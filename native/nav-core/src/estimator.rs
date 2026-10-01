//! Stateful route estimator used by the Android JNI boundary.

use crate::route::{GeoPoint, Projection, RouteGeometry};
use crate::{Estimate, FilterError, RouteFilter, milliseconds_to_seconds};
use std::collections::VecDeque;
use std::sync::Arc;

mod motion;
use motion::MotionControl;
pub use motion::MotionObservation;

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
const MAX_PREDICTION_MS: i64 = 5_000;
const GPS_HISTORY_MS: i64 = 5_000;
const MAX_HISTORY_FRAMES: usize = 128;
const MAX_VEHICLE_KMH: f64 = 250.0;
const OBD_MAX_AGE_MS: i64 = 2_500;
const GPS_SEARCH_BEHIND_M: f64 = 250.0;
const GPS_SEARCH_AHEAD_M: f64 = 2_500.0;
const GPS_GLOBAL_IF_FARTHER_M: f64 = 120.0;
// Match the live engine's normal SUSPECT gates. Recovery cannot relax them without an
// explicit recovery signal, which the comparison estimator does not currently receive.
const SUSPECT_MAX_OFFSET_M: f64 = 60.0;
const SUSPECT_MAX_JUMP_M: f64 = 300.0;
const SCALE_MIN_SPEED_MPS: f64 = 5.0;
const SCALE_MIN_RATIO: f64 = 0.8;
const SCALE_MAX_RATIO: f64 = 1.2;
const SCALE_BLEND: f64 = 0.05;
const SCALE_MAX_GPS_SIGMA_MPS: f64 = 0.8;
const SCALE_MAX_GPS_SIGMA_M: f64 = 20.0;
const SCALE_MAX_OFFSET_M: f64 = 25.0;
const SCALE_MAX_SAMPLE_AGE_MS: i64 = 250;
const SCALE_STABLE_MS: i64 = 3_000;
const SCALE_MAX_OBD_GAP_MS: i64 = 1_000;
const SCALE_STABLE_RANGE_MPS: f64 = 0.5;

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
    state: FilterState,
    route: Arc<RouteGeometry>,
    mode: TravelMode,
    last_gps_ms: i64,
    last_vehicle_input_ms: i64,
    history: VecDeque<HistoryFrame>,
}

/// Everything that must be restored before applying a delayed GNSS observation.
#[derive(Clone, Debug)]
struct FilterState {
    filter: RouteFilter,
    elapsed_ms: i64,
    last_vehicle_speed_ms: i64,
    vehicle_speed_scale: f64,
    stable_vehicle_speed: Option<StableVehicleSpeed>,
    last_gps_speed_ms: i64,
    motion_control: Option<MotionControl>,
}

/// A continuous plateau of accepted raw OBD readings. Calibration during acceleration would
/// mistake sensor latency for wheel-speed scale error, so only stable plateaus qualify.
#[derive(Clone, Copy, Debug)]
struct StableVehicleSpeed {
    since_ms: i64,
    latest_ms: i64,
    latest_mps: f64,
    min_mps: f64,
    max_mps: f64,
}

impl StableVehicleSpeed {
    fn new(elapsed_ms: i64, speed_mps: f64) -> Self {
        Self {
            since_ms: elapsed_ms,
            latest_ms: elapsed_ms,
            latest_mps: speed_mps,
            min_mps: speed_mps,
            max_mps: speed_mps,
        }
    }

    /// Restarts the plateau after a speed change or an OBD gap instead of fitting stale data.
    fn add(self, elapsed_ms: i64, speed_mps: f64) -> Self {
        let minimum = self.min_mps.min(speed_mps);
        let maximum = self.max_mps.max(speed_mps);
        if elapsed_ms.saturating_sub(self.latest_ms) > SCALE_MAX_OBD_GAP_MS
            || maximum - minimum > SCALE_STABLE_RANGE_MPS
        {
            return Self::new(elapsed_ms, speed_mps);
        }
        Self {
            latest_ms: elapsed_ms,
            latest_mps: speed_mps,
            min_mps: minimum,
            max_mps: maximum,
            ..self
        }
    }
}

