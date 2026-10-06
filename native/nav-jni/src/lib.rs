//! Minimal JNI boundary for the route estimator.
//!
//! The Java/Kotlin layer owns scheduling and keeps each handle thread-confined. Handles are opaque
//! registry identifiers rather than native pointers, so stale and double-destroyed handles fail
//! cleanly instead of dereferencing freed memory.

mod device_profile;
mod estimator;
mod logging;
mod network;
mod profiling;
mod route_filter;
mod speed;
mod trust;

pub use estimator::*;
pub use network::*;
pub use route_filter::*;
pub use speed::*;
pub use trust::*;

use imu_nav_core::estimator::{
    GpsObservation, InitialEstimate, MotionObservation, NavigationEstimator, NetworkObservation,
    ObservationTrust, TravelMode as EstimatorTravelMode, TurnObservation, WalkingObservation,
};
use imu_nav_core::network::{GateResult as NetworkGateResult, NetworkSample, NetworkTracker};
use imu_nav_core::route::{GeoPoint, RouteGeometry};
use imu_nav_core::speed::{SpeedEstimate, fuse_speed};
use imu_nav_core::trust::{
    JamDetector, LocationFix, Reason, ReceiverHealth, TrustClassifier, TrustConfig, TrustInput,
    TrustLevel,
};
use imu_nav_core::{Estimate, FilterError, RouteFilter};
use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray, JIntArray, JLongArray};
use jni::sys::{jdouble, jdoubleArray, jint, jintArray, jlong};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const OK: jint = 0;
const REJECTED: jint = 1;
const ERROR_INVALID_HANDLE: jint = -1;
const ERROR_INVALID_INPUT: jint = -2;
const ERROR_INTERNAL: jint = -3;

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
struct NativeRouteState {
    filter: RouteFilter,
    route: Option<Arc<RouteGeometry>>,
}

static FILTERS: OnceLock<Mutex<HashMap<i64, NativeRouteState>>> = OnceLock::new();
static ROUTES: OnceLock<Mutex<HashMap<i64, Arc<RouteGeometry>>>> = OnceLock::new();
struct NativeTrustState {
    classifier: TrustClassifier,
    jam_detector: JamDetector,
}

static TRUST_CLASSIFIERS: OnceLock<Mutex<HashMap<i64, NativeTrustState>>> = OnceLock::new();
static NETWORK_TRACKERS: OnceLock<Mutex<HashMap<i64, NetworkTracker>>> = OnceLock::new();
static ESTIMATORS: OnceLock<Mutex<HashMap<i64, NavigationEstimator>>> = OnceLock::new();

fn filters() -> &'static Mutex<HashMap<i64, NativeRouteState>> {
    FILTERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn trust_classifiers() -> &'static Mutex<HashMap<i64, NativeTrustState>> {
    TRUST_CLASSIFIERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn routes() -> &'static Mutex<HashMap<i64, Arc<RouteGeometry>>> {
    ROUTES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn network_trackers() -> &'static Mutex<HashMap<i64, NetworkTracker>> {
    NETWORK_TRACKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn estimators() -> &'static Mutex<HashMap<i64, NavigationEstimator>> {
    ESTIMATORS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn with_estimator<T>(
    handle: jlong,
    action: impl FnOnce(&mut NavigationEstimator) -> T,
) -> Result<T, jint> {
    if handle <= 0 {
        return Err(ERROR_INVALID_HANDLE);
    }
    let mut registry = estimators().lock().map_err(|_| ERROR_INTERNAL)?;
    let estimator = registry.get_mut(&handle).ok_or(ERROR_INVALID_HANDLE)?;
    Ok(action(estimator))
}

fn with_network_tracker<T>(
    handle: jlong,
    action: impl FnOnce(&mut NetworkTracker) -> T,
) -> Result<T, jint> {
    if handle <= 0 {
        return Err(ERROR_INVALID_HANDLE);
    }
    let mut registry = network_trackers().lock().map_err(|_| ERROR_INTERNAL)?;
    let tracker = registry.get_mut(&handle).ok_or(ERROR_INVALID_HANDLE)?;
    Ok(action(tracker))
}

