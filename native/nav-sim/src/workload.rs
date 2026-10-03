//! Deterministic synthetic app inputs. Fixtures are generated outside measured regions.

use std::path::Path;
use std::sync::Arc;

use anyhow::{Result, anyhow, ensure};
use clap::ValueEnum;
use imu_nav_core::estimator::{
    GpsObservation, InitialEstimate, MotionObservation, NavigationEstimator, NetworkObservation,
    ObservationTrust, TickOutcome, TravelMode, WalkingObservation,
};
use imu_nav_core::network::{GateResult, NetworkSample, NetworkTracker};
use imu_nav_core::route::{GeoPoint, RouteGeometry};
use imu_nav_core::speed::fuse_speed;
use imu_nav_core::trust::{
    JamDetector, LocationFix, ReceiverHealth, TrustClassifier, TrustConfig, TrustInput, TrustLevel,
};
use serde::{Deserialize, Serialize};

use crate::cli::{Config, Pass};
use crate::measurement::{self, Allocations, Region};

pub const TICK_MS: i64 = 500;
const METRES_PER_DEGREE: f64 = 111_194.926_644_558_74;
const WALL_ORIGIN_MS: i64 = 1_700_000_000_000;

/// Each case emphasizes a native path; All expands to separate runs, not simultaneous navigation.
#[derive(Clone, Copy, Debug, Deserialize, Serialize, ValueEnum, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Scenario {
    All,
    Driving,
    Jam,
    Delayed,
    Stop,
    Reroute,
    Walking,
    Lifecycle,
}

impl Scenario {
    pub fn cases(self) -> Vec<Self> {
        if self == Self::All {
            vec![
                Self::Driving,
                Self::Jam,
                Self::Delayed,
                Self::Stop,
                Self::Reroute,
                Self::Walking,
                Self::Lifecycle,
            ]
        } else {
            vec![self]
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Driving => "driving",
            Self::Jam => "jam",
            Self::Delayed => "delayed",
            Self::Stop => "stop",
            Self::Reroute => "reroute",
            Self::Walking => "walking",
            Self::Lifecycle => "lifecycle",
        }
    }
}

/// Raw input at one delivery time; observed GPS timestamps may precede delivery by two seconds.
#[derive(Clone, Copy, Debug)]
struct Input {
    elapsed_ms: i64,
    truth_m: f64,
    speed_mps: f64,
    gps: Option<LocationFix>,
    jammed: bool,
    network: Option<NetworkObservation>,
}

/// Immutable inputs and raw polyline ownership are separate from the measured native session.
pub struct Fixture {
    points: Vec<GeoPoint>,
    replacement: Vec<GeoPoint>,
    inputs: Vec<Input>,
}

impl Fixture {
    pub fn new(config: &Config, scenario: Scenario) -> Self {
        let ticks = config.duration_s * 2;
        let route_length = f64::from(config.duration_s) * 25.0 + 1000.0;
        let points: Vec<_> = (0..config.route_points)
            .map(|index| {
                point(route_length * f64::from(index) / f64::from(config.route_points - 1))
            })
            .collect();
        let replacement = points
            .iter()
            .enumerate()
            .map(|(index, point)| GeoPoint {
                longitude_deg: point.longitude_deg
                    + (f64::from(u32::try_from(index).expect("route length is u32"))
                        / f64::from(config.route_points)
                        * std::f64::consts::PI)
                        .sin()
                        * 0.000_05,
                ..*point
            })
            .collect();
        let mut random = config.seed;
        let mut position = 0.0;
        let mut inputs: Vec<Input> = Vec::with_capacity(ticks as usize);
        for index in 0..ticks {
            let elapsed_ms = (i64::from(index) + 1) * TICK_MS;
            let middle = index >= ticks / 3 && index < ticks * 2 / 3;
            let speed = if scenario == Scenario::Walking {
                1.4
            } else if scenario == Scenario::Stop && index >= ticks / 3 && index < ticks / 2 {
                0.0
            } else {
                15.0
            };
            position += speed * 0.5;
            // Reproducible noise without a platform-dependent random library or integer overflow panic.
            random = random
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1);
            let noise = f64::from((random >> 32) as u32) / f64::from(u32::MAX) * 2.0 - 1.0;
            let jammed = scenario == Scenario::Jam && middle;
            let gps_hidden = matches!(scenario, Scenario::Stop | Scenario::Walking) && middle;
            let gps = if elapsed_ms % 1000 == 0 && !gps_hidden {
                let (observation_ms, observation_m, observation_speed) =
                    if scenario == Scenario::Delayed && index >= 4 {
                        let old = inputs[index as usize - 4];
                        (old.elapsed_ms, old.truth_m, old.speed_mps)
                    } else {
                        (elapsed_ms, position, speed)
                    };
                Some(location(
                    observation_ms,
                    observation_m + noise * 2.0,
                    observation_speed,
                ))
            } else {
                None
            };
            let network = (elapsed_ms % 5000 == 0).then_some(NetworkObservation {
                point: point(position + noise * 20.0),
                elapsed_ms,
                accuracy_m: 40.0,
            });
            inputs.push(Input {
                elapsed_ms,
                truth_m: position,
                speed_mps: speed,
                gps,
                jammed,
                network,
            });
        }
        Self {
            points,
            replacement,
            inputs,
        }
    }
}

