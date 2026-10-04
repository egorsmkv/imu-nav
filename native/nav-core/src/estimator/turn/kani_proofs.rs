//! Turn-policy proofs use precomputed landmarks and the real production correction path.
use super::*;
use crate::estimator::kani_proofs::{estimator, snapshot};
use crate::route::RouteTurn;
use std::sync::Arc;

fn navigation(turns: Vec<RouteTurn>) -> NavigationEstimator {
    let mut navigation = estimator();
    navigation.route = Arc::new(crate::route::kani_proofs::with_landmarks(turns));
    navigation.state.elapsed_ms = 6000;
    navigation.state.filter.estimate.position_m = 400.0;
    navigation.state.filter.estimate.covariance.position = 10_000.0;
    navigation.state.filter.estimate.covariance.position_speed = 2.0;
    navigation.state.filter.estimate.systematic_drift_m = 50.0;
    navigation
}

fn landmark(position_m: f64, angle_deg: f64) -> RouteTurn {
    RouteTurn {
        position_m,
        angle_deg,
    }
}

fn observation() -> TurnObservation {
    TurnObservation {
        start_ms: 1000,
        end_ms: 5000,
        angle_deg: 90.0,
    }
}

#[kani::proof]
#[kani::unwind(4)]
fn invalid_turn_ingress_preserves_all_state() {
    let mut navigation = navigation(vec![landmark(500.0, 90.0)]);
    navigation.state.turn_state.since_ms = 1000;
    navigation.state.turn_state.last_seen_ms = 3000;
    let input = TurnObservation {
        start_ms: kani::any(),
        end_ms: kani::any(),
        angle_deg: f64::from_bits(kani::any()),
    };
    let duration = i128::from(input.end_ms) - i128::from(input.start_ms);
    let age = 6000_i128 - i128::from(input.end_ms);
    let invalid = input.start_ms < 1000
        || input.end_ms <= 3000
        || !(0..=2000).contains(&age)
        || !(1500..=8000).contains(&duration)
        || !(45.0..=125.0).contains(&input.angle_deg.abs());
    kani::assume(invalid);
    let before = snapshot(&navigation);
    assert!(!navigation.apply_turn(Some(input)).unwrap());
    assert!(snapshot(&navigation) == before);
    kani::cover!(input.end_ms == 3000, "duplicate");
    kani::cover!(input.end_ms > 6000, "future");
    kani::cover!(age > 2000, "stale");
    kani::cover!(input.start_ms < 1000, "old route");
    kani::cover!(input.angle_deg.is_nan(), "invalid angle");
}

#[kani::proof]
#[kani::unwind(4)]
fn measured_position_motion_and_quality_gates_block_turns() {
    let mut navigation = navigation(vec![landmark(500.0, 90.0)]);
    let reason: u8 = kani::any();
    kani::assume(reason < 6);
    let age = i64::from(kani::any::<u16>());
    kani::assume(age <= 2500);
    match reason {
        0 => navigation.state.last_gps_position_ms = 6000 - age,
        1 => {
            navigation.state.motion_control = Some(crate::estimator::MotionControl {
                cruise_speed_mps: 10.0,
                valid_until_ms: 7000,
            })
        }
        2 => navigation.mode = TravelMode::Foot,
        3 => navigation.state.filter.estimate.speed_mps = 0.0,
        4 => navigation.state.filter.estimate.speed_mps = 19.0,
        _ => navigation.state.filter.estimate.covariance.position = 90_001.0,
    }
    let before = snapshot(&navigation);
    assert!(!navigation.apply_turn(Some(observation())).unwrap());
    assert_eq!(
        navigation.state.turn_state.last_seen_ms,
        if reason == 2 { -1 } else { 5000 }
    );
    navigation.state.turn_state.last_seen_ms = -1;
    assert!(snapshot(&navigation) == before);
    kani::cover!(reason == 0 && age == 2500, "gps boundary");
    kani::cover!(reason == 1, "motion control");
    kani::cover!(reason == 2, "walking");
    kani::cover!(reason == 3, "stationary");
    kani::cover!(reason == 4, "fast");
    kani::cover!(reason == 5, "uncertain");
}

#[kani::proof]
#[kani::unwind(4)]
fn cooldown_accepts_only_at_or_after_exact_boundary() {
    let mut navigation = navigation(vec![landmark(100.0, 90.0), landmark(500.0, 90.0)]);
    navigation.state.elapsed_ms = 21_000;
    let gap = i64::from(kani::any::<u16>());
    kani::assume((1..=16_000).contains(&gap));
    let last_used = 20_000 - gap;
    navigation.state.turn_state.last_used_ms = Some(last_used);
    navigation.state.turn_state.last_seen_ms = last_used;
    navigation.state.turn_state.last_landmark = Some(0);
    let before = navigation.estimate();
    let accepted = navigation
        .apply_turn(Some(TurnObservation {
            start_ms: 16_000,
            end_ms: 20_000,
            angle_deg: 90.0,
        }))
        .unwrap();
    assert_eq!(accepted, gap >= 15_000);
    assert_eq!(navigation.state.turn_state.last_seen_ms, 20_000);
    if accepted {
        assert_eq!(navigation.state.turn_state.last_used_ms, Some(20_000));
        assert_eq!(navigation.state.turn_state.last_landmark, Some(1));
        assert!(navigation.estimate().position_m - before.position_m <= 30.0);
    } else {
        assert_eq!(navigation.estimate(), before);
        assert_eq!(navigation.state.turn_state.last_used_ms, Some(last_used));
        assert_eq!(navigation.state.turn_state.last_landmark, Some(0));
    }
    kani::cover!(gap == 14_999 && !accepted, "before cooldown");
    kani::cover!(gap == 15_000 && accepted, "cooldown boundary");
}

