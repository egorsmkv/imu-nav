//! Fixed-size state updates, isolated from the estimator's history and input watermarks.
use super::motion::RESUME_SPEED_SIGMA_MPS;
use super::walking::WALK_SPEED_SIGMA_MPS;
use super::{
    CAR_ACCELERATION_SIGMA_MPS2, ESTIMATED_SYSTEMATIC_DRIFT_PER_M, FilterError, FilterState,
    MAX_PREDICTION_MS, OBD_MAX_AGE_MS, OBD_SPEED_SIGMA_MPS, OBD_SYSTEMATIC_DRIFT_PER_M,
    SPEED_NIS_GATE, StableVehicleSpeed, TravelMode, WALK_ACCELERATION_SIGMA_MPS2,
};
use crate::milliseconds_to_seconds;

impl FilterState {
    /// Stage the numerical work separately so callers cannot retain a partial prediction on error.
    pub(super) fn try_update<T>(
        &mut self,
        update: impl FnOnce(&mut Self) -> Result<T, FilterError>,
    ) -> Result<T, FilterError> {
        let mut pending = self.clone();
        let outcome = update(&mut pending)?;
        *self = pending;
        Ok(outcome)
    }

    /// Predicts chronologically, splitting at OBD expiry so freshness cannot cover older travel.
    pub(super) fn predict_to(
        &mut self,
        elapsed_ms: i64,
        mode: TravelMode,
    ) -> Result<(), FilterError> {
        let acceleration_sigma = match mode {
            TravelMode::Car => CAR_ACCELERATION_SIGMA_MPS2,
            TravelMode::Foot => WALK_ACCELERATION_SIGMA_MPS2,
        };
        while self.elapsed_ms < elapsed_ms {
            self.expire_walking(mode)?;
            if self
                .motion_control
                .is_some_and(|control| control.valid_until_ms <= self.elapsed_ms)
            {
                self.release_motion()?;
            }
            let expiry_ms = self.last_vehicle_speed_ms.saturating_add(OBD_MAX_AGE_MS);
            let obd_fresh = self.last_vehicle_speed_ms >= 0 && self.elapsed_ms < expiry_ms;
            let mut end_ms = elapsed_ms.min(self.elapsed_ms.saturating_add(MAX_PREDICTION_MS));
            if let Some(control) = self.motion_control {
                end_ms = end_ms.min(control.valid_until_ms);
            }
            if let Some(expiry_ms) = self.walking_valid_until_ms {
                end_ms = end_ms.min(expiry_ms);
            }
            if obd_fresh {
                end_ms = end_ms.min(expiry_ms);
            }
            self.filter.predict(
                milliseconds_to_seconds(end_ms.saturating_sub(self.elapsed_ms)),
                acceleration_sigma,
                if obd_fresh {
                    OBD_SYSTEMATIC_DRIFT_PER_M
                } else {
                    ESTIMATED_SYSTEMATIC_DRIFT_PER_M
                },
            )?;
            self.elapsed_ms = end_ms;
        }
        self.expire_walking(mode)?;
        Ok(())
    }

    /// A rejected speed must not start or extend the lower OBD drift allowance.
    pub(super) fn apply_vehicle_speed(&mut self, speed_mps: f64) -> Result<bool, FilterError> {
        let accepted = self.update_measured_speed(
            speed_mps * self.vehicle_speed_scale,
            OBD_SPEED_SIGMA_MPS,
            true,
        )?;
        if accepted {
            self.last_vehicle_speed_ms = self.elapsed_ms;
            self.stable_vehicle_speed = Some(self.stable_vehicle_speed.map_or_else(
                || StableVehicleSpeed::new(self.elapsed_ms, speed_mps),
                |stable| stable.add(self.elapsed_ms, speed_mps),
            ));
        } else {
            self.stable_vehicle_speed = None;
        }
        Ok(accepted)
    }

    /// GPS/OBD observations can overturn an IMU speed prior without being rejected solely
    /// because the heuristic stop had reduced speed uncertainty. If the stopped/ramping model
    /// rejects a trusted observation, also test the saved cruising model. This handles false
    /// highway stops without accepting arbitrary speed jumps or relaxing SUSPECT GPS gates.
    /// During motion control neither speed hypothesis changes position; rejecting both leaves
    /// the state untouched. Ordinary speed updates retain their position/speed correlation.
    pub(super) fn update_measured_speed(
        &mut self,
        speed_mps: f64,
        sigma_mps: f64,
        allow_cruise_recovery: bool,
    ) -> Result<bool, FilterError> {
        let mut candidate = self.filter.clone();
        if self.motion_control.is_some() {
            let speed = self.filter.estimate().speed_mps;
            candidate.set_speed_prior(
                speed,
                self.filter
                    .estimate()
                    .speed_sigma_mps()
                    .max(RESUME_SPEED_SIGMA_MPS),
            )?;
        }
        let mut accepted = candidate
            .update_speed(speed_mps, sigma_mps, SPEED_NIS_GATE)?
            .accepted;
        if !accepted
            && allow_cruise_recovery
            && let Some(control) = self.motion_control
        {
            candidate.set_speed_prior(control.cruise_speed_mps, RESUME_SPEED_SIGMA_MPS)?;
            accepted = candidate
                .update_speed(speed_mps, sigma_mps, SPEED_NIS_GATE)?
                .accepted;
        }
        if accepted {
            self.filter = candidate;
            self.motion_control = None;
            self.network_evidence.clear_speed();
        }
        Ok(accepted)
    }

    /// Reverts stale/vetoed hints to a deliberately uncertain cruising prior, never a new anchor.
    pub(super) fn release_motion(&mut self) -> Result<(), FilterError> {
        if let Some(control) = self.motion_control.take() {
            self.filter
                .set_speed_prior(control.cruise_speed_mps, RESUME_SPEED_SIGMA_MPS)?;
        }
        Ok(())
    }

    /// Split prediction at evidence expiry, including a long app pause, then stop rather than coast forever.
    pub(super) fn expire_walking(&mut self, mode: TravelMode) -> Result<(), FilterError> {
        if mode == TravelMode::Foot
            && self
                .walking_valid_until_ms
                .is_some_and(|expiry| self.elapsed_ms >= expiry)
        {
            self.filter.set_speed_prior(0.0, WALK_SPEED_SIGMA_MPS)?;
            self.walking_valid_until_ms = None;
        }
        Ok(())
    }
}