fn point(position_m: f64) -> GeoPoint {
    GeoPoint {
        latitude_deg: 50.0 + position_m / METRES_PER_DEGREE,
        longitude_deg: 30.0,
    }
}

fn location(elapsed_ms: i64, position_m: f64, speed_mps: f64) -> LocationFix {
    let point = point(position_m);
    LocationFix {
        wall_time_ms: WALL_ORIGIN_MS + elapsed_ms,
        elapsed_ms,
        latitude_deg: point.latitude_deg,
        longitude_deg: point.longitude_deg,
        altitude_m: Some(150.0),
        speed_mps: Some(speed_mps),
        bearing_deg: Some(0.0),
        horizontal_accuracy_m: Some(5.0),
        vertical_accuracy_m: Some(8.0),
        is_mock: false,
    }
}

/// Small streaming digest and coverage counters; the harness never accumulates a navigation trace.
#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
pub struct Outcome {
    pub fingerprint: u64,
    pub ticks: u64,
    pub gps_good: u64,
    pub gps_bad: u64,
    pub gps_suspect: u64,
    pub gps_accepted: u64,
    pub delayed_accepted: u64,
    pub obd_accepted: u64,
    pub network_accepted: u64,
    pub jam_transitions: u64,
    pub motion_hints: u64,
    pub stopped_ticks: u64,
    pub resumed_ticks: u64,
    pub blind_walking_ticks: u64,
    pub gps_recovered: u64,
    pub walking_hints: u64,
    pub reroutes: u64,
    pub sessions: u64,
    pub max_network_history: usize,
    pub rms_error_m: f64,
    pub max_error_m: f64,
}

impl Outcome {
    fn hash(&mut self, value: u64) {
        self.fingerprint = self.fingerprint.wrapping_mul(0x0000_0100_0000_01b3) ^ value;
    }

    pub fn validate(&self, scenario: Scenario) -> Result<()> {
        ensure!(
            self.gps_good > 0 && self.gps_accepted > 0 && self.network_accepted > 0,
            "missing normal navigation coverage: {scenario:?}"
        );
        ensure!(
            self.max_network_history <= 7,
            "network history was not pruned"
        );
        match scenario {
            Scenario::Jam => ensure!(
                self.gps_bad > 0 && self.jam_transitions >= 2 && self.gps_recovered > 0,
                "jamming/recovery not exercised"
            ),
            Scenario::Delayed => ensure!(
                self.delayed_accepted > 0 && self.obd_accepted > 0,
                "delayed OBD/GPS not exercised"
            ),
            Scenario::Stop => ensure!(
                self.motion_hints > 0 && self.stopped_ticks > 0 && self.resumed_ticks > 0,
                "stop hints not exercised"
            ),
            Scenario::Reroute => ensure!(self.reroutes > 0, "reroute not exercised"),
            Scenario::Walking => ensure!(
                self.walking_hints > 0 && self.blind_walking_ticks > 0 && self.obd_accepted == 0,
                "walking isolation failed"
            ),
            Scenario::Lifecycle => ensure!(self.sessions == 8, "session lifecycle not exercised"),
            _ => {}
        }
        Ok(())
    }
}

