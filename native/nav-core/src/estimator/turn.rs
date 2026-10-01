//! Bounded corrections from isolated completed turns. A gyro rotation is not a precise anchor.
use super::{FilterError, NavigationEstimator, TravelMode};
use crate::milliseconds_to_seconds;

const MAX_AGE_MS: i64 = 2_000;
const MIN_DURATION_MS: i64 = 1_500;
const MAX_DURATION_MS: i64 = 8_000;
const COOLDOWN_MS: i64 = 15_000;
const GPS_FRESH_MS: i64 = 2_500;
const MIN_ANGLE_DEG: f64 = 45.0;
const MAX_ANGLE_DEG: f64 = 125.0;
const ANGLE_TOLERANCE_DEG: f64 = 15.0;
const MIN_SPEED_MPS: f64 = 2.0;
const MAX_SPEED_MPS: f64 = 18.0;
const MIN_WINDOW_M: f64 = 60.0;
const MAX_WINDOW_M: f64 = 180.0;
const MAX_POSITION_SIGMA_M: f64 = 300.0;
const ISOLATION_M: f64 = 80.0;
const MIN_SIGMA_M: f64 = 30.0;
const MAX_SIGMA_M: f64 = 100.0;
const MAX_CORRECTION_M: f64 = 30.0;
const POSITION_GATE: f64 = 9.0;

/// Completed, quality-gated rotation derived from recorded IMU samples by the shared detector.
#[derive(Clone, Copy, Debug)]
pub struct TurnObservation {
    pub start_ms: i64,
    pub end_ms: i64,
    pub angle_deg: f64,
}

/// Deduplication and route ownership are replayed with the filter, never shared with live snaps.
#[derive(Clone, Debug)]
pub(super) struct TurnState {
    since_ms: i64,
    last_seen_ms: i64,
    last_used_ms: Option<i64>,
    last_landmark: Option<usize>,
}

impl TurnState {
    pub(super) fn new(since_ms: i64) -> Self {
        Self {
            since_ms,
            last_seen_ms: -1,
            last_used_ms: None,
            last_landmark: None,
        }
    }
}

impl NavigationEstimator {
    /// Matches at the approximate turn midpoint and advances by current speed, widening error
    /// for turn duration and uncertain speed. Ambiguous or imprecise evidence stays inert.
    pub(super) fn apply_turn(
        &mut self,
        observation: Option<TurnObservation>,
    ) -> Result<bool, FilterError> {
        let Some(observation) = observation else {
            return Ok(false);
        };
        let now_ms = self.state.elapsed_ms;
        let duration_ms = observation.end_ms.saturating_sub(observation.start_ms);
        if self.mode != TravelMode::Car
            || observation.start_ms < self.state.turn_state.since_ms
            || observation.end_ms <= self.state.turn_state.last_seen_ms
            || !(0..=MAX_AGE_MS).contains(&now_ms.saturating_sub(observation.end_ms))
            || !(MIN_DURATION_MS..=MAX_DURATION_MS).contains(&duration_ms)
            || !observation.angle_deg.is_finite()
            || !(MIN_ANGLE_DEG..=MAX_ANGLE_DEG).contains(&observation.angle_deg.abs())
        {
            return Ok(false);
        }
        self.state.turn_state.last_seen_ms = observation.end_ms;
        let estimate = self.estimate();
        if self.state.motion_control.is_some()
            || !(MIN_SPEED_MPS..=MAX_SPEED_MPS).contains(&estimate.speed_mps)
            || estimate.position_sigma_m() > MAX_POSITION_SIGMA_M
            || (self.state.last_gps_position_ms >= 0
                && now_ms - self.state.last_gps_position_ms <= GPS_FRESH_MS)
            || self
                .state
                .turn_state
                .last_used_ms
                .is_some_and(|last| observation.end_ms - last < COOLDOWN_MS)
        {
            return Ok(false);
        }
        let midpoint_ms = observation.start_ms + duration_ms / 2;
        let age_s = milliseconds_to_seconds(now_ms - midpoint_ms);
        let advance_m = estimate.speed_mps * age_s;
        let sigma_m = MIN_SIGMA_M
            + 0.5 * estimate.speed_mps * milliseconds_to_seconds(duration_ms)
            + estimate.speed_sigma_mps() * age_s;
        if sigma_m > MAX_SIGMA_M {
            return Ok(false);
        }
        let center_m = estimate.position_m - advance_m;
        let window_m = (2.0 * estimate.position_sigma_m() + estimate.systematic_drift_m)
            .clamp(MIN_WINDOW_M, MAX_WINDOW_M);
        let mut candidates = self
            .route
            .turns()
            .iter()
            .enumerate()
            .filter(|(_, landmark)| {
                (landmark.position_m - center_m).abs() <= window_m
                    && (landmark.angle_deg - observation.angle_deg).abs() <= ANGLE_TOLERANCE_DEG
            });
        let Some((index, landmark)) = candidates.next() else {
            return Ok(false);
        };
        if candidates.next().is_some()
            || self.state.turn_state.last_landmark == Some(index)
            || self.route.turns().iter().enumerate().any(|(other, rival)| {
                other != index && (rival.position_m - landmark.position_m).abs() < ISOLATION_M
            })
        {
            return Ok(false);
        }
        let accepted = self.state.filter.update_coarse_position(
            landmark.position_m + advance_m,
            sigma_m,
            POSITION_GATE,
            MAX_CORRECTION_M,
        )?;
        if accepted {
            self.state.turn_state.last_used_ms = Some(observation.end_ms);
            self.state.turn_state.last_landmark = Some(index);
        }
        Ok(accepted)
    }
}

#[cfg(test)]
mod tests;
