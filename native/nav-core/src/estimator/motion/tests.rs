//! Model transitions, contradictory evidence, and delayed-observation replay regressions.

use super::super::regression_tests::{estimator, gps};
use super::*;

fn hint(now_ms: i64, factor: f64) -> MotionObservation {
    MotionObservation {
        factor,
        cruise_speed_mps: 15.0,
        valid_until_ms: now_ms + 2_000,
        network_moving: false,
    }
}

#[test]
fn stop_holds_position_without_erasing_uncertainty_and_resume_restores_motion() {
    let mut navigation = estimator(20.0);
    let stopped = navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap()
        .estimate;
    assert!(stopped.speed_mps.abs() < f64::EPSILON);
    assert!(stopped.position_sigma_m() >= 20.0);
    assert!((stopped.systematic_drift_m - 0.8).abs() < 1.0e-9);
    for time in [1_500, 2_000, 2_500, 3_000] {
        let state = navigation
            .tick_with_motion(time, None, Some(hint(time, 0.0)))
            .unwrap()
            .estimate;
        assert!((state.position_m - stopped.position_m).abs() < f64::EPSILON);
        assert!(state.position_sigma_m() >= stopped.position_sigma_m());
    }
    let ramp = navigation
        .tick_with_motion(3_500, None, Some(hint(3_500, 0.25)))
        .unwrap()
        .estimate;
    assert!((ramp.speed_mps - 2.5).abs() < 1.0e-9);
    let moving = navigation
        .tick_with_motion(4_000, None, Some(hint(4_000, 1.0)))
        .unwrap()
        .estimate;
    assert!(moving.position_m > stopped.position_m);
    assert!((moving.speed_mps - 10.0).abs() < 1.0e-9);
}

#[test]
fn missing_imu_expires_at_sample_deadline_even_after_a_long_tick_gap() {
    let mut navigation = estimator(20.0);
    navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap();
    let state = navigation.tick(7_000, None).unwrap().estimate;
    assert!((state.position_m - 50.0).abs() < 1.0e-9);
    assert!((state.speed_mps - 10.0).abs() < 1.0e-9);
}

#[test]
fn fresh_gps_obd_and_network_motion_override_quiet_imu() {
    let mut gps_navigation = estimator(20.0);
    gps_navigation.tick(500, Some(gps(500, 50.00005))).unwrap();
    let moving = gps_navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap()
        .estimate;
    assert!(moving.speed_mps > 10.0);

    let mut obd_navigation = estimator(20.0);
    obd_navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap();
    assert!(obd_navigation.on_vehicle_speed(54.0, 1_100).unwrap());
    let moving = obd_navigation
        .tick_with_motion(1_500, None, Some(hint(1_500, 0.0)))
        .unwrap()
        .estimate;
    assert!(moving.speed_mps > 14.0);

    let mut network_navigation = estimator(20.0);
    network_navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap();
    let mut network_hint = hint(1_500, 0.0);
    network_hint.network_moving = true;
    let moving = network_navigation
        .tick_with_motion(1_500, None, Some(network_hint))
        .unwrap()
        .estimate;
    assert!((moving.speed_mps - 10.0).abs() < 1.0e-9);
}

#[test]
fn delayed_gps_replays_motion_hints_instead_of_latching_an_obsolete_stop() {
    let mut timely = estimator(20.0);
    let mut delayed = timely.clone();
    let observation = gps(500, 50.00005);
    timely.tick(500, Some(observation)).unwrap();
    delayed.tick(500, None).unwrap();
    for time in [1_000, 1_500] {
        timely
            .tick_with_motion(time, None, Some(hint(time, 0.0)))
            .unwrap();
        delayed
            .tick_with_motion(time, None, Some(hint(time, 0.0)))
            .unwrap();
    }
    assert!(delayed.estimate().speed_mps.abs() < f64::EPSILON);
    timely
        .tick_with_motion(2_000, None, Some(hint(2_000, 0.0)))
        .unwrap();
    delayed
        .tick_with_motion(2_000, Some(observation), Some(hint(2_000, 0.0)))
        .unwrap();
    assert_eq!(timely.estimate(), delayed.estimate());
    assert!(delayed.estimate().speed_mps > 10.0);
}

#[test]
fn stale_invalid_and_walking_hints_cannot_stop_prediction() {
    let mut walking = estimator(20.0);
    walking.mode = TravelMode::Foot;
    assert!(
        walking
            .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
            .unwrap()
            .estimate
            .speed_mps
            > 0.0
    );
    for observation in [
        MotionObservation {
            valid_until_ms: 1_000,
            ..hint(1_000, 0.0)
        },
        MotionObservation {
            valid_until_ms: 10_000,
            ..hint(1_000, 0.0)
        },
        MotionObservation {
            factor: f64::NAN,
            ..hint(1_000, 0.0)
        },
        MotionObservation {
            factor: -1.0,
            ..hint(1_000, 0.0)
        },
    ] {
        let mut navigation = estimator(20.0);
        let state = navigation
            .tick_with_motion(1_000, None, Some(observation))
            .unwrap()
            .estimate;
        assert!((state.speed_mps - 10.0).abs() < 1.0e-9);
    }
}