/// Phase durations exclude sampling/report output; timing comparisons use only the timing pass.
#[derive(Debug, Deserialize, Serialize)]
pub struct Phase {
    pub name: String,
    pub elapsed_ms: f64,
    pub allocations: Allocations,
    pub allocator_live_bytes: usize,
}

/// One fresh simulated drive; repeated lifecycle cases include eight independent sessions.
#[derive(Debug, Deserialize, Serialize)]
pub struct Run {
    pub scenario: Scenario,
    pub outcome: Outcome,
    pub phases: Vec<Phase>,
}

/// Executes fixtures with the same public calls and ordering as the app's native input boundary.
pub fn run(
    fixture: &Fixture,
    scenario: Scenario,
    pass: Pass,
    output: Option<&Path>,
) -> Result<Run> {
    let mut outcome = Outcome::default();
    let mut phases = Vec::with_capacity(64);
    let session_count = if scenario == Scenario::Lifecycle {
        8
    } else {
        1
    };
    for session in 0..session_count {
        // JNI array conversion is outside scope: raw route buffers are prepared before measuring.
        let points = fixture.points.clone();
        let replacement = fixture.replacement.clone();
        let region = Region::start(pass == Pass::Alloc);
        let route =
            Arc::new(RouteGeometry::new(points).map_err(|error| anyhow!("route: {error:?}"))?);
        let route_lifetime = Arc::downgrade(&route);
        let mode = if scenario == Scenario::Walking {
            TravelMode::Foot
        } else {
            TravelMode::Car
        };
        let mut estimator = NavigationEstimator::new(
            Arc::clone(&route),
            InitialEstimate {
                position_m: 0.0,
                speed_mps: if mode == TravelMode::Foot { 0.0 } else { 15.0 },
                position_sigma_m: 10.0,
                speed_sigma_mps: 2.0,
                systematic_drift_m: 0.0,
            },
            mode,
            0,
        )
        .map_err(|error| anyhow!("estimator: {error:?}"))?;
        let mut classifier = TrustClassifier::new(TrustConfig::default());
        let mut jammer = JamDetector::default();
        let mut network = NetworkTracker::default();
        finish_phase(region, "route", session, &mut phases, output)?;
        drop(route); // Only the estimator owns geometry, as after the app installs a route handle.
        let mut replacement = Some(replacement);
        let quarter = fixture.inputs.len() / 4;
        let mut region = Region::start(pass == Pass::Alloc);
        for (index, input) in fixture.inputs.iter().enumerate() {
            if scenario == Scenario::Reroute && index == fixture.inputs.len() / 2 {
                let points = replacement
                    .take()
                    .ok_or_else(|| anyhow!("duplicate reroute"))?;
                estimator
                    .replace_route(
                        Arc::new(
                            RouteGeometry::new(points)
                                .map_err(|error| anyhow!("reroute: {error:?}"))?,
                        ),
                        input.truth_m,
                        10.0,
                    )
                    .map_err(|error| anyhow!("replace: {error:?}"))?;
                outcome.reroutes += 1;
            }
            deliver(
                input,
                scenario,
                &mut estimator,
                &mut classifier,
                &mut jammer,
                &mut network,
                &mut outcome,
            )?;
            if (index + 1) % quarter == 0 || index + 1 == fixture.inputs.len() {
                let name = match (index + 1) / quarter {
                    1 => "steady",
                    2 => "middle",
                    3 => "recovery",
                    _ => "late",
                };
                finish_phase(region, name, session, &mut phases, output)?;
                region = Region::start(pass == Pass::Alloc);
            }
        }
        drop(region);
        let region = Region::start(pass == Pass::Alloc);
        drop(estimator);
        ensure!(
            route_lifetime.upgrade().is_none(),
            "route retained after session teardown"
        );
        drop(network);
        drop(replacement);
        finish_phase(region, "teardown", session, &mut phases, output)?;
        outcome.sessions += 1;
    }
    outcome.rms_error_m = (outcome.rms_error_m / f64::from(u32::try_from(outcome.ticks)?)).sqrt();
    outcome.validate(scenario)?;
    Ok(Run {
        scenario,
        outcome,
        phases,
    })
}

