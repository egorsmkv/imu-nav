//! Speed-source precedence and model-only corrections, without inlining route replay.
use super::*;
use crate::estimator::kani_proofs::{estimator, snapshot};

fn arbitrary_hint() -> Option<MotionObservation> {
    kani::any::<bool>().then(|| MotionObservation {
        factor: f64::from_bits(kani::any()),
        cruise_speed_mps: f64::from_bits(kani::any()),
        valid_until_ms: kani::any(),
        network_moving: kani::any(),
    })
}

fn stop_hint() -> MotionObservation {
    MotionObservation {
        factor: 0.0,
        cruise_speed_mps: 10.0,
        valid_until_ms: 6000,
        network_moving: false,
    }
}

/// Accepted measured speed has already cleared motion control in reachable estimator state.
#[kani::proof]
#[kani::unwind(3)]
fn fresh_measured_speed_blocks_all_motion_hints() {
    let mut navigation = estimator();
    let now: i64 = kani::any();
    let measured: i64 = kani::any();
    let gps: bool = kani::any();
    kani::assume(measured >= 0 && measured <= now);
    kani::assume(i128::from(now) - i128::from(measured) < 2500);
    navigation.state.elapsed_ms = now;
    if gps {
        navigation.state.last_gps_speed_ms = measured;
    } else {
        navigation.state.last_vehicle_speed_ms = measured;
    }
    let before = snapshot(&navigation);
    navigation.apply_motion(arbitrary_hint()).unwrap();
    assert!(snapshot(&navigation) == before);
    kani::cover!(gps, "gps wins");
    kani::cover!(!gps, "obd wins");
}

#[kani::proof]
#[kani::unwind(3)]
fn measured_priority_ends_at_exact_freshness_boundary() {
    let mut navigation = estimator();
    let age = i64::from(kani::any::<u16>());
    kani::assume(age <= 3000);
    let gps: bool = kani::any();
    navigation.state.elapsed_ms = 5000;
    if gps {
        navigation.state.last_gps_speed_ms = 5000 - age;
    } else {
        navigation.state.last_vehicle_speed_ms = 5000 - age;
    }
    let before = navigation.estimate();
    navigation.apply_motion(Some(stop_hint())).unwrap();
    assert_eq!(
        navigation.estimate().speed_mps,
        if age < 2500 { 10.0 } else { 0.0 }
    );
    assert_eq!(navigation.state.motion_control.is_some(), age >= 2500);
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    assert_eq!(
        navigation.state.last_gps_speed_ms,
        if gps { 5000 - age } else { -1 }
    );
    assert_eq!(
        navigation.state.last_vehicle_speed_ms,
        if gps { -1 } else { 5000 - age }
    );
    kani::cover!(gps && age == 2499, "gps fresh boundary");
    kani::cover!(!gps && age == 2499, "obd fresh boundary");
    kani::cover!(gps && age == 2500, "gps expired boundary");
    kani::cover!(!gps && age == 2500, "obd expired boundary");
}

#[kani::proof]
#[kani::unwind(3)]
fn walking_mode_ignores_car_motion_hints() {
    let mut navigation = estimator();
    navigation.mode = TravelMode::Foot;
    navigation.state.filter.set_speed_prior(1.5, 1.5).unwrap();
    navigation.state.walking_valid_until_ms = Some(2000);
    let before = snapshot(&navigation);
    let hint = arbitrary_hint();
    navigation.apply_motion(hint).unwrap();
    assert!(snapshot(&navigation) == before);
    kani::cover!(hint.is_some(), "hint ignored");
    kani::cover!(hint.is_none(), "missing hint");
}

