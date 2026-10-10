//! Stateful GNSS trust firewall.
//!
//! These checks deliberately precede Kalman innovation gating. A slowly moving spoofer can produce
//! statistically plausible innovations, so receiver health, independent network agreement, clock
//! consistency and physical reachability remain separate security evidence.

use std::f64::consts::PI;

use crate::milliseconds_to_seconds;

const EARTH_RADIUS_M: f64 = 6_371_000.0;

/// AGC-based jamming state with hysteresis, independent from individual fix verdicts.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct JamDetector {
    enter_db: f64,
    exit_db: f64,
    exit_hold_ms: i64,
    jammed: bool,
    above_since_ms: Option<i64>,
    last_sample_ms: Option<i64>,
}

impl Default for JamDetector {
    fn default() -> Self {
        Self {
            enter_db: -12.0,
            exit_db: -8.0,
            exit_hold_ms: 15_000,
            jammed: false,
            above_since_ms: None,
            last_sample_ms: None,
        }
    }
}

impl JamDetector {
    #[must_use]
    pub fn jammed(&self) -> bool {
        self.jammed
    }

    /// Returns true only when the state changes. Missing or non-finite AGC is ignored.
    pub fn update(&mut self, agc_db: Option<f64>, now_ms: i64) -> bool {
        let Some(agc_db) = agc_db.filter(|value| value.is_finite()) else {
            return false;
        };
        if now_ms < 0
            || self
                .last_sample_ms
                .is_some_and(|previous| now_ms <= previous)
        {
            return false;
        }
        self.last_sample_ms = Some(now_ms);
        let before = self.jammed;
        if !self.jammed {
            self.above_since_ms = None;
            if agc_db < self.enter_db {
                self.jammed = true;
            }
        } else if agc_db < self.exit_db {
            self.above_since_ms = None;
        } else {
            let above_since_ms = self.above_since_ms.get_or_insert(now_ms);
            if now_ms.saturating_sub(*above_since_ms) >= self.exit_hold_ms {
                self.jammed = false;
                self.above_since_ms = None;
            }
        }
        let changed = before != self.jammed;
        if changed {
            tracing::debug!(jammed = self.jammed, "receiver jamming state changed");
        }
        changed
    }