fn finish_phase(
    region: Region,
    name: &str,
    session: usize,
    phases: &mut Vec<Phase>,
    output: Option<&Path>,
) -> Result<()> {
    let (elapsed, allocations) = region.finish();
    let live = measurement::allocated_bytes()?;
    if let Some(directory) = output {
        measurement::snapshot(&directory.join(format!("session-{session}-{name}.pb.gz")))?;
    }
    phases.push(Phase {
        name: format!("{session}-{name}"),
        elapsed_ms: elapsed.as_secs_f64() * 1000.0,
        allocations,
        allocator_live_bytes: live,
    });
    Ok(())
}

/// Timestamp delivery matters: OBD precedes same-time GNSS, then coarse fixes and motion evidence.
fn deliver(
    input: &Input,
    scenario: Scenario,
    estimator: &mut NavigationEstimator,
    classifier: &mut TrustClassifier,
    jammer: &mut JamDetector,
    network: &mut NetworkTracker,
    outcome: &mut Outcome,
) -> Result<()> {
    let now_ms = input.elapsed_ms;
    if matches!(
        scenario,
        Scenario::Driving | Scenario::Delayed | Scenario::Reroute | Scenario::Lifecycle
    ) && estimator
        .on_vehicle_speed(input.speed_mps * 3.6, now_ms)
        .map_err(|error| anyhow!("OBD: {error:?}"))?
    {
        outcome.obd_accepted += 1;
    }
    outcome.jam_transitions +=
        u64::from(jammer.update(Some(if input.jammed { -30.0 } else { 0.0 }), now_ms));
    let gps = classify(input, classifier, jammer, outcome);
    let coarse = input.network.filter(|fix| {
        let projected = (fix.point.latitude_deg - 50.0) * METRES_PER_DEGREE;
        let gate = network.gate(now_ms, projected, fix.accuracy_m);
        if gate == GateResult::Rejected {
            return false;
        }
        network.record(
            NetworkSample {
                elapsed_ms: now_ms,
                position_m: projected,
                accuracy_m: fix.accuracy_m,
                offset_m: 0.0,
            },
            fix.point.latitude_deg,
            fix.point.longitude_deg,
        );
        outcome.network_accepted += 1;
        true
    });
    network.prune_history(now_ms);
    outcome.max_network_history = outcome.max_network_history.max(network.history().len());
    let fused = fuse_speed(
        gps.and_then(|fix| fix.speed_mps),
        0,
        Some(15.0),
        network.speed_estimate(now_ms),
    );
    outcome.hash(fused.map_or(0, |speed| speed.speed_mps.to_bits()));
    let motion = (scenario == Scenario::Stop).then_some(MotionObservation {
        factor: if input.speed_mps == 0.0 { 0.0 } else { 1.0 },
        cruise_speed_mps: 15.0,
        valid_until_ms: now_ms + 1500,
        network_moving: input.speed_mps > 0.0,
    });
    let walking = (scenario == Scenario::Walking).then_some(WalkingObservation {
        speed_mps: 1.4,
        valid_until_ms: now_ms + 2000,
    });
    outcome.motion_hints += u64::from(motion.is_some());
    outcome.walking_hints += u64::from(walking.is_some());
    let tick = estimator
        .tick_with_walking(now_ms, gps, motion, coarse, None, walking)
        .map_err(|error| anyhow!("tick: {error:?}"))?;
    record_tick(input, scenario, tick, gps, outcome)
}

