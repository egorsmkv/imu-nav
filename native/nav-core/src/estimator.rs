//! Stateful route estimator used by the Android JNI boundary.

use crate::route::{GeoPoint, Projection, RouteGeometry};
use crate::{Estimate, FilterError, RouteFilter};
use std::collections::VecDeque;
use std::sync::Arc;

mod motion;
mod state;
use motion::MotionControl;
pub use motion::MotionObservation;
mod network_position;
use network_position::NetworkEvidence;
pub use network_position::NetworkObservation;
mod turn;
pub use turn::TurnObservation;
use turn::TurnState;
mod walking;
pub use walking::WalkingObservation;

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
// A tick can append a GNSS anchor and its final prediction checkpoint before pruning history.
const TICK_HISTORY_HEADROOM: usize = 2;
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
#[cfg_attr(any(test, kani), derive(PartialEq))]
pub struct NavigationEstimator {
    state: FilterState,
    route: Arc<RouteGeometry>,
    mode: TravelMode,
    last_gps_ms: i64,
    last_vehicle_input_ms: i64,
    network_speed_enabled: bool,
    history: VecDeque<HistoryFrame>,
}

/// Everything that must be restored before applying a delayed GNSS observation.
#[derive(Clone, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
struct FilterState {
    filter: RouteFilter,
    elapsed_ms: i64,
    last_vehicle_speed_ms: i64,
    vehicle_speed_scale: f64,
    stable_vehicle_speed: Option<StableVehicleSpeed>,
    last_gps_speed_ms: i64,
    last_gps_position_ms: i64,
    motion_control: Option<MotionControl>,
    walking_valid_until_ms: Option<i64>,
    network_evidence: NetworkEvidence,
    turn_state: TurnState,
}

