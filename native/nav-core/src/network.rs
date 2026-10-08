//! Physical gate for route-projected network and cell positioning.

use crate::milliseconds_to_seconds;
use crate::speed::{NetworkSpeedEstimator, SpeedEstimate};

const MAX_SPEED_MPS: f64 = 150.0 / 3.6;
const COARSE_ACCURACY_M: f64 = 300.0;
const REANCHOR_MIN_SPAN_S: f64 = 12.0;
const MAX_BACKWARDS_MPS: f64 = -6.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NetworkSample {
    pub elapsed_ms: i64,
    pub position_m: f64,
    pub accuracy_m: f64,
    pub offset_m: f64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GateResult {
    Accepted,
    Reanchored,
    Rejected,
}

#[derive(Clone, Copy, Debug)]
struct Anchor {
    time_s: f64,
    position_m: f64,
    accuracy_m: f64,
}

#[derive(Clone, Debug, Default)]
pub struct NetworkTracker {
    anchor: Option<Anchor>,
    candidates: Vec<Anchor>,
    recent: Vec<NetworkSample>,
    history: Vec<NetworkSample>,
    speed: NetworkSpeedEstimator,
    last_latitude_deg: Option<f64>,
    last_longitude_deg: Option<f64>,
}

impl NetworkTracker {
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    pub fn clear_samples(&mut self) {
        self.recent.clear();
        self.history.clear();
        self.speed.clear();
    }

    /// Ignore duplicate/out-of-order evidence before it can rewind an anchor or candidate chain.
    pub fn gate(&mut self, elapsed_ms: i64, position_m: f64, accuracy_m: f64) -> GateResult {
        if elapsed_ms < 0 || !position_m.is_finite() || !accuracy_m.is_finite() || accuracy_m < 0.0
        {
            return GateResult::Rejected;
        }
        let fix = Anchor {
            time_s: milliseconds_to_seconds(elapsed_ms),
            position_m,
            accuracy_m,
        };
        let Some(current) = self.anchor else {
            if accuracy_m <= COARSE_ACCURACY_M {
                self.anchor = Some(fix);
                return GateResult::Accepted;
            }
            return GateResult::Rejected;
        };
        if fix.time_s <= current.time_s
            || self
                .candidates
                .last()
                .is_some_and(|candidate| fix.time_s <= candidate.time_s)
        {
            return GateResult::Rejected;
        }
        if accuracy_m > COARSE_ACCURACY_M {
            return if reachable(current, fix) {
                GateResult::Accepted
            } else {
                GateResult::Rejected
            };
        }
        if self
            .candidates
            .last()
            .is_some_and(|candidate| reachable(*candidate, fix))
        {
            self.candidates.push(fix);
            let span_s = match (self.candidates.first(), self.candidates.last()) {
                (Some(first), Some(last)) => last.time_s - first.time_s,
                _ => return GateResult::Rejected,
            };
            if self.candidates.len() >= 2
                && span_s >= REANCHOR_MIN_SPAN_S
                && slope(&self.candidates) >= MAX_BACKWARDS_MPS
            {
                self.anchor = Some(fix);
                self.candidates.clear();
                tracing::debug!("network anchor reestablished");
                return GateResult::Reanchored;
            }
            return GateResult::Rejected;
        }
        if reachable(current, fix) {
            self.anchor = Some(fix);
            self.candidates.clear();
            return GateResult::Accepted;
        }
        self.candidates.clear();
        self.candidates.push(fix);
        GateResult::Rejected
    }

    pub fn record(&mut self, sample: NetworkSample, latitude_deg: f64, longitude_deg: f64) {
        if !sample.position_m.is_finite()
            || !sample.accuracy_m.is_finite()
            || !sample.offset_m.is_finite()
            || !latitude_deg.is_finite()
            || !longitude_deg.is_finite()
        {
            return;
        }
        self.recent.push(sample);
        if self.recent.len() > 4 {
            self.recent.remove(0);
        }
        let duplicate = self.last_latitude_deg == Some(latitude_deg)
            && self.last_longitude_deg == Some(longitude_deg);
        self.last_latitude_deg = Some(latitude_deg);
        self.last_longitude_deg = Some(longitude_deg);
        if duplicate {
            return;
        }
        if sample.accuracy_m <= 120.0 {
            self.speed
                .add(sample.position_m, sample.accuracy_m, sample.elapsed_ms);
        }
        self.history.push(sample);
    }

    pub fn prune_history(&mut self, now_ms: i64) {
        self.history
            .retain(|sample| now_ms.saturating_sub(sample.elapsed_ms) <= 30_000);
    }

    #[must_use]
    pub fn last_two_consistent(&self) -> bool {
        let Some([older, newer]) = self.recent.last_chunk::<2>() else {
            return false;
        };
        let duration_s = milliseconds_to_seconds(newer.elapsed_ms.saturating_sub(older.elapsed_ms));
        duration_s > 0.0
            && (newer.position_m - older.position_m).abs()
                <= duration_s * MAX_SPEED_MPS + older.accuracy_m + newer.accuracy_m
    }

    #[must_use]
    pub fn recent(&self) -> &[NetworkSample] {
        &self.recent
    }

    #[must_use]
    pub fn history(&self) -> &[NetworkSample] {
        &self.history
    }

    #[must_use]
    pub fn speed_estimate(&self, now_ms: i64) -> Option<SpeedEstimate> {
        self.speed.estimate(now_ms)
    }

    #[must_use]
    pub fn strict_speed_estimate(&self, now_ms: i64) -> Option<SpeedEstimate> {
        self.speed.strict_estimate(now_ms)
    }
}

fn reachable(from: Anchor, to: Anchor) -> bool {
    (to.position_m - from.position_m).abs()
        <= (to.time_s - from.time_s).max(0.0) * MAX_SPEED_MPS + from.accuracy_m + to.accuracy_m
}

fn slope(points: &[Anchor]) -> f64 {
    let Ok(count) = u32::try_from(points.len()) else {
        return 0.0;
    };
    let count = f64::from(count);
    let mean_time = points.iter().map(|point| point.time_s).sum::<f64>() / count;
    let mean_position = points.iter().map(|point| point.position_m).sum::<f64>() / count;
    let time_variance = points
        .iter()
        .map(|point| {
            // An explicit square has precise multiplication semantics for the verifier.
            let centered_time = point.time_s - mean_time;
            centered_time * centered_time
        })
        .sum::<f64>();
    if time_variance < 1.0e-9 {
        return 0.0;
    }
    points
        .iter()
        .map(|point| (point.time_s - mean_time) * (point.position_m - mean_position))
        .sum::<f64>()
        / time_variance
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
mod kani_proofs;
