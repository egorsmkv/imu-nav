//! OBD scale learning includes the caller's fusion verdict so trust gates cannot be bypassed.
use super::{
    FilterState, GpsObservation, ObservationTrust, SCALE_BLEND, SCALE_MAX_GPS_SIGMA_M,
    SCALE_MAX_GPS_SIGMA_MPS, SCALE_MAX_OFFSET_M, SCALE_MAX_RATIO, SCALE_MAX_SAMPLE_AGE_MS,
    SCALE_MIN_RATIO, SCALE_MIN_SPEED_MPS, SCALE_STABLE_MS, TravelMode,
};

/// A small convex step preserves the valid calibration interval without changing rounding order.
pub(super) fn blend_scale(previous: f64, ratio: f64) -> f64 {
    previous + SCALE_BLEND * (ratio - previous)
}

impl FilterState {
    /// Learn only from accepted GOOD GNSS near the route and contemporaneous stable OBD.
    /// Keeping both acceptance flags here makes eligibility part of the verified production path.
    pub(super) fn learn_vehicle_speed_scale(
        &mut self,
        mode: TravelMode,
        observation: GpsObservation,
        offset_m: f64,
        accepted: (bool, bool),
    ) -> bool {
        if !accepted.0 || !accepted.1 || observation.trust != ObservationTrust::Good {
            return false;
        }
        let Some(stable) = self.stable_vehicle_speed else {
            return false;
        };
        let Some(gps_speed) = observation.speed_mps else {
            return false;
        };
        let precise_speed = observation.speed_accuracy_mps.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_MPS).contains(&sigma)
        });
        let precise_position = observation.position_accuracy_m.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_M).contains(&sigma)
        });
        if mode != TravelMode::Car
            || !precise_speed
            || !precise_position
            || offset_m >= SCALE_MAX_OFFSET_M
            || gps_speed < SCALE_MIN_SPEED_MPS
            || stable.latest_mps < SCALE_MIN_SPEED_MPS
            || stable.latest_ms.saturating_sub(stable.since_ms) < SCALE_STABLE_MS
            || !(0..=SCALE_MAX_SAMPLE_AGE_MS)
                .contains(&observation.elapsed_ms.saturating_sub(stable.latest_ms))
        {
            return false;
        }
        let ratio = gps_speed / stable.latest_mps;
        if !(SCALE_MIN_RATIO..=SCALE_MAX_RATIO).contains(&ratio) {
            return false;
        }
        self.vehicle_speed_scale = blend_scale(self.vehicle_speed_scale, ratio);
        true
    }
}