    pub fn reset(&mut self) {
        self.jammed = false;
        self.above_since_ms = None;
        self.last_sample_ms = None;
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TrustLevel {
    Good,
    Suspect,
    Bad,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Reason {
    Invalid,
    Mock,
    OutsideServiceArea,
    Altitude,
    Speed,
    Accuracy,
    ClockSkew,
    DuplicateTime,
    Jump,
    SpeedMismatch,
    Frozen,
    NetworkDifference,
    Jam,
    JamWeak,
    JamStrong,
    NoSatellites,
    FewSatellites,
    WeakSignal,
    FlatSignal,
    HeadingDifference,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Verdict {
    pub level: TrustLevel,
    pub reasons: Vec<Reason>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LocationFix {
    pub wall_time_ms: i64,
    pub elapsed_ms: i64,
    pub latitude_deg: f64,
    pub longitude_deg: f64,
    pub altitude_m: Option<f64>,
    pub speed_mps: Option<f64>,
    pub bearing_deg: Option<f64>,
    pub horizontal_accuracy_m: Option<f64>,
    pub vertical_accuracy_m: Option<f64>,
    pub is_mock: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct ReceiverHealth {
    pub satellites_visible: u16,
    pub satellites_used: u16,
    pub mean_cn0_used: Option<f64>,
    pub cn0_spread_used: Option<f64>,
    pub agc_db: Option<f64>,
    pub dual_frequency_used: u16,
    pub elapsed_ms: i64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrustInput {
    pub fix: LocationFix,
    pub network_fix: Option<LocationFix>,
    pub receiver: ReceiverHealth,
    pub wall_now_ms: i64,
    pub compass_deg: Option<f64>,
    pub jammed: bool,
    /// Service-area geometry stays with the route/geospatial module; its boolean result enters the
    /// trust firewall so the policy cannot accidentally be bypassed by JNI callers.
    pub inside_service_area: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrustConfig {
    pub altitude_min_m: f64,
    pub altitude_max_m: f64,
    pub max_speed_mps: f64,
    pub max_accuracy_m: f64,
    pub max_clock_skew_ms: i64,
    pub min_satellites_used: u16,
    pub min_mean_cn0: f64,
    pub min_cn0_spread: f64,
    pub max_heading_difference_deg: f64,
    pub heading_check_min_speed_mps: f64,
    pub jam_agc_db: f64,
    pub hard_jam_agc_db: f64,
    pub frozen_min_speed_mps: f64,
    pub frozen_suspect_ms: i64,
    pub frozen_bad_ms: i64,
    pub network_difference_min_m: f64,
    pub network_max_accuracy_m: f64,
    pub network_difference_max_speed_mps: f64,
    pub speed_mismatch_min_mps: f64,
    pub strong_jam_min_satellites: u16,
    pub strong_jam_min_satellites_dual: u16,
    pub strong_jam_min_cn0: f64,
    pub strong_jam_min_spread: f64,
    pub strong_jam_network_m: f64,
    pub strong_jam_network_max_age_ms: i64,
    pub strong_jam_network_max_accuracy_m: f64,
    pub strong_jam_chain_ms: i64,
    pub max_plausible_speed_mps: f64,
}

impl Default for TrustConfig {
    fn default() -> Self {
        Self {
            altitude_min_m: -500.0,
            altitude_max_m: 9_000.0,
            max_speed_mps: 150.0 / 3.6,
            max_accuracy_m: 100.0,
            max_clock_skew_ms: 30_000,
            min_satellites_used: 5,
            min_mean_cn0: 20.0,
            min_cn0_spread: 1.5,
            max_heading_difference_deg: 165.0,
            heading_check_min_speed_mps: 5.5,
            jam_agc_db: -10.0,
            hard_jam_agc_db: -16.0,
            frozen_min_speed_mps: 3.0,
            frozen_suspect_ms: 5_000,
            frozen_bad_ms: 15_000,
            network_difference_min_m: 500.0,
            network_max_accuracy_m: 100.0,
            network_difference_max_speed_mps: 8.0,
            speed_mismatch_min_mps: 10.0,
            strong_jam_min_satellites: 8,
            strong_jam_min_satellites_dual: 6,
            strong_jam_min_cn0: 25.0,
            strong_jam_min_spread: 3.0,
            strong_jam_network_m: 150.0,
            strong_jam_network_max_age_ms: 10_000,
            strong_jam_network_max_accuracy_m: 150.0,
            strong_jam_chain_ms: 3_000,
            max_plausible_speed_mps: 150.0 / 3.6,
        }
    }
}

#[derive(Debug)]
pub struct TrustClassifier {
    config: TrustConfig,
    previous_raw: Option<LocationFix>,
    last_good: Option<LocationFix>,
    frozen_since_ms: Option<i64>,
    strong_jam_at_ms: Option<i64>,
}

impl TrustClassifier {
    #[must_use]
    pub fn new(config: TrustConfig) -> Self {
        Self {
            config,
            previous_raw: None,
            last_good: None,
            frozen_since_ms: None,
            strong_jam_at_ms: None,
        }
    }

    #[must_use]
    pub fn last_good(&self) -> Option<LocationFix> {
        self.last_good
    }

    pub fn reset(&mut self) {
        self.previous_raw = None;
        self.last_good = None;
        self.frozen_since_ms = None;
        self.strong_jam_at_ms = None;
    }

    pub fn evaluate(&mut self, input: TrustInput) -> Verdict {
        let fix = input.fix;
        // Reject before changing sequence, frozen-position or jamming state.
        if self.previous_raw.is_some_and(|previous| {
            fix.elapsed_ms <= previous.elapsed_ms || fix.wall_time_ms <= previous.wall_time_ms
        }) {
            return Verdict {
                level: TrustLevel::Bad,
                reasons: vec![Reason::DuplicateTime],
            };
        }
        let mut hard = Vec::new();
        let mut soft = Vec::new();
        self.check_fix(&input, &mut hard);
        // Reject impossible clocks without poisoning the sequence watermark or trusted anchor.
        if hard.contains(&Reason::ClockSkew) {
            return Verdict {
                level: TrustLevel::Bad,
                reasons: hard,
            };
        }
        let previous = self.previous_raw.replace(fix);
        self.check_last_good(fix, &mut hard, &mut soft);
        self.check_sequence(fix, previous, &mut hard, &mut soft);
        self.check_network(fix, input.network_fix, &mut soft);
        self.check_jamming(&input, &mut hard, &mut soft);
        self.check_receiver(&input, &mut hard, &mut soft);
        self.check_heading(fix, input.compass_deg, &mut soft);

        let verdict = self.finish_verdict(fix, hard, soft);
        tracing::trace!(level = ?verdict.level, reasons = verdict.reasons.len(), "GPS fix classified");
        verdict
    }

    /// Hard reasons always dominate; only the final GOOD verdict may publish a trusted anchor.
    fn finish_verdict(
        &mut self,
        fix: LocationFix,
        mut hard: Vec<Reason>,
        soft: Vec<Reason>,
    ) -> Verdict {
        let verdict = if !hard.is_empty() {
            hard.extend(soft);
            Verdict {
                level: TrustLevel::Bad,
                reasons: hard,
            }
        } else if !soft.is_empty() {
            Verdict {
                level: TrustLevel::Suspect,
                reasons: soft,
            }
        } else {
            Verdict {
                level: TrustLevel::Good,
                reasons: Vec::new(),
            }
        };
        self.commit_trusted_anchor(fix, verdict.level);
        verdict
    }

    /// Keep anchor promotion separate so every verdict shares the same trust boundary.
    fn commit_trusted_anchor(&mut self, fix: LocationFix, level: TrustLevel) {
        if level == TrustLevel::Good {
            self.last_good = Some(fix);
        }
    }

    fn check_fix(&self, input: &TrustInput, hard: &mut Vec<Reason>) {
        let fix = input.fix;
        let invalid_optional = [
            fix.altitude_m,
            fix.speed_mps,
            fix.bearing_deg,
            fix.horizontal_accuracy_m,
            fix.vertical_accuracy_m,
        ]
        .into_iter()
        .flatten()
        .any(|value| !value.is_finite());
        let invalid_non_negative = fix.speed_mps.is_some_and(|value| value < 0.0)
            || fix.horizontal_accuracy_m.is_some_and(|value| value < 0.0)
            || fix.vertical_accuracy_m.is_some_and(|value| value < 0.0);
        let invalid_receiver = [
            input.receiver.mean_cn0_used,
            input.receiver.cn0_spread_used,
            input.receiver.agc_db,
        ]
        .into_iter()
        .flatten()
        .any(|value| !value.is_finite());
        if !fix.latitude_deg.is_finite()
            || !fix.longitude_deg.is_finite()
            || !(-90.0..=90.0).contains(&fix.latitude_deg)
            || !(-180.0..=180.0).contains(&fix.longitude_deg)
            || invalid_optional
            || invalid_non_negative
            || invalid_receiver
        {
            hard.push(Reason::Invalid);
        }
        if fix.is_mock {
            hard.push(Reason::Mock);
        }
        if !input.inside_service_area {
            hard.push(Reason::OutsideServiceArea);
        }
        if let Some(altitude) = fix.altitude_m {
            let slack = fix.vertical_accuracy_m.unwrap_or(0.0).min(50.0);
            if altitude < self.config.altitude_min_m - slack
                || altitude > self.config.altitude_max_m + slack
            {
                hard.push(Reason::Altitude);
            }
        }
        if fix
            .speed_mps
            .is_some_and(|speed| speed > self.config.max_speed_mps)
        {
            hard.push(Reason::Speed);
        }
        if fix
            .horizontal_accuracy_m
            .is_some_and(|accuracy| accuracy > self.config.max_accuracy_m)
        {
            hard.push(Reason::Accuracy);
        }
        let maximum_clock_skew_ms = u64::try_from(self.config.max_clock_skew_ms).unwrap_or(0);
        if fix.wall_time_ms.abs_diff(input.wall_now_ms) > maximum_clock_skew_ms {
            hard.push(Reason::ClockSkew);
        }
    }

    fn check_last_good(&self, fix: LocationFix, hard: &mut Vec<Reason>, soft: &mut Vec<Reason>) {
        let Some(last) = self.last_good else {
            return;
        };
        if let (Some(accuracy), Some(last_accuracy)) =
            (fix.horizontal_accuracy_m, last.horizontal_accuracy_m)
            && accuracy > 15.0
            && accuracy > last_accuracy * 3.0
        {
            soft.push(Reason::Accuracy);
        }
        if fix.elapsed_ms <= last.elapsed_ms {
            return;
        }
        let dt_s = milliseconds_to_seconds(fix.elapsed_ms.saturating_sub(last.elapsed_ms));
        let distance = distance_m(last, fix);
        let reachable = self.config.max_plausible_speed_mps * dt_s
            + fix.horizontal_accuracy_m.unwrap_or(0.0)
            + last.horizontal_accuracy_m.unwrap_or(0.0)
            + 20.0;
        if distance > reachable {
            hard.push(Reason::Jump);
        }
        if let Some(speed) = fix.speed_mps.filter(|_| (0.5..=2.5).contains(&dt_s)) {
            let implied = distance / dt_s;
            if (speed - implied).abs()
                > self
                    .config
                    .speed_mismatch_min_mps
                    .max(speed.max(implied) * 0.5)
            {
                soft.push(Reason::SpeedMismatch);
            }
        }
    }

    fn check_sequence(
        &mut self,
        fix: LocationFix,
        previous: Option<LocationFix>,
        hard: &mut Vec<Reason>,
        soft: &mut Vec<Reason>,
    ) {
        let Some(previous) = previous else {
            self.frozen_since_ms = None;
            return;
        };
        if fix.elapsed_ms <= previous.elapsed_ms || fix.wall_time_ms <= previous.wall_time_ms {
            hard.push(Reason::DuplicateTime);
        }
        let frozen = exactly_equal(fix.latitude_deg, previous.latitude_deg)
            && exactly_equal(fix.longitude_deg, previous.longitude_deg)
            && fix
                .speed_mps
                .is_some_and(|speed| speed > self.config.frozen_min_speed_mps);
        if !frozen {
            self.frozen_since_ms = None;
            return;
        }
        let since = *self.frozen_since_ms.get_or_insert(previous.elapsed_ms);
        // Very distant or reversed timestamps still classify without overflowing before rejection.
        let duration = fix.elapsed_ms.saturating_sub(since);
        if duration >= self.config.frozen_bad_ms {
            hard.push(Reason::Frozen);
        } else if duration >= self.config.frozen_suspect_ms {
            soft.push(Reason::Frozen);
        }
    }

    fn check_network(
        &self,
        fix: LocationFix,
        network: Option<LocationFix>,
        soft: &mut Vec<Reason>,
    ) {
        let Some(network) = network else {
            return;
        };
        let Some(network_accuracy) = network.horizontal_accuracy_m else {
            return;
        };
        if !valid_network_fix(network)
            || fix.elapsed_ms.abs_diff(network.elapsed_ms) > 5_000
            || network_accuracy >= self.config.network_max_accuracy_m
            || fix
                .speed_mps
                .is_some_and(|speed| speed >= self.config.network_difference_max_speed_mps)
        {
            return;
        }
        let threshold = self
            .config
            .network_difference_min_m
            .max((fix.horizontal_accuracy_m.unwrap_or(10.0) + network_accuracy) * 3.0);
        if distance_m(fix, network) > threshold {
            soft.push(Reason::NetworkDifference);
        }
    }

    fn check_jamming(
        &mut self,
        input: &TrustInput,
        hard: &mut Vec<Reason>,
        soft: &mut Vec<Reason>,
    ) {
        // Preserve lazy geographic evaluation: only fresh, hard-jammed, healthy receivers need it.
        let independently_confirmed = receiver_fresh(input.fix, input.receiver)
            && input
                .receiver
                .agc_db
                .is_some_and(|agc| agc < self.config.hard_jam_agc_db)
            && self.healthy_constellation(input.receiver)
            && self.network_agrees(input.fix, input.network_fix);
        self.check_jamming_policy(input, independently_confirmed, hard, soft);
    }

    /// Apply jamming policy after geographic confirmation; no jammed exception grants GOOD trust.
    fn check_jamming_policy(
        &mut self,
        input: &TrustInput,
        independently_confirmed: bool,
        hard: &mut Vec<Reason>,
        soft: &mut Vec<Reason>,
    ) {
        let fresh = receiver_fresh(input.fix, input.receiver);
        if fresh
            && input
                .receiver
                .agc_db
                .is_some_and(|agc| agc < self.config.hard_jam_agc_db)
        {
            if self.healthy_constellation(input.receiver)
                && (independently_confirmed
                    || self.strong_jam_at_ms.is_some_and(|time| {
                        input
                            .fix
                            .elapsed_ms
                            .checked_sub(time)
                            .is_some_and(|age| age > 0 && age <= self.config.strong_jam_chain_ms)
                    }))
            {
                soft.push(Reason::JamStrong);
                self.strong_jam_at_ms = Some(input.fix.elapsed_ms);
            } else {
                hard.push(Reason::Jam);
                self.strong_jam_at_ms = None;
            }
        } else if input.jammed
            || (fresh
                && input
                    .receiver
                    .agc_db
                    .is_some_and(|agc| agc < self.config.jam_agc_db))
        {
            if fresh && input.receiver.satellites_used < self.config.min_satellites_used {
                hard.push(Reason::JamWeak);
            } else {
                soft.push(Reason::JamWeak);
            }
        }
    }

    fn healthy_constellation(&self, receiver: ReceiverHealth) -> bool {
        let minimum = if receiver.dual_frequency_used >= 2 {
            self.config.strong_jam_min_satellites_dual
        } else {
            self.config.strong_jam_min_satellites
        };
        receiver.satellites_used >= minimum
            && receiver.mean_cn0_used.unwrap_or(0.0) >= self.config.strong_jam_min_cn0
            && receiver.cn0_spread_used.unwrap_or(0.0) >= self.config.strong_jam_min_spread
    }

    fn network_agrees(&self, fix: LocationFix, network: Option<LocationFix>) -> bool {
        let Some(network) = network else {
            return false;
        };
        let Some(network_accuracy) = network.horizontal_accuracy_m else {
            return false;
        };
        self.network_confirmation_eligible(fix, network)
            && distance_m(fix, network)
                <= self.config.strong_jam_network_m
                    + fix.horizontal_accuracy_m.unwrap_or(10.0)
                    + network_accuracy
    }

    /// Age and precision eligibility are independent of geographic distance computation.
    fn network_confirmation_eligible(&self, fix: LocationFix, network: LocationFix) -> bool {
        valid_network_fix(network)
            && network.horizontal_accuracy_m.is_some_and(|accuracy| {
                accuracy <= self.config.strong_jam_network_max_accuracy_m
                    && fix.elapsed_ms.abs_diff(network.elapsed_ms)
                        <= u64::try_from(self.config.strong_jam_network_max_age_ms).unwrap_or(0)
            })
    }

    fn check_receiver(&self, input: &TrustInput, hard: &mut Vec<Reason>, soft: &mut Vec<Reason>) {
        let receiver = input.receiver;
        if !receiver_fresh(input.fix, receiver) {
            return;
        }
        if receiver.satellites_used == 0 && receiver.satellites_visible > 0 {
            hard.push(Reason::NoSatellites);
        }
        if receiver.satellites_used > 0
            && receiver.satellites_used < self.config.min_satellites_used
        {
            soft.push(Reason::FewSatellites);
        }
        if receiver
            .mean_cn0_used
            .is_some_and(|value| value < self.config.min_mean_cn0)
        {
            soft.push(Reason::WeakSignal);
        }
        if receiver.satellites_used >= 4
            && receiver
                .cn0_spread_used
                .is_some_and(|value| value < self.config.min_cn0_spread)
        {
            soft.push(Reason::FlatSignal);
        }
    }

    fn check_heading(&self, fix: LocationFix, compass_deg: Option<f64>, soft: &mut Vec<Reason>) {
        if let (Some(bearing), Some(speed), Some(compass)) =
            (fix.bearing_deg, fix.speed_mps, compass_deg)
            && speed > self.config.heading_check_min_speed_mps
            && angle_difference_deg(bearing, compass) > self.config.max_heading_difference_deg
        {
            soft.push(Reason::HeadingDifference);
        }
    }
}

/// Ancillary network evidence must be geographically valid with a finite, non-negative error.
/// Ignore malformed evidence instead of allowing it to confirm or discredit the GNSS fix.
fn valid_network_fix(fix: LocationFix) -> bool {
    (-90.0..=90.0).contains(&fix.latitude_deg)
        && (-180.0..=180.0).contains(&fix.longitude_deg)
        && fix
            .horizontal_accuracy_m
            .is_some_and(|accuracy| accuracy.is_finite() && accuracy >= 0.0)
}

fn receiver_fresh(fix: LocationFix, receiver: ReceiverHealth) -> bool {
    receiver.elapsed_ms > 0
        && fix
            .elapsed_ms
            .checked_sub(receiver.elapsed_ms)
            .is_some_and(|age| (0..5000).contains(&age))
}

fn distance_m(first: LocationFix, second: LocationFix) -> f64 {
    let lat1 = first.latitude_deg * PI / 180.0;
    let lat2 = second.latitude_deg * PI / 180.0;
    let delta_lat = (second.latitude_deg - first.latitude_deg) * PI / 180.0;
    let delta_lon = (second.longitude_deg - first.longitude_deg) * PI / 180.0;
    // A valid antipodal jump must retain a finite distance for the physical reachability check.
    let a = ((delta_lat / 2.0).sin().powi(2)
        + lat1.cos() * lat2.cos() * (delta_lon / 2.0).sin().powi(2))
    .clamp(0.0, 1.0);
    2.0 * EARTH_RADIUS_M * a.sqrt().atan2((1.0 - a).sqrt())
}

fn angle_difference_deg(first: f64, second: f64) -> f64 {
    ((first - second + 540.0).rem_euclid(360.0) - 180.0).abs()
}

// Frozen-fix detection deliberately tests exact receiver output. An approximate comparison would
// incorrectly classify legitimate slow movement, while ordinary equality preserves `-0.0 == 0.0`.
#[allow(clippy::float_cmp)]
#[must_use]
fn exactly_equal(first: f64, second: f64) -> bool {
    first == second
}

#[cfg(test)]
mod tests;

#[cfg(kani)]
mod kani_proofs;
