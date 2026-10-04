//! Conservative coarse-position evidence. Repeated tower estimates are correlated, not anchors.

use super::{
    FilterError, GeoPoint, MotionObservation, NavigationEstimator, OBD_MAX_AGE_MS, TravelMode,
};
use crate::milliseconds_to_seconds;
mod speed;
use speed::{NetworkUse, SpeedBatch};

const MAX_AGE_MS: i64 = 2_500;
const MIN_INTERVAL_MS: i64 = 5_000;
const MAX_GAP_MS: i64 = 15_000;
const MIN_SPAN_MS: i64 = 10_000;
const MIN_FIXES: u8 = 3;
const MIN_ACCURACY_M: f64 = 30.0;
const MAX_ACCURACY_M: f64 = 200.0;
const MAX_SPEED_MPS: f64 = 150.0 / 3.6;
const MAX_BACKWARD_MPS: f64 = 6.0;
const MAX_JUMP_M: f64 = 500.0;
const MAX_CORRECTION_M: f64 = 50.0;
const POSITION_GATE: f64 = 9.0;
const SPEED_GATE: f64 = 9.0;
const MAX_SPEED_CORRECTION_MPS: f64 = 2.0;
const MANEUVER_SPEED_CORRECTION_MPS: f64 = 4.0;
const SPEED_SIGMA_PER_SECOND: f64 = 0.5;
const RECOVERY_SPEED_SIGMA_MPS: f64 = 6.0;
const CACHE_SIZE: usize = 8;

/// Only non-mock CELL/NET fixes may enter here; the JNI wrapper excludes GPS and fused fixes.
#[derive(Clone, Copy, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
pub struct NetworkObservation {
    pub point: GeoPoint,
    pub elapsed_ms: i64,
    pub accuracy_m: f64,
}

#[derive(Clone, Copy, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
struct Candidate {
    first_ms: i64,
    last_ms: i64,
    position_m: f64,
    accuracy_m: f64,
    residual_m: f64,
    count: u8,
}

/// Checkpointed together with the filter, so delayed GNSS re-evaluates coarse corrections.
#[derive(Clone, Debug)]
#[cfg_attr(all(test, not(kani)), derive(PartialEq))]
pub(super) struct NetworkEvidence {
    valid_after_ms: i64,
    last_input_ms: i64,
    last_evaluated_ms: i64,
    seen: [Option<GeoPoint>; CACHE_SIZE],
    next_seen: usize,
    candidate: Option<Candidate>,
    speed: SpeedBatch,
}

impl NetworkEvidence {
    pub(super) fn new(now_ms: i64) -> Self {
        Self {
            valid_after_ms: now_ms,
            last_input_ms: -1,
            last_evaluated_ms: -1,
            seen: [None; CACHE_SIZE],
            next_seen: 0,
            candidate: None,
            speed: SpeedBatch::new(),
        }
    }

    /// Preserve duplicate protection across rerouting, but discard the old route's sequence.
    pub(super) fn reroute(&mut self, now_ms: i64) {
        self.valid_after_ms = now_ms;
        self.candidate = None;
        self.speed.clear();
    }

    pub(super) fn clear_speed(&mut self) {
        self.speed.clear();
    }
}

impl NavigationEstimator {
    /// Apply fresh coarse locations at the tick time with an age-dependent error allowance.
    /// Three distinct reachable fixes over ten seconds are required; one jump never corrects.
    pub(super) fn apply_network(
        &mut self,
        observation: Option<NetworkObservation>,
        motion: Option<MotionObservation>,
    ) -> Result<bool, FilterError> {
        // A stop between cell scans also invalidates the unfinished cruising window.
        if self.state.motion_control.is_some()
            || motion.is_some_and(|hint| hint.factor < 1.0 || !hint.factor.is_finite())
        {
            self.state.network_evidence.speed.clear();
        }
        let Some(observation) = observation else {
            return Ok(false);
        };
        let now_ms = self.state.elapsed_ms;
        let predicted_position_m = self.estimate().position_m;
        let age_ms = now_ms.saturating_sub(observation.elapsed_ms);
        let evidence = &mut self.state.network_evidence;
        if self.mode != TravelMode::Car
            || !(0..=MAX_AGE_MS).contains(&age_ms)
            || observation.elapsed_ms < evidence.valid_after_ms
            || observation.elapsed_ms <= evidence.last_input_ms
            || !observation.accuracy_m.is_finite()
            || !(0.0..=MAX_ACCURACY_M).contains(&observation.accuracy_m)
            || observation.accuracy_m == 0.0
            || !(-90.0..=90.0).contains(&observation.point.latitude_deg)
            || !(-180.0..=180.0).contains(&observation.point.longitude_deg)
        {
            return Ok(false);
        }
        evidence.last_input_ms = observation.elapsed_ms;
        if evidence
            .seen
            .iter()
            .flatten()
            .any(|point| *point == observation.point)
        {
            return Ok(false);
        }
        if evidence.last_evaluated_ms >= 0
            && observation.elapsed_ms - evidence.last_evaluated_ms < MIN_INTERVAL_MS
        {
            return Ok(false);
        }
        evidence.last_evaluated_ms = observation.elapsed_ms;
        evidence.seen[evidence.next_seen] = Some(observation.point);
        evidence.next_seen = (evidence.next_seen + 1) % CACHE_SIZE;
        let accuracy_m = observation.accuracy_m.max(MIN_ACCURACY_M);
        let Some(projected) = self
            .route
            .project_unambiguous(observation.point, accuracy_m)
            .map_err(|_| FilterError::NonFinite)?
        else {
            evidence.candidate = None;
            evidence.speed.clear();
            return Ok(false);
        };
        if projected.offset_m > accuracy_m {
            evidence.candidate = None;
            evidence.speed.clear();
            return Ok(false);
        }
        let mut candidate = Candidate {
            first_ms: observation.elapsed_ms,
            last_ms: observation.elapsed_ms,
            position_m: projected.position_m,
            accuracy_m,
            residual_m: projected.position_m - predicted_position_m,
            count: 1,
        };
        if let Some(previous) = evidence.candidate {
            let gap_ms = observation.elapsed_ms - previous.last_ms;
            let dt = milliseconds_to_seconds(gap_ms);
            let delta_m = projected.position_m - previous.position_m;
            let slack_m = accuracy_m + previous.accuracy_m;
            if gap_ms <= MAX_GAP_MS
                && delta_m <= MAX_SPEED_MPS * dt + slack_m
                && delta_m >= -MAX_BACKWARD_MPS * dt - slack_m
                && (candidate.residual_m - previous.residual_m).abs() <= 2.0 * slack_m
            {
                candidate.first_ms = previous.first_ms;
                candidate.count = previous.count.saturating_add(1);
            }
        }
        evidence.candidate = Some(candidate);
        if candidate.count == 1 {
            evidence.speed.clear();
        }
        self.apply_network_estimates(candidate, age_ms, motion)
    }

