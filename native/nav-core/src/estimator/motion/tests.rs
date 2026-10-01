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

/// A quiet IMU can falsely stop a highway-speed car; independent speed must recover it.
fn highway_false_stop() -> NavigationEstimator {
    let mut navigation = estimator(20.0);
    navigation.state.filter = crate::RouteFilter::new(0.0, 35.0, 20.0, 2.0, 0.0).unwrap();
    navigation.history.clear();
    navigation.remember(None);
    navigation
        .tick_with_motion(1_000, None, Some(hint(1_000, 0.0)))
        .unwrap();
    navigation
}

#[test]
fn highway_obd_can_overturn_a_false_stop() {
    let mut navigation = highway_false_stop();
    let stopped = navigation.estimate();
    assert!(navigation.on_vehicle_speed(126.0, 1_100).unwrap());
    assert!((navigation.estimate().speed_mps - 35.0).abs() < 0.1);
    assert_eq!(navigation.estimate().position_m, stopped.position_m);
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        stopped.systematic_drift_m
    );
    assert!(navigation.estimate().position_sigma_m() >= stopped.position_sigma_m());
    assert!(navigation.state.motion_control.is_none());
}

#[test]
fn highway_good_gps_can_overturn_a_false_stop() {
    let mut navigation = highway_false_stop();
    let mut observation = gps(1_100, 50.000_35);
    observation.speed_mps = Some(35.0);
    let result = navigation
        .tick_with_motion(1_100, Some(observation), Some(hint(1_100, 0.0)))
        .unwrap();
    assert!(result.speed_accepted);
    assert!((result.estimate.speed_mps - 35.0).abs() < 0.1);
}

#[test]
fn highway_recovery_still_rejects_outliers_and_suspect_gps() {
    let mut navigation = highway_false_stop();
    for time in [1_100, 1_200, 1_300] {
        let mut predicted = navigation.clone();
        predicted.predict_to(time).unwrap();
        assert!(!navigation.on_vehicle_speed(250.0, time).unwrap());
        assert_eq!(navigation.estimate(), predicted.estimate());
        assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
        assert!(navigation.state.motion_control.is_some());
    }
    let mut observation = gps(1_400, 50.000_35);
    observation.speed_mps = Some(35.0);
    observation.trust = super::super::ObservationTrust::Suspect;
    let result = navigation
        .tick_with_motion(1_400, Some(observation), Some(hint(1_400, 0.0)))
        .unwrap();
    assert!(!result.speed_accepted);
    assert!(result.estimate.speed_mps.abs() < f64::EPSILON);
    assert!(navigation.on_vehicle_speed(126.0, 1_500).unwrap());
}

#[test]
fn highway_recovery_does_not_override_a_real_measured_stop() {
    let mut navigation = highway_false_stop();
    assert!(navigation.on_vehicle_speed(0.0, 1_100).unwrap());
    assert!(navigation.estimate().speed_mps.abs() < f64::EPSILON);
    assert!(navigation.state.motion_control.is_none());
}

#[test]
fn highway_recovery_matches_delayed_gps_replay() {
    let mut timely = highway_false_stop();
    let mut delayed = timely.clone();
    let mut observation = gps(1_100, 50.000_35);
    observation.speed_mps = Some(35.0);
    timely
        .tick_with_motion(1_100, Some(observation), Some(hint(1_100, 0.0)))
        .unwrap();
    delayed
        .tick_with_motion(1_100, None, Some(hint(1_100, 0.0)))
        .unwrap();
    for time in [1_200, 1_300] {
        timely
            .tick_with_motion(time, None, Some(hint(time, 0.0)))
            .unwrap();
        delayed
            .tick_with_motion(time, None, Some(hint(time, 0.0)))
            .unwrap();
    }
    timely
        .tick_with_motion(1_500, None, Some(hint(1_500, 0.0)))
        .unwrap();
    let result = delayed
        .tick_with_motion(1_500, Some(observation), Some(hint(1_500, 0.0)))
        .unwrap();
    assert!(result.speed_accepted);
    assert_eq!(timely.estimate(), delayed.estimate());
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