/// Hash all numerical outputs without retaining the per-tick trace in the profiled heap.
fn record_tick(
    input: &Input,
    scenario: Scenario,
    tick: TickOutcome,
    gps: Option<GpsObservation>,
    outcome: &mut Outcome,
) -> Result<()> {
    let now_ms = input.elapsed_ms;
    outcome.gps_accepted += u64::from(tick.position_accepted);
    outcome.delayed_accepted +=
        u64::from(tick.position_accepted && gps.is_some_and(|fix| fix.elapsed_ms < now_ms));
    if let Some(projection) = tick.projection {
        outcome.hash(projection.segment as u64);
        for value in [
            projection.position_m,
            projection.offset_m,
            projection.point.latitude_deg,
            projection.point.longitude_deg,
        ] {
            outcome.hash(value.to_bits());
        }
    }
    let estimate = tick.estimate;
    outcome.stopped_ticks +=
        u64::from(scenario == Scenario::Stop && input.speed_mps == 0.0 && estimate.speed_mps < 0.5);
    outcome.resumed_ticks += u64::from(
        scenario == Scenario::Stop
            && outcome.stopped_ticks > 0
            && input.speed_mps > 1.0
            && estimate.speed_mps > 1.0,
    );
    outcome.blind_walking_ticks +=
        u64::from(scenario == Scenario::Walking && input.gps.is_none() && estimate.speed_mps > 0.5);
    outcome.gps_recovered += u64::from(
        scenario == Scenario::Jam && outcome.jam_transitions >= 2 && tick.position_accepted,
    );
    for value in [
        estimate.position_m,
        estimate.speed_mps,
        estimate.covariance.position,
        estimate.covariance.position_speed,
        estimate.covariance.speed,
        estimate.systematic_drift_m,
    ] {
        ensure!(value.is_finite(), "non-finite navigation output");
        outcome.hash(value.to_bits());
    }
    outcome.hash(u64::from(tick.position_accepted) | (u64::from(tick.speed_accepted) << 1));
    let error = (estimate.position_m - input.truth_m).abs();
    outcome.rms_error_m += error * error;
    outcome.max_error_m = outcome.max_error_m.max(error);
    outcome.ticks += 1;
    Ok(())
}

/// Feed raw fixes through trust classification before any estimator observation is constructed.
fn classify(
    input: &Input,
    classifier: &mut TrustClassifier,
    jammer: &JamDetector,
    outcome: &mut Outcome,
) -> Option<GpsObservation> {
    let now_ms = input.elapsed_ms;
    input.gps.and_then(|fix| {
        let verdict = classifier.evaluate(TrustInput {
            fix,
            network_fix: None,
            receiver: ReceiverHealth {
                satellites_visible: 16,
                satellites_used: if input.jammed { 0 } else { 12 },
                mean_cn0_used: Some(35.0),
                cn0_spread_used: Some(8.0),
                agc_db: Some(if input.jammed { -30.0 } else { 0.0 }),
                dual_frequency_used: 6,
                elapsed_ms: fix.elapsed_ms,
            },
            wall_now_ms: WALL_ORIGIN_MS + now_ms,
            compass_deg: Some(0.0),
            jammed: jammer.jammed(),
            inside_service_area: true,
        });
        outcome.hash(verdict.level as u64);
        for reason in &verdict.reasons {
            outcome.hash(*reason as u64);
        }
        match verdict.level {
            TrustLevel::Good => outcome.gps_good += 1,
            TrustLevel::Suspect => outcome.gps_suspect += 1,
            TrustLevel::Bad => {
                outcome.gps_bad += 1;
                return None;
            }
        }
        Some(GpsObservation {
            point: GeoPoint {
                latitude_deg: fix.latitude_deg,
                longitude_deg: fix.longitude_deg,
            },
            elapsed_ms: fix.elapsed_ms,
            position_accuracy_m: fix.horizontal_accuracy_m,
            speed_mps: fix.speed_mps,
            speed_accuracy_mps: Some(0.5),
            trust: if verdict.level == TrustLevel::Good {
                ObservationTrust::Good
            } else {
                ObservationTrust::Suspect
            },
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_case_is_deterministic_and_exercises_its_paths() {
        let config = Config {
            duration_s: 120,
            route_points: 100,
            ..Config::default()
        };
        for scenario in Scenario::All.cases() {
            let fixture = Fixture::new(&config, scenario);
            let first = run(&fixture, scenario, Pass::Timing, None).unwrap();
            let second = run(&fixture, scenario, Pass::Timing, None).unwrap();
            assert_eq!(first.outcome, second.outcome, "{scenario:?}");
        }
    }

    #[test]
    fn delayed_inputs_preserve_monotonic_observation_and_delivery_times() {
        let fixture = Fixture::new(&Config::default(), Scenario::Delayed);
        let mut previous_ms = -1;
        for input in fixture.inputs {
            if let Some(gps) = input.gps {
                // First delayed observations repeat startup timestamps; the estimator must ignore them.
                assert!(gps.elapsed_ms <= input.elapsed_ms);
                if input.elapsed_ms > 4000 {
                    assert!(gps.elapsed_ms > previous_ms);
                }
                previous_ms = gps.elapsed_ms;
            }
        }
    }
}