    /// Allocate each fix to only one channel. Model-speed changes never masquerade as OBD/GPS.
    fn apply_network_estimates(
        &mut self,
        candidate: Candidate,
        age_ms: i64,
        motion: Option<MotionObservation>,
    ) -> Result<bool, FilterError> {
        let now_ms = self.state.elapsed_ms;
        let fresh_gps = self.state.last_gps_position_ms >= 0
            && now_ms.saturating_sub(self.state.last_gps_position_ms) <= MAX_AGE_MS;
        if fresh_gps || candidate.residual_m.abs() > MAX_JUMP_M {
            self.state.network_evidence.speed.clear();
            return Ok(false);
        }
        let measured_speed = (self.state.last_gps_speed_ms >= 0
            && now_ms - self.state.last_gps_speed_ms <= MAX_AGE_MS)
            || (self.state.last_vehicle_speed_ms >= 0
                && now_ms - self.state.last_vehicle_speed_ms < OBD_MAX_AGE_MS);
        let motion_blocked = self.state.motion_control.is_some()
            || motion.is_some_and(|hint| hint.factor < 1.0 || !hint.factor.is_finite());
        if self.network_speed_enabled && !measured_speed && !motion_blocked {
            let previous_speed = self.estimate().speed_mps;
            let allocation = self
                .state
                .network_evidence
                .speed
                .select(candidate, previous_speed);
            if let Some(accepted) = self.apply_network_speed(allocation, age_ms)? {
                return Ok(accepted);
            }
        } else {
            self.state.network_evidence.speed.clear();
        }
        if candidate.count < MIN_FIXES || candidate.last_ms - candidate.first_ms < MIN_SPAN_MS {
            return Ok(false);
        }
        // Do not extrapolate using the same DR speed we are trying to correct. Inflate instead.
        let sigma_m = 2.0 * candidate.accuracy_m + MAX_SPEED_MPS * milliseconds_to_seconds(age_ms);
        self.state.filter.update_coarse_position(
            candidate.position_m,
            sigma_m,
            POSITION_GATE,
            MAX_CORRECTION_M,
        )
    }

    /// A speed allocation always consumes the fix, even when rejected or restoring a prior.
    /// Only Position returns None, allowing the caller to evaluate a position correction.
    fn apply_network_speed(
        &mut self,
        allocation: NetworkUse,
        age_ms: i64,
    ) -> Result<Option<bool>, FilterError> {
        let previous_speed = self.estimate().speed_mps;
        match allocation {
            NetworkUse::Reserved => Ok(Some(false)),
            NetworkUse::Speed(estimate) => {
                // The mean describes a time window, not instantaneous speed during a manoeuvre.
                let sigma =
                    estimate.sigma_mps + SPEED_SIGMA_PER_SECOND * milliseconds_to_seconds(age_ms);
                let correction_limit = if self.state.network_evidence.speed.relearning() {
                    self.state
                        .filter
                        .restore_speed_prior(previous_speed, RECOVERY_SPEED_SIGMA_MPS)?;
                    MANEUVER_SPEED_CORRECTION_MPS
                } else {
                    MAX_SPEED_CORRECTION_MPS
                };
                let accepted = self.state.filter.update_coarse_speed(
                    estimate.speed_mps,
                    sigma,
                    SPEED_GATE,
                    correction_limit,
                )?;
                if accepted {
                    self.state.network_evidence.speed.accepted(
                        previous_speed,
                        estimate,
                        self.state.filter.estimate().speed_mps,
                    );
                }
                Ok(Some(accepted))
            }
            NetworkUse::RestorePrior(speed_mps) => {
                // Retract cell learning without reducing uncertainty or claiming a new sensor.
                self.state
                    .filter
                    .restore_speed_prior(speed_mps, RECOVERY_SPEED_SIGMA_MPS)?;
                Ok(Some(false))
            }
            NetworkUse::Position => Ok(None),
        }
    }
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
mod kani_proofs;
