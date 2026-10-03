//! Pedestrian source precedence and repeated-prior bounds on the actual production setters.
use super::*;
use crate::estimator::kani_proofs::{estimator, snapshot};

fn arbitrary_hint() -> Option<WalkingObservation> {
    kani::any::<bool>().then(|| WalkingObservation {
        speed_mps: f64::from_bits(kani::any()),
        valid_until_ms: kani::any(),
    })
}

#[kani::proof]
#[kani::unwind(3)]
fn car_mode_ignores_all_walking_hints() {
    let mut navigation = estimator();
    let before = snapshot(&navigation);
    let hint = arbitrary_hint();
    navigation.apply_walking(hint).unwrap();
    assert!(snapshot(&navigation) == before);
    kani::cover!(hint.is_some(), "hint ignored");
    kani::cover!(hint.is_none(), "missing hint");
}

#[kani::proof]
#[kani::unwind(3)]
fn fresh_gps_preserves_walking_speed_and_original_deadline() {
    let mut navigation = estimator();
    navigation.mode = TravelMode::Foot;
    navigation.state.filter.set_speed_prior(1.5, 1.5).unwrap();
    let measured: i64 = kani::any();
    let now: i64 = kani::any();
    kani::assume(measured >= 0 && measured <= now);
    let deadline = measured.saturating_add(GPS_SPEED_MAX_AGE_MS);
    kani::assume(now < deadline);
    navigation.state.elapsed_ms = now;
    navigation.state.last_gps_speed_ms = measured;
    let before = snapshot(&navigation);
    let hint = arbitrary_hint();
    navigation.apply_walking(hint).unwrap();
    assert_eq!(navigation.state.walking_valid_until_ms, Some(deadline));
    navigation.apply_walking(hint).unwrap();
    assert_eq!(navigation.state.walking_valid_until_ms, Some(deadline));
    // The only permitted change is the expiry derived from the original GPS timestamp.
    navigation.state.walking_valid_until_ms = None;
    assert!(snapshot(&navigation) == before);
    kani::cover!(hint.is_some(), "gps wins");
    kani::cover!(hint.is_none(), "gps without hint");
    kani::cover!(deadline == i64::MAX, "saturated deadline");
}

#[kani::proof]
#[kani::unwind(3)]
fn walking_hint_takes_over_at_gps_expiry() {
    let mut navigation = estimator();
    navigation.mode = TravelMode::Foot;
    navigation.state.filter.set_speed_prior(1.5, 1.5).unwrap();
    navigation.state.elapsed_ms = 5000;
    let age = i64::from(kani::any::<u16>());
    kani::assume(age <= 3000);
    navigation.state.last_gps_speed_ms = 5000 - age;
    let before = navigation.estimate();
    navigation
        .apply_walking(Some(WalkingObservation {
            speed_mps: 2.0,
            valid_until_ms: 6000,
        }))
        .unwrap();
    assert_eq!(
        navigation.estimate().speed_mps,
        if age < 2500 { 1.5 } else { 2.0 }
    );
    assert_eq!(
        navigation.state.walking_valid_until_ms,
        Some(if age < 2500 { 7500 - age } else { 6000 })
    );
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    assert_eq!(navigation.state.last_gps_speed_ms, 5000 - age);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    kani::cover!(age == 2499, "fresh boundary");
    kani::cover!(age == 2500, "expired boundary");
    kani::cover!(age > 2500, "expired");
}

#[kani::proof]
#[kani::unwind(3)]
fn repeated_walking_hints_are_bounded_and_never_anchor_position() {
    let mut navigation = estimator();
    navigation.mode = TravelMode::Foot;
    navigation.state.elapsed_ms = 5000;
    navigation.state.filter.estimate.covariance.position_speed = 2.0;
    let hint = arbitrary_hint();
    let before = navigation.estimate();
    navigation.apply_walking(hint).unwrap();
    let first = navigation.estimate();
    navigation.apply_walking(hint).unwrap();
    let repeated = navigation.estimate();
    let valid = hint.is_some_and(|hint| {
        hint.speed_mps.is_finite()
            && (0.0..=4.0).contains(&hint.speed_mps)
            && (1..=2500).contains(&(i128::from(hint.valid_until_ms) - 5000))
    });
    let expected_speed = if valid { hint.unwrap().speed_mps } else { 0.0 };
    assert_eq!(repeated.speed_mps.to_bits(), expected_speed.to_bits());
    assert!(repeated.speed_mps.is_finite() && (0.0..=4.0).contains(&repeated.speed_mps));
    assert_eq!(first.speed_mps.to_bits(), repeated.speed_mps.to_bits());
    assert_eq!(first, repeated);
    assert_eq!(repeated.position_m, before.position_m);
    assert_eq!(repeated.covariance.position, before.covariance.position);
    assert_eq!(repeated.systematic_drift_m, before.systematic_drift_m);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    assert_eq!(
        navigation.state.walking_valid_until_ms,
        if valid {
            Some(hint.unwrap().valid_until_ms)
        } else {
            None
        }
    );
    kani::cover!(valid && repeated.speed_mps > 0.0, "moving");
    kani::cover!(valid && repeated.speed_mps == 0.0, "stopped");
    kani::cover!(!valid && hint.is_some(), "invalid");
    kani::cover!(hint.is_none(), "missing");
}
