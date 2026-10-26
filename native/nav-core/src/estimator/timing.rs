//! Integer-only scheduling decisions, shared by live prediction and bounded proofs.
use super::{GPS_HISTORY_MS, MAX_PREDICTION_MS, OBD_MAX_AGE_MS};
use imu_nav_types::ElapsedMs;

/// Select the next boundary after the caller has released expired motion/walking controls.
/// Active controls must expire strictly after `now_ms`, and the target must be later than now.
pub(super) fn prediction_step(
    now_ms: i64,
    target_ms: i64,
    last_vehicle_ms: Option<ElapsedMs>,
    motion_expiry_ms: Option<i64>,
    walking_expiry_ms: Option<i64>,
) -> (i64, bool) {
    let obd_expiry_ms = last_vehicle_ms.map(|last| last.get().saturating_add(OBD_MAX_AGE_MS));
    let obd_fresh = obd_expiry_ms.is_some_and(|expiry| now_ms < expiry);
    let mut end_ms = target_ms.min(now_ms.saturating_add(MAX_PREDICTION_MS));
    if let Some(expiry) = motion_expiry_ms {
        end_ms = end_ms.min(expiry);
    }
    if let Some(expiry) = walking_expiry_ms {
        end_ms = end_ms.min(expiry);
    }
    if let Some(expiry) = obd_expiry_ms.filter(|_| obd_fresh) {
        end_ms = end_ms.min(expiry);
    }
    (end_ms, obd_fresh)
}

/// Only a newer fix within retained history may rewind; initial checkpoints allow equal time.
pub(super) fn gps_time_is_eligible(
    measurement_ms: i64,
    now_ms: i64,
    last_gps_ms: Option<ElapsedMs>,
    oldest_ms: i64,
    oldest_is_initial: bool,
) -> bool {
    measurement_ms >= 0
        && last_gps_ms.is_none_or(|last| measurement_ms > last.get())
        && measurement_ms <= now_ms
        && now_ms.saturating_sub(measurement_ms) <= GPS_HISTORY_MS
        && (measurement_ms > oldest_ms || (measurement_ms == oldest_ms && oldest_is_initial))
}

/// OBD can arrive at the current state time, but cannot repeat an already consumed input.
pub(super) fn vehicle_time_is_eligible(
    measurement_ms: i64,
    now_ms: i64,
    last_input_ms: Option<ElapsedMs>,
) -> bool {
    measurement_ms >= 0
        && measurement_ms >= now_ms
        && last_input_ms.is_none_or(|last| measurement_ms > last.get())
}
