//! Pedestrian speed is a cadence/stride model, not an independent position observation.
//! Repeated ticks must not shrink position uncertainty or prolong stale movement.

use super::{FilterError, NavigationEstimator, TravelMode};

pub(super) const GPS_SPEED_MAX_AGE_MS: i64 = 2_500;
const MAX_HINT_LIFETIME_MS: i64 = 2_500;
const MAX_WALK_SPEED_MPS: f64 = 4.0;
pub(super) const WALK_SPEED_SIGMA_MPS: f64 = 1.5;

/// Explicit walking evidence from recorded steps/IMU. A car motion hint cannot create this input.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WalkingObservation {
    pub speed_mps: f64,
    pub valid_until_ms: i64,
}

impl NavigationEstimator {
    /// Fresh GOOD-GPS speed wins. Otherwise use a bounded pedestrian prior or hold without evidence.
    pub(super) fn apply_walking(
        &mut self,
        observation: Option<WalkingObservation>,
    ) -> Result<(), FilterError> {
        if self.mode != TravelMode::Foot {
            return Ok(());
        }
        let now_ms = self.state.elapsed_ms;
        let gps_expiry_ms = self
            .state
            .last_gps_speed_ms
            .saturating_add(GPS_SPEED_MAX_AGE_MS);
        if self.state.last_gps_speed_ms >= 0 && now_ms < gps_expiry_ms {
            self.state.walking_valid_until_ms = Some(gps_expiry_ms);
            return Ok(());
        }
        let hint = observation.filter(|hint| {
            hint.speed_mps.is_finite()
                && (0.0..=MAX_WALK_SPEED_MPS).contains(&hint.speed_mps)
                && (1..=MAX_HINT_LIFETIME_MS).contains(&hint.valid_until_ms.saturating_sub(now_ms))
        });
        self.state.filter.set_speed_prior(
            hint.map_or(0.0, |hint| hint.speed_mps),
            WALK_SPEED_SIGMA_MPS,
        )?;
        self.state.walking_valid_until_ms = hint.map(|hint| hint.valid_until_ms);
        Ok(())
    }
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
mod kani_proofs;