/// A continuous plateau of accepted raw OBD readings. Calibration during acceleration would
/// mistake sensor latency for wheel-speed scale error, so only stable plateaus qualify.
#[derive(Clone, Copy, Debug)]
#[cfg_attr(any(test, kani), derive(PartialEq))]
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
#[cfg_attr(any(test, kani), derive(PartialEq))]
struct HistoryFrame {
    state: FilterState,
    vehicle_speed_mps: Option<f64>,
    motion: Option<MotionObservation>,
    network: Option<NetworkObservation>,
    turn: Option<TurnObservation>,
    walking: Option<WalkingObservation>,
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
        let mut state = FilterState {
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
            last_gps_position_ms: -1,
            motion_control: None,
            walking_valid_until_ms: None,
            network_evidence: NetworkEvidence::new(now_ms),
            turn_state: TurnState::new(now_ms),
        };
        // Walking starts/restores stationary until recorded GPS, steps or IMU supply movement.
        if mode == TravelMode::Foot {
            state.filter.set_speed_prior(0.0, initial.speed_sigma_mps)?;
        }
        Ok(Self {
            state: state.clone(),
            route,
            mode,
            last_gps_ms: -1,
            last_vehicle_input_ms: -1,
            network_speed_enabled: false,
            history: VecDeque::from([HistoryFrame {
                state,
                vehicle_speed_mps: None,
                motion: None,
                network: None,
                turn: None,
                walking: None,
            }]),
        })
    }

    #[must_use]
    pub fn estimate(&self) -> Estimate {
        self.state.filter.estimate()
    }

    /// Enables the off-by-default cell-speed experiment for an A/B run. Changing policy discards
    /// old replay history and partial speed batches; configure before feeding observations.
    pub fn set_network_speed_enabled(&mut self, enabled: bool) {
        if self.network_speed_enabled == enabled {
            return;
        }
        self.network_speed_enabled = enabled;
        self.state.network_evidence.clear_speed();
        self.history.clear();
        self.remember(None);
    }

    /// Incorporates a vehicle-reported speed at its timestamp when operating in car mode.
    /// Duplicate or older-than-current-state samples are ignored. Only accepted updates refresh
    /// the OBD drift allowance; later delayed GNSS can cause this decision to be re-evaluated.
    ///
    /// # Errors
    ///
    /// Returns [`FilterError`] for an invalid prediction or measurement update, without changing
    /// the filter, calibration, history or timestamp watermarks.
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
        // Only fixed-size state is staged; history/watermarks are written after success.
        let mode = self.mode;
        let accepted = self.state.try_update(|pending| {
            pending.predict_to(elapsed_ms, mode)?;
            pending.apply_vehicle_speed(speed_kmh / 3.6)
        })?;
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
        self.tick_with_observations(now_ms, gps, motion, None)
    }

    /// Adds conservative coarse-position corrections; all inputs are retained for GNSS replay.
    ///
    /// # Errors
    /// Returns [`FilterError`] for invalid filter updates, without changing the estimator.
    pub fn tick_with_observations(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
        network: Option<NetworkObservation>,
    ) -> Result<TickOutcome, FilterError> {
        self.tick_with_turn(now_ms, gps, motion, network, None)
    }

    /// Includes a completed IMU rotation for conservative landmark matching.
    ///
    /// # Errors
    /// Returns [`FilterError`] for invalid filter updates, without changing the estimator.
    pub fn tick_with_turn(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
        network: Option<NetworkObservation>,
        turn: Option<TurnObservation>,
    ) -> Result<TickOutcome, FilterError> {
        self.tick_with_walking(now_ms, gps, motion, network, turn, None)
    }

    /// Adds an explicit pedestrian speed model; car hints never stand in for walking evidence.
    ///
    /// # Errors
    /// Returns [`FilterError`] for invalid filter updates, without changing the estimator.
    pub fn tick_with_walking(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
        network: Option<NetworkObservation>,
        turn: Option<TurnObservation>,
        walking: Option<WalkingObservation>,
    ) -> Result<TickOutcome, FilterError> {
        self.try_tick(|pending| pending.tick_inner(now_ms, gps, motion, network, turn, walking))
    }

    /// A pending tick owns its history; an error cannot publish any part of that update.
    fn try_tick<T>(
        &mut self,
        update: impl FnOnce(&mut Self) -> Result<T, FilterError>,
    ) -> Result<T, FilterError> {
        let mut pending = self.copy_for_tick();
        let outcome = update(&mut pending)?;
        *self = pending;
        Ok(outcome)
    }

    /// Preserve transactional rollback without cloning a full deque only to grow it immediately.
    /// `VecDeque::clone` copies the length, not spare capacity; reserving the two possible new
    /// checkpoints up front avoids repeated reallocations while keeping all mutations isolated.
    fn copy_for_tick(&self) -> Self {
        let mut history = VecDeque::with_capacity(self.history.len() + TICK_HISTORY_HEADROOM);
        history.extend(self.history.iter().cloned());
        Self {
            state: self.state.clone(),
            route: Arc::clone(&self.route),
            mode: self.mode,
            last_gps_ms: self.last_gps_ms,
            last_vehicle_input_ms: self.last_vehicle_input_ms,
            network_speed_enabled: self.network_speed_enabled,
            history,
        }
    }

    /// Rewinds only when the measurement still belongs to the current route and history.
    fn tick_inner(
        &mut self,
        now_ms: i64,
        gps: Option<GpsObservation>,
        motion: Option<MotionObservation>,
        network: Option<NetworkObservation>,
        turn: Option<TurnObservation>,
        walking: Option<WalkingObservation>,
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
                && self.history.front().is_some_and(|frame| {
                    observation.elapsed_ms > frame.state.elapsed_ms
                        || (observation.elapsed_ms == frame.state.elapsed_ms
                            && frame.vehicle_speed_mps.is_none()
                            && frame.motion.is_none()
                            && frame.turn.is_none()
                            && frame.walking.is_none()
                            && frame.network.is_none())
                })
        }) {
            self.last_gps_ms = observation.elapsed_ms;
            let original_state = self.state.clone();
            let mut later = Vec::new();
            // Replay same-time hints too. OBD must precede GNSS (plateau calibration), then
            // GNSS precedes coarse positions and motion hints. Keep the initial checkpoint.
            while self.history.len() > 1
                && self
                    .history
                    .back()
                    .is_some_and(|frame| frame.state.elapsed_ms >= observation.elapsed_ms)
            {
                if let Some(frame) = self.history.pop_back() {
                    later.push(frame);
                }
            }
            let preserved_frames = self.history.len();
            if let Some(frame) = self.history.back() {
                self.state = frame.state.clone();
            }
            self.state.predict_to(observation.elapsed_ms, self.mode)?;
            for frame in later
                .iter()
                .rev()
                .filter(|frame| frame.state.elapsed_ms == observation.elapsed_ms)
            {
                if let Some(speed) = frame.vehicle_speed_mps {
                    self.state.apply_vehicle_speed(speed)?;
                    self.remember(Some(speed));
                }
            }
            (projection, position_accepted, speed_accepted) = self.apply_gps(observation)?;
            if position_accepted || speed_accepted {
                self.remember(None);
                for frame in later.into_iter().rev() {
                    if frame.state.elapsed_ms == observation.elapsed_ms
                        && frame.vehicle_speed_mps.is_some()
                    {
                        continue;
                    }
                    self.state.predict_to(frame.state.elapsed_ms, self.mode)?;
                    if let Some(speed) = frame.vehicle_speed_mps {
                        self.state.apply_vehicle_speed(speed)?;
                    } else {
                        self.apply_network(frame.network, frame.motion)?;
                        self.apply_motion(frame.motion)?;
                        self.apply_turn(frame.turn)?;
                        self.apply_walking(frame.walking)?;
                    }
                    self.remember_observations(
                        frame.vehicle_speed_mps,
                        frame.motion,
                        frame.network,
                        frame.turn,
                        frame.walking,
                    );
                }
            } else {
                // A rejected observation must not change process-noise partitioning or cause
                // later OBD gates to be re-evaluated with a different covariance.
                self.state = original_state;
                // Only same-time OBD checkpoints were appended to the preserved prefix. They
                // replace removed frames at an earlier timestamp, so neither retention limit
                // can evict the prefix. Restore the owned suffix instead of cloning it upfront.
                self.history.truncate(preserved_frames);
                self.history.extend(later.into_iter().rev());
            }
        }
        self.state.predict_to(now_ms, self.mode)?;
        self.apply_network(network, motion)?;
        self.apply_motion(motion)?;
        self.apply_turn(turn)?;
        self.apply_walking(walking)?;
        self.remember_observations(None, motion, network, turn, walking);
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
            self.state.network_evidence.clear_speed();
            self.state.filter.reset_systematic_drift();
            self.state.last_gps_position_ms = observation.elapsed_ms;
        }
        let mut speed_accepted = false;
        if let Some(speed_mps) = observation.speed_mps {
            let speed_sigma = observation
                .speed_accuracy_mps
                .unwrap_or(DEFAULT_GPS_SPEED_SIGMA_MPS)
                .max(MIN_SPEED_SIGMA_MPS)
                * multiplier;
            speed_accepted = self.state.update_measured_speed(
                speed_mps,
                speed_sigma,
                observation.trust == ObservationTrust::Good,
            )?;
            if speed_accepted && observation.trust == ObservationTrust::Good {
                self.state.last_gps_speed_ms = observation.elapsed_ms;
                if self.mode == TravelMode::Foot {
                    self.state.walking_valid_until_ms = Some(
                        observation
                            .elapsed_ms
                            .saturating_add(walking::GPS_SPEED_MAX_AGE_MS),
                    );
                }
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

    /// Keeps bounded replay work and one checkpoint preceding the time window when available.
    fn remember(&mut self, vehicle_speed_mps: Option<f64>) {
        self.remember_observations(vehicle_speed_mps, None, None, None, None);
    }

    /// Retains model hints as events, not just their resulting speed, for delayed-GNSS replay.
    fn remember_observations(
        &mut self,
        vehicle_speed_mps: Option<f64>,
        motion: Option<MotionObservation>,
        network: Option<NetworkObservation>,
        turn: Option<TurnObservation>,
        walking: Option<WalkingObservation>,
    ) {
        self.history.push_back(HistoryFrame {
            state: self.state.clone(),
            vehicle_speed_mps,
            motion,
            network,
            turn,
            walking,
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
        self.state.network_evidence.reroute(self.state.elapsed_ms);
        self.state.turn_state = TurnState::new(self.state.elapsed_ms);
        self.history.clear();
        self.remember(None);
        Ok(())
    }
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod regression_tests;

#[cfg(kani)]
mod kani_proofs;