fn error_code(error: FilterError) -> jint {
    tracing::debug!(?error, "native filter input rejected");
    match error {
        FilterError::NonFinite
        | FilterError::InvalidTimeStep
        | FilterError::InvalidSigma
        | FilterError::InvalidGate
        | FilterError::InvalidCovariance => ERROR_INVALID_INPUT,
    }
}

fn with_filter<T>(handle: jlong, action: impl FnOnce(&mut RouteFilter) -> T) -> Result<T, jint> {
    if handle <= 0 {
        return Err(ERROR_INVALID_HANDLE);
    }
    let mut registry = filters().lock().map_err(|_| ERROR_INTERNAL)?;
    let state = registry.get_mut(&handle).ok_or(ERROR_INVALID_HANDLE)?;
    Ok(action(&mut state.filter))
}

fn with_route_state<T>(
    handle: jlong,
    action: impl FnOnce(&mut NativeRouteState) -> T,
) -> Result<T, jint> {
    if handle <= 0 {
        return Err(ERROR_INVALID_HANDLE);
    }
    let mut registry = filters().lock().map_err(|_| ERROR_INTERNAL)?;
    let state = registry.get_mut(&handle).ok_or(ERROR_INVALID_HANDLE)?;
    Ok(action(state))
}

fn guarded_code(action: impl FnOnce() -> jint) -> jint {
    catch_unwind(AssertUnwindSafe(action)).unwrap_or_else(|_| {
        tracing::error!("native call panicked");
        ERROR_INTERNAL
    })
}

fn optional(value: f64) -> Option<f64> {
    if value.is_nan() { None } else { Some(value) }
}

#[must_use]
fn usize_as_f64(value: usize) -> Option<f64> {
    u32::try_from(value).ok().map(f64::from)
}

// The Kotlin API historically represents recorded elapsed-realtime values in a DoubleArray.
// Android monotonic timestamps remain well inside f64's exact-integer range in practice.
#[allow(clippy::cast_precision_loss)]
#[must_use]
fn elapsed_milliseconds_as_f64(value: i64) -> f64 {
    value as f64
}

/// Serializes an estimate in the stable order consumed by both Kotlin estimator wrappers.
fn estimate_values(estimate: Estimate) -> Option<[f64; 7]> {
    Some([
        estimate.position_m,
        estimate.speed_mps,
        estimate.covariance.position,
        estimate.covariance.position_speed,
        estimate.covariance.speed,
        estimate.systematic_drift_m,
        estimate.safety_radius_m(2.0).ok()?,
    ])
}

/// Copies Rust doubles into a newly allocated Java array and returns its JNI-owned reference.
fn new_double_array(env: &JNIEnv, values: &[f64]) -> Option<jdoubleArray> {
    let output: JDoubleArray = env
        .new_double_array(i32::try_from(values.len()).ok()?)
        .ok()?;
    env.set_double_array_region(&output, 0, values).ok()?;
    Some(output.into_raw())
}

/// Copies Rust integers into a newly allocated Java array and returns its JNI-owned reference.
fn new_int_array(env: &JNIEnv, values: &[jint]) -> Option<jintArray> {
    let output: JIntArray = env.new_int_array(i32::try_from(values.len()).ok()?).ok()?;
    env.set_int_array_region(&output, 0, values).ok()?;
    Some(output.into_raw())
}

fn reason_code(reason: Reason) -> jint {
    match reason {
        Reason::Invalid => 0,
        Reason::Mock => 1,
        Reason::OutsideServiceArea => 2,
        Reason::Altitude => 3,
        Reason::Speed => 4,
        Reason::Accuracy => 5,
        Reason::ClockSkew => 6,
        Reason::DuplicateTime => 7,
        Reason::Jump => 8,
        Reason::SpeedMismatch => 9,
        Reason::Frozen => 10,
        Reason::NetworkDifference => 11,
        Reason::Jam => 12,
        Reason::JamWeak => 13,
        Reason::JamStrong => 14,
        Reason::NoSatellites => 15,
        Reason::FewSatellites => 16,
        Reason::WeakSignal => 17,
        Reason::FlatSignal => 18,
        Reason::HeadingDifference => 19,
    }
}

fn trust_level_code(level: TrustLevel) -> jint {
    match level {
        TrustLevel::Good => 0,
        TrustLevel::Suspect => 1,
        TrustLevel::Bad => 2,
    }
}

#[cfg(test)]
mod tests;
