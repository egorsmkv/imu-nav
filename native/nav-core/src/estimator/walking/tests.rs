//! Pedestrian control remains bounded, replayable, and separate from vehicle/landmark heuristics.

use super::*;
use crate::estimator::regression_tests::{estimator as car_estimator, gps};
use crate::estimator::{InitialEstimate, MotionObservation, NetworkObservation, TurnObservation};

fn walker() -> NavigationEstimator {
    let base = car_estimator(20.0);
    NavigationEstimator::new(
        base.route,
        InitialEstimate {
            position_m: 0.0,
            speed_mps: 10.0,
            position_sigma_m: 20.0,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 10.0,
        },
        TravelMode::Foot,
        0,
    )
    .unwrap()
}

fn hint(now_ms: i64, speed_mps: f64) -> WalkingObservation {
    WalkingObservation {
        speed_mps,
        valid_until_ms: now_ms + 2_500,
    }
}

fn tick(navigation: &mut NavigationEstimator, now_ms: i64, speed_mps: f64) {
    navigation
        .tick_with_walking(
            now_ms,
            None,
            None,
            None,
            None,
            Some(hint(now_ms, speed_mps)),
        )
        .unwrap();
}

#[test]
fn walking_starts_and_restores_without_inventing_movement() {
    let mut navigation = walker();
    navigation.tick(30_000, None).unwrap();
    assert_eq!(navigation.estimate().position_m, 0.0);
    assert_eq!(navigation.estimate().speed_mps, 0.0);
    assert!(navigation.estimate().systematic_drift_m >= 10.0);
}

#[test]
fn cadence_prior_advances_stops_and_resumes_without_a_position_anchor() {
    let mut navigation = walker();
    let before = navigation.estimate();
    tick(&mut navigation, 0, 1.44);
    assert_eq!(navigation.estimate().position_m, before.position_m);
    assert_eq!(
        navigation.estimate().covariance.position,
        before.covariance.position
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        before.systematic_drift_m
    );
    for time in (500..=10_000).step_by(500) {
        tick(&mut navigation, time, 1.44);
    }
    assert!((navigation.estimate().position_m - 14.4).abs() < 1e-9);
    tick(&mut navigation, 10_000, 0.0);
    let stopped = navigation.estimate();
    tick(&mut navigation, 12_000, 0.0);
    assert_eq!(navigation.estimate().position_m, stopped.position_m);
    assert!(
        navigation.estimate().safety_radius_m(3.0).unwrap()
            >= stopped.safety_radius_m(3.0).unwrap()
    );
    tick(&mut navigation, 12_000, 1.44);
    tick(&mut navigation, 13_000, 1.44);
    assert!((navigation.estimate().position_m - stopped.position_m - 1.44).abs() < 1e-9);
}

#[test]
fn long_tick_gap_splits_at_step_expiry_then_holds() {
    let mut navigation = walker();
    tick(&mut navigation, 0, 1.44);
    navigation.tick(10_000, None).unwrap();
    assert!((navigation.estimate().position_m - 3.6).abs() < 1e-9);
    assert_eq!(navigation.estimate().speed_mps, 0.0);
    navigation.tick(20_000, None).unwrap();
    assert!((navigation.estimate().position_m - 3.6).abs() < 1e-9);
}

#[test]
fn fresh_good_gps_speed_wins_but_does_not_extrapolate_forever() {
    let mut navigation = walker();
    let mut observation = gps(1_000, 50.0);
    observation.speed_mps = Some(1.6);
    let outcome = navigation
        .tick_with_walking(
            1_000,
            Some(observation),
            None,
            None,
            None,
            Some(hint(1_000, 0.0)),
        )
        .unwrap();
    assert!(outcome.speed_accepted);
    assert!(outcome.estimate.speed_mps > 1.0);
    navigation.tick(10_000, None).unwrap();
    assert!(
        (navigation.estimate().position_m
            - outcome.estimate.position_m
            - outcome.estimate.speed_mps * 2.5)
            .abs()
            < 1e-9
    );
    assert_eq!(navigation.estimate().speed_mps, 0.0);
}

#[test]
fn delayed_gps_replays_walking_hints_and_their_expiry() {
    let mut timely = walker();
    let mut delayed = walker();
    let mut observation = gps(1_500, 50.000_02);
    observation.speed_mps = Some(1.4);
    for time in (0..=6_000).step_by(500) {
        let speed = if time < 3_000 { 1.44 } else { 0.0 };
        timely
            .tick_with_walking(
                time,
                (time == 1_500).then_some(observation),
                None,
                None,
                None,
                Some(hint(time, speed)),
            )
            .unwrap();
        delayed
            .tick_with_walking(
                time,
                (time == 3_500).then_some(observation),
                None,
                None,
                None,
                Some(hint(time, speed)),
            )
            .unwrap();
        if time >= 3_500 {
            assert_eq!(timely.estimate(), delayed.estimate());
        }
    }
}

#[test]
fn walking_and_car_observations_cannot_cross_modes() {
    let mut car = car_estimator(20.0);
    let mut baseline_car = car.clone();
    car.tick_with_walking(1_000, None, None, None, None, Some(hint(1_000, 0.0)))
        .unwrap();
    baseline_car.tick(1_000, None).unwrap();
    assert_eq!(car.estimate(), baseline_car.estimate());

    let mut walking = walker();
    let mut baseline_walking = walking.clone();
    walking.set_network_speed_enabled(true);
    baseline_walking.set_network_speed_enabled(true);
    assert!(!walking.on_vehicle_speed(100.0, 1_000).unwrap());
    for time in (1_000..=30_000).step_by(1_000) {
        let motion = MotionObservation {
            factor: 0.0,
            cruise_speed_mps: 30.0,
            valid_until_ms: time + 2_000,
            network_moving: true,
        };
        let network = NetworkObservation {
            point: gps(time, 50.000_1).point,
            accuracy_m: 30.0,
            elapsed_ms: time,
        };
        let turn = TurnObservation {
            start_ms: time - 500,
            end_ms: time,
            angle_deg: 90.0,
        };
        walking
            .tick_with_walking(
                time,
                None,
                Some(motion),
                Some(network),
                Some(turn),
                Some(hint(time, 1.44)),
            )
            .unwrap();
        tick(&mut baseline_walking, time, 1.44);
        assert_eq!(walking.estimate(), baseline_walking.estimate());
    }
}

#[test]
fn invalid_or_stale_hints_cannot_create_speed() {
    for observation in [
        hint(1_000, f64::NAN),
        hint(1_000, -1.0),
        hint(1_000, 10.0),
        WalkingObservation {
            valid_until_ms: 1_000,
            ..hint(1_000, 1.4)
        },
        WalkingObservation {
            valid_until_ms: 10_000,
            ..hint(1_000, 1.4)
        },
    ] {
        let mut navigation = walker();
        navigation
            .tick_with_walking(1_000, None, None, None, None, Some(observation))
            .unwrap();
        assert_eq!(navigation.estimate().speed_mps, 0.0);
        assert_eq!(navigation.estimate().position_m, 0.0);
    }
}
