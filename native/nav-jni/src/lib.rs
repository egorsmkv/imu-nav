//! Minimal JNI boundary for the route estimator.
//!
//! The Java/Kotlin layer owns scheduling and keeps each handle thread-confined. Handles are opaque
//! registry identifiers rather than native pointers, so stale and double-destroyed handles fail
//! cleanly instead of dereferencing freed memory.

mod profiling;

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
    catch_unwind(AssertUnwindSafe(action)).unwrap_or(ERROR_INTERNAL)
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeSpeedFusion_nativeFuse(
    _env: JNIEnv,
    _class: JClass,
    last_gps_speed_mps: jdouble,
    gps_age_ms: jlong,
    route_prior_mps: jdouble,
    network_speed_mps: jdouble,
    network_sigma_mps: jdouble,
    network_samples: jint,
    network_span_s: jdouble,
) -> jdouble {
    catch_unwind(AssertUnwindSafe(|| {
        let network = optional(network_speed_mps).map(|speed_mps| SpeedEstimate {
            speed_mps,
            sigma_mps: network_sigma_mps,
            samples: usize::try_from(network_samples).unwrap_or(0),
            span_s: network_span_s,
        });
        fuse_speed(
            optional(last_gps_speed_mps),
            gps_age_ms,
            optional(route_prior_mps),
            network,
        )
        .map_or(0.0, |estimate| estimate.speed_mps)
    }))
    .unwrap_or(0.0)
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    position_m: jdouble,
    speed_mps: jdouble,
    position_sigma_m: jdouble,
    speed_sigma_mps: jdouble,
    systematic_drift_m: jdouble,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let filter = RouteFilter::new(
            position_m,
            speed_mps,
            position_sigma_m,
            speed_sigma_mps,
            systematic_drift_m,
        )
        .ok()?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        filters().lock().ok()?.insert(
            handle,
            NativeRouteState {
                filter,
                route: None,
            },
        );
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = filters().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativePredict(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    dt_s: jdouble,
    acceleration_sigma_mps2: jdouble,
    systematic_drift_per_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.predict(dt_s, acceleration_sigma_mps2, systematic_drift_per_m)
        }) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeUpdatePosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_m: jdouble,
    sigma_m: jdouble,
    nis_gate: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.update_position(position_m, sigma_m, nis_gate)
        }) {
            Ok(Ok(outcome)) => {
                if outcome.accepted {
                    OK
                } else {
                    REJECTED
                }
            }
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeUpdateSpeed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    speed_mps: jdouble,
    sigma_mps: jdouble,
    nis_gate: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| {
            filter.update_speed(speed_mps, sigma_mps, nis_gate)
        }) {
            Ok(Ok(outcome)) => {
                if outcome.accepted {
                    OK
                } else {
                    REJECTED
                }
            }
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeAnchorPosition(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_m: jdouble,
    sigma_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_filter(handle, |filter| filter.anchor_position(position_m, sigma_m)) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeResetSystematicDrift(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_filter(handle, RouteFilter::reset_systematic_drift) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeCreate(
    env: JNIEnv,
    _class: JClass,
    coordinates: JDoubleArray,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(length) = env.get_array_length(&coordinates) else {
            return None;
        };
        if length < 4 || length % 2 != 0 {
            return None;
        }
        let mut values = vec![0.0; usize::try_from(length).ok()?];
        if env
            .get_double_array_region(&coordinates, 0, &mut values)
            .is_err()
        {
            return None;
        }
        let points = values
            .as_chunks::<2>()
            .0
            .iter()
            .map(|&[latitude_deg, longitude_deg]| GeoPoint {
                latitude_deg,
                longitude_deg,
            })
            .collect();
        let route = Arc::new(RouteGeometry::new(points).ok()?);
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        routes().lock().ok()?.insert(handle, route);
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = routes().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteGeometry_nativeProject(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    latitude_deg: jdouble,
    longitude_deg: jdouble,
    around_m: jdouble,
    behind_m: jdouble,
    ahead_m: jdouble,
    global_if_farther_m: jdouble,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let route = {
            let registry = routes().lock().ok()?;
            Arc::clone(registry.get(&handle)?)
        };
        let projection = route
            .project(
                GeoPoint {
                    latitude_deg,
                    longitude_deg,
                },
                around_m,
                behind_m,
                ahead_m,
                global_if_farther_m,
            )
            .ok()?;
        let values = [
            projection.position_m,
            projection.offset_m,
            usize_as_f64(projection.segment)?,
            projection.point.latitude_deg,
            projection.point.longitude_deg,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeInstallRoute(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    route_handle: jlong,
) -> jint {
    guarded_code(|| {
        let route = {
            let Ok(registry) = routes().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(route) = registry.get(&route_handle) else {
                return ERROR_INVALID_HANDLE;
            };
            Arc::clone(route)
        };
        match with_route_state(handle, |state| state.route = Some(route)) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeProject(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    latitude_deg: jdouble,
    longitude_deg: jdouble,
    around_m: jdouble,
    behind_m: jdouble,
    ahead_m: jdouble,
    global_if_farther_m: jdouble,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let projection = with_route_state(handle, |state| {
            state
                .route
                .as_ref()?
                .project(
                    GeoPoint {
                        latitude_deg,
                        longitude_deg,
                    },
                    around_m,
                    behind_m,
                    ahead_m,
                    global_if_farther_m,
                )
                .ok()
        })
        .ok()
        .flatten()?;
        let values = [
            projection.position_m,
            projection.offset_m,
            usize_as_f64(projection.segment)?,
            projection.point.latitude_deg,
            projection.point.longitude_deg,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeRouteFilter_nativeGetState(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let estimate = with_filter(handle, |filter| filter.estimate()).ok()?;
        let values = estimate_values(estimate)?;
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        trust_classifiers().lock().ok()?.insert(
            handle,
            NativeTrustState {
                classifier: TrustClassifier::new(TrustConfig::default()),
                jam_detector: JamDetector::default(),
            },
        );
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(state) = registry.get_mut(&handle) else {
            return ERROR_INVALID_HANDLE;
        };
        state.classifier.reset();
        state.jam_detector.reset();
        OK
    })
}

/// Returns bit 0 as the current jam state and bit 1 when the state changed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeUpdateAgc(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    agc_db: jdouble,
    now_ms: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = trust_classifiers().lock() else {
            return ERROR_INTERNAL;
        };
        let Some(state) = registry.get_mut(&handle) else {
            return ERROR_INVALID_HANDLE;
        };
        let changed = state.jam_detector.update(optional(agc_db), now_ms);
        jint::from(state.jam_detector.jammed()) | (jint::from(changed) << 1)
    })
}

/// Evaluate arrays documented by `NativeTrustEvaluator`. The first returned integer is the trust
/// level (0 GOOD, 1 SUSPECT, 2 BAD); remaining integers are stable reason codes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeTrustEvaluator_nativeEvaluate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    doubles: JDoubleArray,
    longs: JLongArray,
) -> jintArray {
    catch_unwind(AssertUnwindSafe(|| {
        if env.get_array_length(&doubles).ok()? < 14 || env.get_array_length(&longs).ok()? < 11 {
            return None;
        }
        let mut d = [0.0; 14];
        let mut l = [0_i64; 11];
        env.get_double_array_region(&doubles, 0, &mut d).ok()?;
        env.get_long_array_region(&longs, 0, &mut l).ok()?;
        let fix = LocationFix {
            wall_time_ms: l[0],
            elapsed_ms: l[1],
            latitude_deg: d[0],
            longitude_deg: d[1],
            altitude_m: optional(d[2]),
            speed_mps: optional(d[3]),
            bearing_deg: optional(d[4]),
            horizontal_accuracy_m: optional(d[5]),
            vertical_accuracy_m: optional(d[6]),
            is_mock: l[2] != 0,
        };
        let network_fix = if l[4] != 0 {
            Some(LocationFix {
                wall_time_ms: fix.wall_time_ms,
                elapsed_ms: l[5],
                latitude_deg: d[7],
                longitude_deg: d[8],
                altitude_m: None,
                speed_mps: None,
                bearing_deg: None,
                horizontal_accuracy_m: optional(d[9]),
                vertical_accuracy_m: None,
                is_mock: false,
            })
        } else {
            None
        };
        let mut input = TrustInput {
            fix,
            network_fix,
            receiver: ReceiverHealth {
                satellites_visible: u16::try_from(l[6]).ok()?,
                satellites_used: u16::try_from(l[7]).ok()?,
                mean_cn0_used: optional(d[10]),
                cn0_spread_used: optional(d[11]),
                agc_db: optional(d[12]),
                dual_frequency_used: u16::try_from(l[8]).ok()?,
                elapsed_ms: l[9],
            },
            wall_now_ms: l[10],
            compass_deg: optional(d[13]),
            jammed: false,
            inside_service_area: l[3] != 0,
        };
        let verdict = {
            let mut registry = trust_classifiers().lock().ok()?;
            let state = registry.get_mut(&handle)?;
            input.jammed = state.jam_detector.jammed();
            state.classifier.evaluate(input)
        };
        let mut result = Vec::with_capacity(verdict.reasons.len() + 1);
        result.push(trust_level_code(verdict.level));
        result.extend(verdict.reasons.into_iter().map(reason_code));
        new_int_array(&env, &result)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        network_trackers()
            .lock()
            .ok()?
            .insert(handle, NetworkTracker::default());
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = network_trackers().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeReset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, NetworkTracker::reset) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeClearSamples(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, NetworkTracker::clear_samples) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeGate(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    elapsed_ms: jlong,
    position_m: jdouble,
    accuracy_m: jdouble,
) -> jint {
    guarded_code(|| {
        match with_network_tracker(handle, |tracker| {
            tracker.gate(elapsed_ms, position_m, accuracy_m)
        }) {
            Ok(NetworkGateResult::Accepted) => 0,
            Ok(NetworkGateResult::Reanchored) => 1,
            Ok(NetworkGateResult::Rejected) => 2,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeRecord(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    elapsed_ms: jlong,
    values: JDoubleArray,
) -> jint {
    guarded_code(|| {
        if env.get_array_length(&values).ok() != Some(5) {
            return ERROR_INVALID_INPUT;
        }
        let mut data = [0.0; 5];
        if env.get_double_array_region(&values, 0, &mut data).is_err() {
            return ERROR_INVALID_INPUT;
        }
        match with_network_tracker(handle, |tracker| {
            tracker.record(
                NetworkSample {
                    elapsed_ms,
                    position_m: data[0],
                    accuracy_m: data[1],
                    offset_m: data[2],
                },
                data[3],
                data[4],
            );
        }) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativePruneHistory(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, |tracker| tracker.prune_history(now_ms)) {
            Ok(()) => OK,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeLastTwoConsistent(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(
        || match with_network_tracker(handle, |tracker| tracker.last_two_consistent()) {
            Ok(false) => 0,
            Ok(true) => 1,
            Err(code) => code,
        },
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeEstimate(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
    strict: jint,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let estimate = with_network_tracker(handle, |tracker| {
            if strict == 0 {
                tracker.speed_estimate(now_ms)
            } else {
                tracker.strict_speed_estimate(now_ms)
            }
        })
        .ok()
        .flatten()?;
        let values = [
            estimate.speed_mps,
            estimate.sigma_mps,
            usize_as_f64(estimate.samples)?,
            estimate.span_s,
        ];
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNetworkTracker_nativeSamples(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    history: jint,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let samples = with_network_tracker(handle, |tracker| {
            if history == 0 {
                tracker.recent().to_vec()
            } else {
                tracker.history().to_vec()
            }
        })
        .ok()?;
        let values: Vec<f64> = samples
            .iter()
            .flat_map(|sample| {
                [
                    elapsed_milliseconds_as_f64(sample.elapsed_ms),
                    sample.position_m,
                    sample.accuracy_m,
                    sample.offset_m,
                ]
            })
            .collect();
        new_double_array(&env, &values)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeCreate(
    env: JNIEnv,
    _class: JClass,
    route_handle: jlong,
    initial_values: JDoubleArray,
    mode: jint,
    now_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if env.get_array_length(&initial_values).ok()? != 5 {
            return None;
        }
        let mut values = [0.0; 5];
        env.get_double_array_region(&initial_values, 0, &mut values)
            .ok()?;
        let route = {
            let registry = routes().lock().ok()?;
            Arc::clone(registry.get(&route_handle)?)
        };
        let travel_mode = match mode {
            0 => EstimatorTravelMode::Car,
            1 => EstimatorTravelMode::Foot,
            _ => return None,
        };
        let estimator = NavigationEstimator::new(
            route,
            InitialEstimate {
                position_m: values[0],
                speed_mps: values[1],
                position_sigma_m: values[2],
                speed_sigma_mps: values[3],
                systematic_drift_m: values[4],
            },
            travel_mode,
            now_ms,
        )
        .ok()?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle <= 0 {
            return None;
        }
        estimators().lock().ok()?.insert(handle, estimator);
        Some(handle)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guarded_code(|| {
        let Ok(mut registry) = estimators().lock() else {
            return ERROR_INTERNAL;
        };
        if registry.remove(&handle).is_some() {
            OK
        } else {
            ERROR_INVALID_HANDLE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeSetNetworkSpeedEnabled(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    enabled: jint,
) -> jint {
    guarded_code(|| {
        if !(0..=1).contains(&enabled) {
            return ERROR_INTERNAL;
        }
        match with_estimator(handle, |estimator| {
            estimator.set_network_speed_enabled(enabled == 1);
        }) {
            Ok(()) => OK,
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeOnVehicleSpeed(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    speed_kmh: jdouble,
    elapsed_ms: jlong,
) -> jint {
    guarded_code(|| {
        match with_estimator(handle, |estimator| {
            estimator.on_vehicle_speed(speed_kmh, elapsed_ms)
        }) {
            Ok(Ok(false)) => REJECTED,
            Ok(Ok(true)) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeReplaceRoute(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    route_handle: jlong,
    position_m: jdouble,
    position_sigma_m: jdouble,
) -> jint {
    guarded_code(|| {
        let route = {
            let Ok(registry) = routes().lock() else {
                return ERROR_INTERNAL;
            };
            let Some(route) = registry.get(&route_handle) else {
                return ERROR_INVALID_HANDLE;
            };
            Arc::clone(route)
        };
        match with_estimator(handle, |estimator| {
            estimator.replace_route(route, position_m, position_sigma_m)
        }) {
            Ok(Ok(())) => OK,
            Ok(Err(error)) => error_code(error),
            Err(code) => code,
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_imunav_app_nativecore_NativeNavigationEstimator_nativeTick(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    now_ms: jlong,
    doubles: JDoubleArray,
    longs: JLongArray,
) -> jdoubleArray {
    catch_unwind(AssertUnwindSafe(|| {
        let double_count = env.get_array_length(&doubles).ok()?;
        let long_count = env.get_array_length(&longs).ok()?;
        let (double_len, long_len) = match (double_count, long_count) {
            (11, 11) => (11, 11),
            (10, 8) => (10, 8),
            (7, 6) => (7, 6),
            (5, 3) => (5, 3),
            _ => return None,
        };
        let has_turn_fields = double_len == 11;
        let has_network_fields = double_len >= 10;
        let has_motion_fields = double_len >= 7;
        let mut values = [0.0; 11];
        let mut flags = [0_i64; 11];
        env.get_double_array_region(&doubles, 0, &mut values[..double_len])
            .ok()?;
        env.get_long_array_region(&longs, 0, &mut flags[..long_len])
            .ok()?;
        let gps = if flags[0] == 0 {
            None
        } else {
            Some(GpsObservation {
                point: GeoPoint {
                    latitude_deg: values[0],
                    longitude_deg: values[1],
                },
                elapsed_ms: flags[1],
                position_accuracy_m: optional(values[2]),
                speed_mps: optional(values[3]),
                speed_accuracy_mps: optional(values[4]),
                trust: match flags[2] {
                    0 => ObservationTrust::Good,
                    1 => ObservationTrust::Suspect,
                    _ => return None,
                },
            })
        };
        let motion = if has_motion_fields && flags[3] == 1 {
            Some(MotionObservation {
                factor: values[5],
                cruise_speed_mps: values[6],
                valid_until_ms: flags[4],
                network_moving: match flags[5] {
                    0 => false,
                    1 => true,
                    _ => return None,
                },
            })
        } else {
            None
        };
        let network = if has_network_fields && flags[6] == 1 {
            Some(NetworkObservation {
                point: GeoPoint {
                    latitude_deg: values[7],
                    longitude_deg: values[8],
                },
                accuracy_m: values[9],
                elapsed_ms: flags[7],
            })
        } else {
            None
        };
        let walking = if has_motion_fields && flags[3] == 2 {
            Some(WalkingObservation {
                speed_mps: values[5] * values[6],
                valid_until_ms: flags[4],
            })
        } else {
            None
        };
        let turn = if has_turn_fields && flags[8] == 1 {
            Some(TurnObservation {
                start_ms: flags[9],
                end_ms: flags[10],
                angle_deg: values[10],
            })
        } else {
            None
        };
        let outcome = with_estimator(handle, |estimator| {
            estimator.tick_with_walking(now_ms, gps, motion, network, turn, walking)
        })
        .ok()?
        .ok()?;
        let mut result = estimate_values(outcome.estimate)?.to_vec();
        result.push(if outcome.position_accepted { 1.0 } else { 0.0 });
        result.push(if outcome.speed_accepted { 1.0 } else { 0.0 });
        new_double_array(&env, &result)
    }))
    .ok()
    .flatten()
    .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use imu_nav_core::Covariance2;

    #[test]
    fn estimate_values_preserves_kotlin_wire_order() {
        let values = estimate_values(Estimate {
            position_m: 1.0,
            speed_mps: 2.0,
            covariance: Covariance2 {
                position: 3.0,
                position_speed: 4.0,
                speed: 5.0,
            },
            systematic_drift_m: 6.0,
        })
        .expect("the fixed safety multiplier is valid");
        let expected = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 6.0 + 2.0 * 3.0_f64.sqrt()];

        for (actual, expected) in values.into_iter().zip(expected) {
            assert!((actual - expected).abs() < f64::EPSILON);
        }
    }
}