/// Repeating the same ramp must use the saved cruise speed, not multiply an already slowed speed.
/// Fixed initial-speed cases keep unrelated floating-point branch choices out of each SAT problem.
fn repeated_motion_hint(initial_speed: f64, factor: f64) {
    let mut navigation = estimator();
    navigation
        .state
        .filter
        .set_speed_prior(initial_speed, 2.0)
        .unwrap();
    navigation.state.filter.estimate.covariance.position_speed = 2.0;
    navigation.state.elapsed_ms = 5000;
    let hint = MotionObservation {
        factor,
        cruise_speed_mps: 16.0,
        ..stop_hint()
    };
    let before = navigation.estimate();
    navigation.apply_motion(Some(hint)).unwrap();
    let first = navigation.estimate();
    navigation.apply_motion(Some(hint)).unwrap();
    let repeated = navigation.estimate();
    assert_eq!(first.speed_mps.to_bits(), repeated.speed_mps.to_bits());
    assert_eq!(first, repeated);
    assert!(repeated.speed_mps.is_finite() && repeated.speed_mps >= 0.0);
    assert!(repeated.speed_mps <= initial_speed.max(MAX_CRUISE_SPEED_MPS));
    assert_eq!(repeated.position_m, before.position_m);
    assert_eq!(repeated.covariance.position, before.covariance.position);
    assert_eq!(repeated.systematic_drift_m, before.systematic_drift_m);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    if let Some(control) = navigation.state.motion_control {
        let expected = if initial_speed >= 0.5 {
            initial_speed.min(MAX_CRUISE_SPEED_MPS)
        } else {
            16.0
        };
        assert_eq!(control.cruise_speed_mps, expected);
    }
    kani::cover!(factor == 0.0, "stop");
    kani::cover!(factor > 0.0 && factor < 1.0, "ramp");
    kani::cover!(factor == 1.0, "cruise");
}

#[kani::proof]
#[kani::unwind(3)]
fn repeated_motion_hint_preserves_position_and_cruise() {
    let factor = f64::from_bits(kani::any());
    kani::assume((0.0..=1.0).contains(&factor));
    repeated_motion_hint(10.0, factor);
}

#[kani::proof]
#[kani::unwind(3)]
fn repeated_motion_hint_preserves_fallback_cruise() {
    let factor = f64::from_bits(kani::any());
    kani::assume((0.0..=1.0).contains(&factor));
    repeated_motion_hint(0.0, factor);
}

#[kani::proof]
#[kani::unwind(3)]
fn repeated_motion_hint_preserves_capped_cruise() {
    let numerator: u16 = kani::any();
    kani::assume(numerator <= 256);
    repeated_motion_hint(63.75, f64::from(numerator) / 256.0);
}

#[kani::proof]
#[kani::unwind(3)]
fn vetoed_motion_restores_cruise_without_an_anchor() {
    let mut navigation = estimator();
    navigation.state.elapsed_ms = 5000;
    let before = navigation.estimate();
    navigation.apply_motion(Some(stop_hint())).unwrap();
    assert_eq!(navigation.estimate().speed_mps, 0.0);
    let veto: u8 = kani::any();
    kani::assume(veto < 3);
    let hint = match veto {
        0 => None,
        1 => Some(MotionObservation {
            network_moving: true,
            ..stop_hint()
        }),
        _ => Some(MotionObservation {
            factor: f64::NAN,
            ..stop_hint()
        }),
    };
    navigation.apply_motion(hint).unwrap();
    assert!(navigation.state.motion_control.is_none());
    assert_eq!(navigation.estimate().speed_mps, before.speed_mps);
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    kani::cover!(veto == 0, "missing");
    kani::cover!(veto == 1, "network moving");
    kani::cover!(veto == 2, "invalid");
}

/// A trusted 35 m/s measurement can recover a false stop; SUSPECT GPS cannot use that fallback.
#[kani::proof]
#[kani::unwind(3)]
fn measured_speed_recovery_clears_control_only_when_accepted() {
    let mut state = estimator().state;
    state
        .filter
        .set_speed_prior(0.0, STOP_SPEED_SIGMA_MPS)
        .unwrap();
    state.motion_control = Some(MotionControl {
        cruise_speed_mps: 35.0,
        valid_until_ms: 2000,
    });
    let before = state.clone();
    let trusted: bool = kani::any();
    let accepted = state.update_measured_speed(35.0, 0.6, trusted).unwrap();
    assert_eq!(accepted, trusted);
    if accepted {
        assert!(state.motion_control.is_none());
        assert_eq!(state.filter.estimate().speed_mps, 35.0);
        assert_eq!(
            state.filter.estimate().position_m,
            before.filter.estimate().position_m
        );
        assert_eq!(
            state.filter.estimate().covariance.position,
            before.filter.estimate().covariance.position
        );
        assert_eq!(
            state.filter.estimate().systematic_drift_m,
            before.filter.estimate().systematic_drift_m
        );
        assert_eq!(state.last_gps_speed_ms, before.last_gps_speed_ms);
        assert_eq!(state.last_vehicle_speed_ms, before.last_vehicle_speed_ms);
    } else {
        assert!(state == before);
    }
    kani::cover!(accepted, "trusted recovery");
    kani::cover!(!accepted, "suspect rejected");
}