#[kani::proof]
#[kani::unwind(4)]
fn a_used_landmark_cannot_be_reused_after_cooldown() {
    let mut navigation = navigation(vec![landmark(500.0, 90.0)]);
    // Successful correction recording is proved separately; start with its used-landmark state.
    navigation.state.turn_state.last_seen_ms = 5000;
    navigation.state.turn_state.last_used_ms = Some(5000);
    navigation.state.turn_state.last_landmark = Some(0);
    let extra = i64::from(kani::any::<u16>());
    navigation.state.elapsed_ms = 21_000 + extra;
    let before = snapshot(&navigation);
    assert!(
        !navigation
            .apply_turn(Some(TurnObservation {
                start_ms: 16_000 + extra,
                end_ms: 20_000 + extra,
                angle_deg: 90.0,
            }))
            .unwrap()
    );
    assert_eq!(navigation.state.turn_state.last_seen_ms, 20_000 + extra);
    navigation.state.turn_state.last_seen_ms = 5000;
    assert!(snapshot(&navigation) == before);
    kani::cover!(extra == 0, "same landmark rejected");
    kani::cover!(extra > 0, "later reuse rejected");
}

#[kani::proof]
#[kani::unwind(4)]
fn ambiguous_and_nearby_landmarks_cannot_correct_position() {
    let kind: u8 = kani::any();
    kani::assume(kind < 3);
    let second_position = if kind == 0 {
        400.0
    } else {
        500.0 + f64::from(kani::any::<u8>()) / 4.0
    };
    let second_angle = if kind == 0 { 90.0 } else { -90.0 };
    let turns = if kind == 2 {
        Vec::new()
    } else {
        vec![
            landmark(500.0, 90.0),
            landmark(second_position, second_angle),
        ]
    };
    let mut navigation = navigation(turns);
    let before = snapshot(&navigation);
    assert!(!navigation.apply_turn(Some(observation())).unwrap());
    assert_eq!(navigation.state.turn_state.last_seen_ms, 5000);
    navigation.state.turn_state.last_seen_ms = -1;
    assert!(snapshot(&navigation) == before);
    kani::cover!(kind == 0, "ambiguous");
    kani::cover!(kind == 1, "nearby rival");
    kani::cover!(kind == 2, "no landmark");
}

#[kani::proof]
#[kani::unwind(4)]
fn isolated_turn_correction_is_bounded_and_never_refreshes_sensors() {
    let position = f64::from(kani::any::<u16>());
    kani::assume((300.0..=550.0).contains(&position));
    let mut navigation = navigation(vec![landmark(position, 90.0)]);
    let before = navigation.estimate();
    assert!(navigation.apply_turn(Some(observation())).unwrap());
    let after = navigation.estimate();
    assert!((after.position_m - before.position_m).abs() <= 30.0);
    assert_eq!(after.speed_mps, before.speed_mps);
    assert_eq!(after.covariance.speed, before.covariance.speed);
    assert_eq!(after.systematic_drift_m, before.systematic_drift_m);
    assert!(after.covariance.position >= 30.0 * 30.0);
    assert_eq!(navigation.state.last_gps_position_ms, -1);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
    assert_eq!(navigation.state.turn_state.last_used_ms, Some(5000));
    assert_eq!(navigation.state.turn_state.last_landmark, Some(0));
    // The same event is inert even when offered again immediately.
    let once = snapshot(&navigation);
    assert!(!navigation.apply_turn(Some(observation())).unwrap());
    assert!(snapshot(&navigation) == once);
    kani::cover!(after.position_m < before.position_m, "backward correction");
    kani::cover!(after.position_m > before.position_m, "forward correction");
    kani::cover!(
        (after.position_m - before.position_m).abs() == 30.0,
        "correction cap"
    );
}

#[kani::proof]
#[kani::unwind(4)]
fn landmark_isolation_accepts_the_exact_distance_boundary() {
    let distance = f64::from(kani::any::<u8>());
    let mut navigation = navigation(vec![
        landmark(500.0, 90.0),
        landmark(500.0 + distance, -90.0),
    ]);
    let before = navigation.estimate();
    let accepted = navigation.apply_turn(Some(observation())).unwrap();
    assert_eq!(accepted, distance >= 80.0);
    if !accepted {
        assert_eq!(navigation.estimate(), before);
        assert!(navigation.state.turn_state.last_used_ms.is_none());
        assert!(navigation.state.turn_state.last_landmark.is_none());
    }
    kani::cover!(distance == 79.0 && !accepted, "inside isolation");
    kani::cover!(distance == 80.0 && accepted, "isolation boundary");
}