/// Post-event checkpoint plus the OBD input needed to replay this event after a delayed fix.
/// GNSS timestamps are strictly increasing, so a replay never crosses a newer GNSS update.
#[derive(Clone, Debug)]
struct HistoryFrame {
    state: FilterState,
    vehicle_speed_mps: Option<f64>,
    motion: Option<MotionObservation>,
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
        let state = FilterState {
            filter: RouteFilter::new(
                initial.position_m,
                initial.speed_mps,
                initial.position_sigma_m.max(MIN_POSITION_SIGMA_M),
                initial.speed_sigma_mps,
                initial.systematic_drift_m,
            )?,
            elapsed_ms: now_ms,
            last_vehicle_speed_ms: -1,
            vehicle_speed_scale: 1.0,
            stable_vehicle_speed: None,
            last_gps_speed_ms: -1,
            motion_control: None,
        };
        Ok(Self {
            state: state.clone(),
            route,
            mode,
            last_gps_ms: -1,
            last_vehicle_input_ms: -1,
            history: VecDeque::from([HistoryFrame {
                state,
                vehicle_speed_mps: None,
                motion: None,
            }]),
        })
    }

    #[must_use]
    pub fn estimate(&self) -> Estimate {
        self.state.filter.estimate()
    }

    /// Incorporates a vehicle-reported speed at its timestamp when operating in car mode.
    /// Duplicate or older-than-current-state samples are ignored. Only accepted updates refresh
    /// the OBD drift allowance; later delayed GNSS can cause this decision to be re-evaluated.
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
            || elapsed_ms < self.state.elapsed_ms
            || elapsed_ms <= self.last_vehicle_input_ms
        {
            return Ok(false);
        }
        self.predict_to(elapsed_ms)?;
        let accepted = self.apply_vehicle_speed(speed_kmh / 3.6)?;
        self.last_vehicle_input_ms = elapsed_ms;
        self.remember(Some(speed_kmh / 3.6));
        Ok(accepted)
    }

    /// Applies GNSS at its measurement time and replays up to five seconds of later events.
    /// Future, duplicate, out-of-order and expired fixes are ignored. Failed updates leave the
    /// estimator unchanged, including its history and timestamp watermarks.
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
        self.tick_with_motion(now_ms, gps, None)
    }

    /// Advances with a fresh IMU-derived motion hint. Hints are model changes, not position
    /// anchors, and are replayed with later events when delayed GNSS arrives.
    ///
    /// # Errors
    /// Returns [`FilterError`] for invalid measurements or filter updates, without mutation.
    pub fn tick_with_motion(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
    ) -> Result<TickOutcome, FilterError> {
        let mut pending = self.clone();
        let outcome = pending.tick_inner(now_ms, gps, motion)?;
        *self = pending;
        Ok(outcome)
    }

    /// Rewinds only when the measurement still belongs to the current route and history.
    fn tick_inner(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
    ) -> Result<TickOutcome, FilterError> {
        let mut projection = None;
        let mut position_accepted = false;
        let mut speed_accepted = false;
        if now_ms < self.state.elapsed_ms {
            return Ok(TickOutcome {
                estimate: self.estimate(),
                projection,
                position_accepted,
                speed_accepted,
            });
        }
        if let Some(observation) = gps.filter(|observation| {
            observation.elapsed_ms > self.last_gps_ms
                && observation.elapsed_ms <= now_ms
                && now_ms.saturating_sub(observation.elapsed_ms) <= GPS_HISTORY_MS
                && self
                    .history
                    .front()
                    .is_some_and(|frame| observation.elapsed_ms >= frame.state.elapsed_ms)
        }) {
            self.last_gps_ms = observation.elapsed_ms;
            let original_state = self.state.clone();
            let original_history = self.history.clone();
            let mut later = Vec::new();
            while self
                .history
                .back()
                .is_some_and(|frame| frame.state.elapsed_ms > observation.elapsed_ms)
            {
                if let Some(frame) = self.history.pop_back() {
                    later.push(frame);
                }
            }
            if let Some(frame) = self.history.back() {
                self.state = frame.state.clone();
            }
            self.predict_to(observation.elapsed_ms)?;
            (projection, position_accepted, speed_accepted) = self.apply_gps(observation)?;
            if position_accepted || speed_accepted {
                self.remember(None);
                for frame in later.into_iter().rev() {
                    self.predict_to(frame.state.elapsed_ms)?;
                    if let Some(speed) = frame.vehicle_speed_mps {
                        self.apply_vehicle_speed(speed)?;
                    } else {
                        self.apply_motion(frame.motion)?;
                    }
                    self.remember_motion(frame.vehicle_speed_mps, frame.motion);
                }
            } else {
                // A rejected observation must not change process-noise partitioning or cause
                // later OBD gates to be re-evaluated with a different covariance.
                self.state = original_state;
                self.history = original_history;
            }
        }
        self.predict_to(now_ms)?;
        self.apply_motion(motion)?;
        self.remember_motion(None, motion);
        Ok(TickOutcome {
            estimate: self.estimate(),
            projection,
            position_accepted,
            speed_accepted,
        })
    }

    /// Checks route consistency independently of covariance before either GNSS update.
    fn apply_gps(
        &mut self,
        observation: GpsObservation,
    ) -> Result<(Option<Projection>, bool, bool), FilterError> {
        let projected = self
            .route
            .project(
                observation.point,
                self.estimate().position_m,
                GPS_SEARCH_BEHIND_M,
                GPS_SEARCH_AHEAD_M,
                GPS_GLOBAL_IF_FARTHER_M,
            )
            .map_err(|_| FilterError::NonFinite)?;
        if observation.trust == ObservationTrust::Suspect
            && (projected.offset_m >= SUSPECT_MAX_OFFSET_M
                || (projected.position_m - self.estimate().position_m).abs() >= SUSPECT_MAX_JUMP_M)
        {
            return Ok((Some(projected), false, false));
        }
        let multiplier = match observation.trust {
            ObservationTrust::Good => 1.0,
            ObservationTrust::Suspect => SUSPECT_SIGMA_MULTIPLIER,
        };
        let position_sigma = observation
            .position_accuracy_m
            .unwrap_or(DEFAULT_GPS_POSITION_SIGMA_M)
            .max(MIN_POSITION_SIGMA_M)
            * multiplier;
        let position_accepted = self
            .state
            .filter
            .update_position(projected.position_m, position_sigma, POSITION_NIS_GATE)?
            .accepted;
        if position_accepted && observation.trust == ObservationTrust::Good {
            self.state.filter.reset_systematic_drift();
        }
        let mut speed_accepted = false;
        if let Some(speed_mps) = observation.speed_mps {
            let speed_sigma = observation
                .speed_accuracy_mps
                .unwrap_or(DEFAULT_GPS_SPEED_SIGMA_MPS)
                .max(MIN_SPEED_SIGMA_MPS)
                * multiplier;
            speed_accepted = self.update_measured_speed(
                speed_mps,
                speed_sigma,
                observation.trust == ObservationTrust::Good,
            )?;
            if speed_accepted && observation.trust == ObservationTrust::Good {
                self.state.last_gps_speed_ms = observation.elapsed_ms;
            }
        }
        if position_accepted && speed_accepted && observation.trust == ObservationTrust::Good {
            self.learn_vehicle_speed_scale(observation, projected.offset_m);
        }
        Ok((Some(projected), position_accepted, speed_accepted))
    }

    /// Learns only from precise, accepted GOOD GNSS near the route and contemporaneous stable
    /// OBD. The scale lives in checkpoints, so delayed GNSS calibrates before later OBD is replayed.
    fn learn_vehicle_speed_scale(&mut self, observation: GpsObservation, offset_m: f64) {
        let Some(stable) = self.state.stable_vehicle_speed else {
            return;
        };
        let Some(gps_speed) = observation.speed_mps else {
            return;
        };
        let precise_speed = observation.speed_accuracy_mps.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_MPS).contains(&sigma)
        });
        let precise_position = observation.position_accuracy_m.is_some_and(|sigma| {
            sigma.is_finite() && (0.0..=SCALE_MAX_GPS_SIGMA_M).contains(&sigma)
        });
        if self.mode != TravelMode::Car
            || !precise_speed
            || !precise_position
            || offset_m >= SCALE_MAX_OFFSET_M
            || gps_speed < SCALE_MIN_SPEED_MPS
            || stable.latest_mps < SCALE_MIN_SPEED_MPS
            || stable.latest_ms.saturating_sub(stable.since_ms) < SCALE_STABLE_MS
            || !(0..=SCALE_MAX_SAMPLE_AGE_MS)
                .contains(&observation.elapsed_ms.saturating_sub(stable.latest_ms))
        {
            return;
        }
        let ratio = gps_speed / stable.latest_mps;
        if (SCALE_MIN_RATIO..=SCALE_MAX_RATIO).contains(&ratio) {
            self.state.vehicle_speed_scale +=
                SCALE_BLEND * (ratio - self.state.vehicle_speed_scale);
        }
    }

    /// A rejected speed must not start or extend the lower OBD drift allowance.
    fn apply_vehicle_speed(&mut self, speed_mps: f64) -> Result<bool, FilterError> {
        let accepted = self.update_measured_speed(
            speed_mps * self.state.vehicle_speed_scale,
            OBD_SPEED_SIGMA_MPS,
            true,
        )?;
        if accepted {
            self.state.last_vehicle_speed_ms = self.state.elapsed_ms;
            self.state.stable_vehicle_speed = Some(self.state.stable_vehicle_speed.map_or_else(
                || StableVehicleSpeed::new(self.state.elapsed_ms, speed_mps),
                |stable| stable.add(self.state.elapsed_ms, speed_mps),
            ));
        } else {
            self.state.stable_vehicle_speed = None;
        }
        Ok(accepted)
    }

    /// Predicts chronologically, splitting at OBD expiry so freshness cannot cover older travel.
    fn predict_to(&mut self, elapsed_ms: i64) -> Result<(), FilterError> {
        let acceleration_sigma = match self.mode {
            TravelMode::Car => CAR_ACCELERATION_SIGMA_MPS2,
            TravelMode::Foot => WALK_ACCELERATION_SIGMA_MPS2,
        };
        while self.state.elapsed_ms < elapsed_ms {
            if self
                .state
                .motion_control
                .is_some_and(|control| control.valid_until_ms <= self.state.elapsed_ms)
            {
                self.release_motion()?;
            }
            let expiry_ms = self
                .state
                .last_vehicle_speed_ms
                .saturating_add(OBD_MAX_AGE_MS);
            let obd_fresh =
                self.state.last_vehicle_speed_ms >= 0 && self.state.elapsed_ms < expiry_ms;
            let mut end_ms =
                elapsed_ms.min(self.state.elapsed_ms.saturating_add(MAX_PREDICTION_MS));
            if let Some(control) = self.state.motion_control {
                end_ms = end_ms.min(control.valid_until_ms);
            }
            if obd_fresh {
                end_ms = end_ms.min(expiry_ms);
            }
            self.state.filter.predict(
                milliseconds_to_seconds(end_ms.saturating_sub(self.state.elapsed_ms)),
                acceleration_sigma,
                if obd_fresh {
                    OBD_SYSTEMATIC_DRIFT_PER_M
                } else {
                    ESTIMATED_SYSTEMATIC_DRIFT_PER_M
                },
            )?;
            self.state.elapsed_ms = end_ms;
        }
        Ok(())
    }

    /// Keeps bounded replay work and one checkpoint preceding the time window when available.
    fn remember(&mut self, vehicle_speed_mps: Option<f64>) {
        self.remember_motion(vehicle_speed_mps, None);
    }

    /// Retains model hints as events, not just their resulting speed, for delayed-GNSS replay.
    fn remember_motion(
        &mut self,
        vehicle_speed_mps: Option<f64>,
        motion: Option<MotionObservation>,
    ) {
        self.history.push_back(HistoryFrame {
            state: self.state.clone(),
            vehicle_speed_mps,
            motion,
        });
        let cutoff_ms = self.state.elapsed_ms.saturating_sub(GPS_HISTORY_MS);
        while self.history.len() > MAX_HISTORY_FRAMES
            || self
                .history
                .get(1)
                .is_some_and(|frame| frame.state.elapsed_ms < cutoff_ms)
        {
            self.history.pop_front();
        }
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
        self.state
            .filter
            .anchor_position(position_m, position_sigma_m.max(MIN_POSITION_SIGMA_M))?;
        self.route = route;
        self.history.clear();
        self.remember(None);
        Ok(())
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
        // Travel before the first accepted OBD reading still uses the estimated-speed allowance.
        assert!((estimate.systematic_drift_m - 1.0).abs() < 0.05);
    }
}

#[cfg(test)]
mod regression_tests;
