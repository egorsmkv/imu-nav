//! Disjoint speed batches: reserved fixes never also update position, even if fitting fails.
use super::Candidate;
use crate::milliseconds_to_seconds;
use crate::speed::{NetworkSpeedEstimator, SpeedEstimate};

const BATCH_SIZE: usize = 4;
const MAX_BATCH_SIZE: usize = 7;
const MIN_SPAN_S: f64 = 30.0;
const MAX_SPAN_S: f64 = 60.0;
const MIN_SIGMA_MPS: f64 = 2.0;
const MAX_SIGMA_MPS: f64 = 4.0;
const MIN_MOVING_MPS: f64 = 1.0;
const MAX_SPEED_MPS: f64 = 150.0 / 3.6;
const MIN_CHANGE_MPS: f64 = 3.0;
const RECOVERY_MS: i64 = 30_000;
const MANEUVER_POSITION_MS: i64 = 15_000;

pub(super) enum NetworkUse {
    Position,
    Reserved,
    Speed(SpeedEstimate),
    RestorePrior(f64),
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod recovery_tests;

#[cfg(test)]
mod maneuver_tests;

/// Fixed storage is cheap to checkpoint; consecutive batches share no samples.
#[derive(Clone, Debug)]
#[cfg_attr(all(test, not(kani)), derive(PartialEq))]
pub(super) struct SpeedBatch {
    samples: [Option<Candidate>; MAX_BATCH_SIZE],
    count: usize,
    reserve_next: bool,
    prior_speed_mps: Option<f64>,
    previous_reserved: Option<Candidate>,
    previous_departure: f64,
    recovery_until_ms: i64,
    reference_speed_mps: Option<f64>,
    previous_change_departure: f64,
    relearning: bool,
}

impl SpeedBatch {
    pub(super) fn new() -> Self {
        Self {
            samples: [None; MAX_BATCH_SIZE],
            count: 0,
            reserve_next: true,
            prior_speed_mps: None,
            previous_reserved: None,
            previous_departure: 0.0,
            recovery_until_ms: -1,
            reference_speed_mps: None,
            previous_change_departure: 0.0,
            relearning: false,
        }
    }

    pub(super) fn clear(&mut self) {
        *self = Self::new();
    }

    /// Alternate eligible position/speed fixes. Never return a failed speed batch to position.
    pub(super) fn select(&mut self, candidate: Candidate, current_speed_mps: f64) -> NetworkUse {
        if candidate.last_ms < self.recovery_until_ms {
            return NetworkUse::Position;
        }
        let reserved = self.reserve_next;
        self.reserve_next = !reserved;
        if !reserved {
            return NetworkUse::Position;
        }
        if let Some(prior) = self.observe_change(candidate, current_speed_mps) {
            self.clear();
            self.recovery_until_ms = candidate.last_ms.saturating_add(RECOVERY_MS);
            return NetworkUse::RestorePrior(prior);
        }
        self.samples[self.count] = Some(candidate);
        self.count += 1;
        if self.count < BATCH_SIZE {
            return NetworkUse::Reserved;
        }
        let estimate = self.fit();
        // A slow but coherent moving window can need more distance to separate speed from noise.
        if estimate.is_some_and(|fit| fit.speed_mps - 3.0 * fit.sigma_mps <= MIN_MOVING_MPS)
            && self.count < MAX_BATCH_SIZE
            && estimate.is_some_and(|fit| {
                fit.span_s < MAX_SPAN_S && fit.speed_mps > MIN_MOVING_MPS + 3.0 * MIN_SIGMA_MPS
            })
        {
            return NetworkUse::Reserved;
        }
        let estimate = estimate.filter(|fit| fit.speed_mps - 3.0 * fit.sigma_mps > MIN_MOVING_MPS);
        self.samples = [None; MAX_BATCH_SIZE];
        self.count = 0;
        if estimate.is_none() && self.relearning {
            // A mixed manoeuvre window is not a speed measurement. Give subsequent fixes to
            // position while the window clears, then collect a completely new speed batch.
            self.recovery_until_ms = candidate.last_ms.saturating_add(MANEUVER_POSITION_MS);
            self.reserve_next = true;
            self.previous_reserved = None;
            self.previous_change_departure = 0.0;
        }
        estimate.map_or(NetworkUse::Reserved, NetworkUse::Speed)
    }

