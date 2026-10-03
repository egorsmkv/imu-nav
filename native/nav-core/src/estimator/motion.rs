//! Conservative stop/resume model changes. No position anchor or independent speed sensor is
//! invented from vibration; measurement updates and their statistical gates remain separate.

use super::{FilterError, NavigationEstimator, OBD_MAX_AGE_MS, TravelMode};

const MOTION_MAX_AGE_MS: i64 = 2_000;
const GPS_SPEED_MAX_AGE_MS: i64 = 2_500;
const STOP_SPEED_SIGMA_MPS: f64 = 2.0;
pub(super) const RESUME_SPEED_SIGMA_MPS: f64 = 6.0;
const MIN_CRUISE_SPEED_MPS: f64 = 0.5;
const MAX_CRUISE_SPEED_MPS: f64 = 150.0 / 3.6;

/// Fresh evidence from the shared Kotlin motion detector and gated network-speed history.
/// `factor` is zero at a confirmed stop, ramps after resuming, and is one while cruising.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MotionObservation {
    pub factor: f64,
    pub cruise_speed_mps: f64,
    pub valid_until_ms: i64,
    pub network_moving: bool,
}

/// Preserves a cruising-speed prior so a zero-speed model cannot get stuck after a stop.
#[derive(Clone, Copy, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
pub(super) struct MotionControl {
    pub cruise_speed_mps: f64,
    pub valid_until_ms: i64,
}

impl NavigationEstimator {
    /// Only car dead reckoning may use stop/resume hints. A fresh measured speed or a positive
    /// network-speed lower bound has priority over a quiet IMU. Missing hints cannot latch a stop.
    pub(super) fn apply_motion(
        &mut self,
        observation: Option<MotionObservation>,
    ) -> Result<(), FilterError> {
        let now_ms = self.state.elapsed_ms;
        let Some(observation) = observation.filter(|hint| {
            hint.factor.is_finite()
                && (0.0..=1.0).contains(&hint.factor)
                && hint.cruise_speed_mps.is_finite()
                && (0.0..=MAX_CRUISE_SPEED_MPS).contains(&hint.cruise_speed_mps)
                && (1..=MOTION_MAX_AGE_MS).contains(&hint.valid_until_ms.saturating_sub(now_ms))
        }) else {
            return self.state.release_motion();
        };
        if self.mode != TravelMode::Car || observation.network_moving {
            return self.state.release_motion();
        }
        let fresh_gps = self.state.last_gps_speed_ms >= 0
            && (0..GPS_SPEED_MAX_AGE_MS)
                .contains(&now_ms.saturating_sub(self.state.last_gps_speed_ms));
        let fresh_obd = self.state.last_vehicle_speed_ms >= 0
            && (0..OBD_MAX_AGE_MS)
                .contains(&now_ms.saturating_sub(self.state.last_vehicle_speed_ms));
        if fresh_gps || fresh_obd {
            return Ok(());
        }
        if observation.factor >= 1.0 && self.state.motion_control.is_none() {
            return Ok(());
        }
        let cruise_speed_mps = self.state.motion_control.map_or_else(
            || {
                if self.estimate().speed_mps >= MIN_CRUISE_SPEED_MPS {
                    self.estimate().speed_mps.min(MAX_CRUISE_SPEED_MPS)
                } else {
                    observation.cruise_speed_mps
                }
            },
            |control| control.cruise_speed_mps,
        );
        self.state.filter.set_speed_prior(
            cruise_speed_mps * observation.factor,
            if observation.factor == 0.0 {
                STOP_SPEED_SIGMA_MPS
            } else {
                RESUME_SPEED_SIGMA_MPS
            },
        )?;
        self.state.motion_control = (observation.factor < 1.0).then_some(MotionControl {
            cruise_speed_mps,
            valid_until_ms: observation.valid_until_ms,
        });
        Ok(())
    }
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
mod kani_proofs;
