//! Stateful route estimator used by the Android JNI boundary.

use crate::route::{GeoPoint, Projection, RouteGeometry};
use crate::{Estimate, FilterError, RouteFilter, milliseconds_to_seconds};
use std::sync::Arc;

const MIN_POSITION_SIGMA_M: f64 = 3.0;
const MIN_SPEED_SIGMA_MPS: f64 = 0.2;
const DEFAULT_GPS_POSITION_SIGMA_M: f64 = 20.0;
const DEFAULT_GPS_SPEED_SIGMA_MPS: f64 = 1.5;
const SUSPECT_SIGMA_MULTIPLIER: f64 = 2.0;
const OBD_SPEED_SIGMA_MPS: f64 = 0.6;
const POSITION_NIS_GATE: f64 = 25.0;
const SPEED_NIS_GATE: f64 = 25.0;
const CAR_ACCELERATION_SIGMA_MPS2: f64 = 2.0;
const WALK_ACCELERATION_SIGMA_MPS2: f64 = 1.0;
const ESTIMATED_SYSTEMATIC_DRIFT_PER_M: f64 = 0.08;
const OBD_SYSTEMATIC_DRIFT_PER_M: f64 = 0.02;
const MAX_DT_S: f64 = 5.0;
const MAX_VEHICLE_KMH: f64 = 250.0;
const OBD_MAX_AGE_MS: i64 = 2_500;
const GPS_SEARCH_BEHIND_M: f64 = 250.0;
const GPS_SEARCH_AHEAD_M: f64 = 2_500.0;
const GPS_GLOBAL_IF_FARTHER_M: f64 = 120.0;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TravelMode {
    Car,
    Foot,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ObservationTrust {
    Good,
    Suspect,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GpsObservation {
    pub point: GeoPoint,
    pub elapsed_ms: i64,
    pub position_accuracy_m: Option<f64>,
    pub speed_mps: Option<f64>,
    pub speed_accuracy_mps: Option<f64>,
    pub trust: ObservationTrust,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TickOutcome {
    pub estimate: Estimate,
    pub projection: Option<Projection>,
    pub position_accepted: bool,
    pub speed_accepted: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct InitialEstimate {
    pub position_m: f64,
    pub speed_mps: f64,
    pub position_sigma_m: f64,
    pub speed_sigma_mps: f64,
    pub systematic_drift_m: f64,
}

#[derive(Clone, Debug)]
pub struct NavigationEstimator {
    filter: RouteFilter,
    route: Arc<RouteGeometry>,
    mode: TravelMode,
    last_tick_ms: i64,
    last_gps_ms: i64,
    last_vehicle_speed_ms: i64,
}

impl NavigationEstimator {
    /// Creates an estimator bound to a route and travel mode.
    ///
    /// # Errors
    ///
    /// Returns [`FilterError`] when the initial estimate contains invalid values.
    pub fn new(
        route: Arc<RouteGeometry>,
        initial: InitialEstimate,
        mode: TravelMode,
        now_ms: i64,
    ) -> Result<Self, FilterError> {
        Ok(Self {
            filter: RouteFilter::new(
                initial.position_m,
                initial.speed_mps,
                initial.position_sigma_m.max(MIN_POSITION_SIGMA_M),
                initial.speed_sigma_mps,
                initial.systematic_drift_m,
            )?,
            route,
            mode,
            last_tick_ms: now_ms,
            last_gps_ms: -1,
            last_vehicle_speed_ms: -1,
        })
    }

    #[must_use]
    pub fn estimate(&self) -> Estimate {
        self.filter.estimate()
    }

    /// Incorporates a vehicle-reported speed when operating in car mode.
    ///
    /// # Errors
    ///
    /// Returns [`FilterError`] if the accepted measurement produces an invalid filter update.
    pub fn on_vehicle_speed(
        &mut self,
        speed_kmh: f64,
        elapsed_ms: i64,
    ) -> Result<bool, FilterError> {
        if self.mode != TravelMode::Car
            || !speed_kmh.is_finite()
            || !(0.0..=MAX_VEHICLE_KMH).contains(&speed_kmh)
        {
            return Ok(false);
        }
        let outcome =
            self.filter
                .update_speed(speed_kmh / 3.6, OBD_SPEED_SIGMA_MPS, SPEED_NIS_GATE)?;
        self.last_vehicle_speed_ms = elapsed_ms;
        Ok(outcome.accepted)
    }

    /// Advances the estimator and optionally incorporates a trusted GNSS observation.
    ///
    /// # Errors
    ///
    /// Returns [`FilterError`] when prediction, projection, or measurement update inputs are
    /// invalid.
    pub fn tick(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
    ) -> Result<TickOutcome, FilterError> {
        let duration_s =
            milliseconds_to_seconds(now_ms.saturating_sub(self.last_tick_ms)).clamp(0.0, MAX_DT_S);
        self.last_tick_ms = now_ms;
        let obd_age_ms = now_ms.saturating_sub(self.last_vehicle_speed_ms);
        let obd_fresh =
            self.last_vehicle_speed_ms >= 0 && (0..=OBD_MAX_AGE_MS).contains(&obd_age_ms);
        let drift_per_m = if obd_fresh {
            OBD_SYSTEMATIC_DRIFT_PER_M
        } else {
            ESTIMATED_SYSTEMATIC_DRIFT_PER_M
        };
        let acceleration_sigma = match self.mode {
            TravelMode::Car => CAR_ACCELERATION_SIGMA_MPS2,
            TravelMode::Foot => WALK_ACCELERATION_SIGMA_MPS2,
        };
        self.filter
            .predict(duration_s, acceleration_sigma, drift_per_m)?;

        let mut projection = None;
        let mut position_accepted = false;
        let mut speed_accepted = false;
        if let Some(observation) =
            gps.filter(|observation| observation.elapsed_ms > self.last_gps_ms)
        {
            self.last_gps_ms = observation.elapsed_ms;
            let projected = self
                .route
                .project(
                    observation.point,
                    self.filter.estimate().position_m,
                    GPS_SEARCH_BEHIND_M,
                    GPS_SEARCH_AHEAD_M,
                    GPS_GLOBAL_IF_FARTHER_M,
                )
                .map_err(|_| FilterError::NonFinite)?;
            let multiplier = match observation.trust {
                ObservationTrust::Good => 1.0,
                ObservationTrust::Suspect => SUSPECT_SIGMA_MULTIPLIER,
            };
            let position_sigma = observation
                .position_accuracy_m
                .unwrap_or(DEFAULT_GPS_POSITION_SIGMA_M)
                .max(MIN_POSITION_SIGMA_M)
                * multiplier;
            position_accepted = self
                .filter
                .update_position(projected.position_m, position_sigma, POSITION_NIS_GATE)?
                .accepted;
            if position_accepted && observation.trust == ObservationTrust::Good {
                self.filter.reset_systematic_drift();
            }
            if let Some(speed_mps) = observation.speed_mps {
                let speed_sigma = observation
                    .speed_accuracy_mps
                    .unwrap_or(DEFAULT_GPS_SPEED_SIGMA_MPS)
                    .max(MIN_SPEED_SIGMA_MPS)
                    * multiplier;
                speed_accepted = self
                    .filter
                    .update_speed(speed_mps, speed_sigma, SPEED_NIS_GATE)?
                    .accepted;
            }
            projection = Some(projected);
        }
        Ok(TickOutcome {
            estimate: self.filter.estimate(),
            projection,
            position_accepted,
            speed_accepted,
        })
    }

    /// Replaces the active route and anchors the filter at the corresponding route position.
    ///
    /// # Errors
    ///
    /// Returns [`FilterError`] when the anchor position or uncertainty is invalid.
    pub fn replace_route(
        &mut self,
        route: Arc<RouteGeometry>,
        position_m: f64,
        position_sigma_m: f64,
    ) -> Result<(), FilterError> {
        self.route = route;
        self.filter
            .anchor_position(position_m, position_sigma_m.max(MIN_POSITION_SIGMA_M))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn route() -> Arc<RouteGeometry> {
        Arc::new(
            RouteGeometry::new(vec![
                GeoPoint {
                    latitude_deg: 50.0,
                    longitude_deg: 30.0,
                },
                GeoPoint {
                    latitude_deg: 50.02,
                    longitude_deg: 30.0,
                },
            ])
            .unwrap(),
        )
    }

    #[test]
    fn trusted_gps_then_blind_prediction_updates_state_and_safety() {
        let initial = InitialEstimate {
            position_m: 0.0,
            speed_mps: 10.0,
            position_sigma_m: 10.0,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 0.0,
        };
        let mut estimator = NavigationEstimator::new(route(), initial, TravelMode::Car, 0).unwrap();
        let gps = GpsObservation {
            point: GeoPoint {
                latitude_deg: 50.0001,
                longitude_deg: 30.0,
            },
            elapsed_ms: 1_000,
            position_accuracy_m: Some(5.0),
            speed_mps: Some(10.0),
            speed_accuracy_mps: Some(0.5),
            trust: ObservationTrust::Good,
        };
        let anchored = estimator.tick(1_000, Some(gps)).unwrap();
        assert!(anchored.position_accepted);
        assert!(anchored.speed_accepted);
        assert!(anchored.estimate.systematic_drift_m.abs() < f64::EPSILON);
        let blind = estimator.tick(6_000, None).unwrap();
        assert!(blind.estimate.position_m > anchored.estimate.position_m + 45.0);
        assert!(blind.estimate.systematic_drift_m > 3.5);
    }

    #[test]
    fn obd_reduces_systematic_drift_rate() {
        let initial = InitialEstimate {
            position_m: 0.0,
            speed_mps: 10.0,
            position_sigma_m: 10.0,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 0.0,
        };
        let mut estimator = NavigationEstimator::new(route(), initial, TravelMode::Car, 0).unwrap();
        assert!(estimator.on_vehicle_speed(36.0, 1_000).unwrap());
        let estimate = estimator.tick(2_000, None).unwrap().estimate;
        assert!((estimate.systematic_drift_m - 0.4).abs() < 0.05);
    }
}