    /// Remember the pre-learning model, not the most recent cell correction. A rejected fit cannot
    /// install a fallback. New accepted fits start a fresh two-interval consistency check.
    pub(super) fn accepted(
        &mut self,
        previous_speed_mps: f64,
        estimate: SpeedEstimate,
        updated_speed_mps: f64,
    ) {
        self.prior_speed_mps.get_or_insert(previous_speed_mps);
        self.previous_departure = 0.0;
        self.previous_change_departure = 0.0;
        self.reference_speed_mps = Some(estimate.speed_mps);
        self.relearning =
            self.relearning && (estimate.speed_mps - updated_speed_mps).abs() > estimate.sigma_mps;
    }

    pub(super) fn relearning(&self) -> bool {
        self.relearning
    }

    /// Consecutive departures from the last fitted trend mark a manoeuvre even if the old prior is
    /// worse. A useful prior can still be restored immediately; otherwise only a coherent full fit
    /// may change speed. Comparing against the fitted trend avoids repeatedly flagging model lag.
    fn observe_change(&mut self, candidate: Candidate, current_speed_mps: f64) -> Option<f64> {
        let previous = self.previous_reserved.replace(candidate)?;
        let dt = milliseconds_to_seconds(candidate.last_ms - previous.last_ms);
        let slope = (candidate.position_m - previous.position_m) / dt;
        let threshold = (candidate.accuracy_m.hypot(previous.accuracy_m) / dt).max(MIN_CHANGE_MPS);
        if let Some(reference) = self.reference_speed_mps {
            let change = slope - reference;
            let departs = change.abs() > threshold;
            if departs && change * self.previous_change_departure > 0.0 {
                self.relearning = true;
            }
            self.previous_change_departure = if departs { change } else { 0.0 };
        }
        let prior = self.prior_speed_mps?;
        // A startup/standing prior must not manufacture a stop from coarse moving fixes.
        if prior <= MIN_MOVING_MPS {
            return None;
        }
        let departure = slope - current_speed_mps;
        let favours_prior = departure.abs() > threshold && (slope - prior).abs() < departure.abs();
        let confirmed = favours_prior && departure * self.previous_departure > 0.0;
        self.previous_departure = if favours_prior { departure } else { 0.0 };
        confirmed.then_some(prior)
    }

    fn fit(&self) -> Option<SpeedEstimate> {
        let mut regression = NetworkSpeedEstimator::default();
        let mut previous: Option<Candidate> = None;
        let mut minimum_slope = f64::INFINITY;
        let mut maximum_slope = f64::NEG_INFINITY;
        for sample in self.samples.iter().flatten() {
            regression.add(sample.position_m, sample.accuracy_m, sample.last_ms);
            if let Some(older) = previous {
                let dt = milliseconds_to_seconds(sample.last_ms - older.last_ms);
                let slope = (sample.position_m - older.position_m) / dt;
                minimum_slope = minimum_slope.min(slope);
                maximum_slope = maximum_slope.max(slope);
            }
            previous = Some(*sample);
        }
        let mut estimate = regression.strict_estimate(previous?.last_ms)?;
        estimate.sigma_mps = estimate.sigma_mps.max(MIN_SIGMA_MPS);
        if estimate.samples != self.count
            || !(MIN_SPAN_S..=MAX_SPAN_S).contains(&estimate.span_s)
            || estimate.sigma_mps > MAX_SIGMA_MPS
            || estimate.speed_mps > MAX_SPEED_MPS
            || maximum_slope - minimum_slope > (2.0 * estimate.sigma_mps).max(2.0)
        {
            return None;
        }
        Some(estimate)
    }
}

#[cfg(kani)]
mod kani_proofs;
