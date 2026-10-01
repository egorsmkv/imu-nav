//! Turn matching is a weak one-shot correction, not a forced route snap.
use super::super::{GpsObservation, InitialEstimate, ObservationTrust};
use super::*;
use crate::route::{GeoPoint, RouteGeometry};
use std::sync::Arc;

fn point(east_m: f64, north_m: f64) -> GeoPoint {
    GeoPoint {
        latitude_deg: 50.0 + north_m / 111_194.926_6,
        longitude_deg: 30.0 + east_m / (111_194.926_6 * 50_f64.to_radians().cos()),
    }
}

fn navigation(points: Vec<GeoPoint>) -> NavigationEstimator {
    NavigationEstimator::new(
        Arc::new(RouteGeometry::new(points).unwrap()),
        InitialEstimate {
            position_m: 400.0,
            speed_mps: 10.0,
            position_sigma_m: 100.0,
            speed_sigma_mps: 2.0,
            systematic_drift_m: 50.0,
        },
        TravelMode::Car,
        0,
    )
    .unwrap()
}

fn corner() -> NavigationEstimator {
    navigation(vec![
        point(0.0, 0.0),
        point(0.0, 500.0),
        point(1000.0, 500.0),
    ])
}

fn turn() -> TurnObservation {
    TurnObservation {
        start_ms: 1_000,
        end_ms: 5_000,
        angle_deg: 90.0,
    }
}

#[test]
fn distinct_turn_corrects_once_without_changing_speed_or_resetting_drift() {
    let mut navigation = corner();
    let mut baseline = navigation.clone();
    baseline.tick(6_000, None).unwrap();
    let result = navigation
        .tick_with_turn(6_000, None, None, None, Some(turn()))
        .unwrap()
        .estimate;
    assert!(result.position_m > baseline.estimate().position_m + 10.0);
    assert!(result.position_m - baseline.estimate().position_m <= MAX_CORRECTION_M);
    assert_eq!(result.speed_mps, baseline.estimate().speed_mps);
    assert_eq!(
        result.systematic_drift_m,
        baseline.estimate().systematic_drift_m
    );
    assert!(result.position_sigma_m() >= MIN_SIGMA_M);
    let mut once = navigation.clone();
    once.tick(6_500, None).unwrap();
    navigation
        .tick_with_turn(6_500, None, None, None, Some(turn()))
        .unwrap();
    assert_eq!(once.estimate(), navigation.estimate());
}

#[test]
fn a_new_rotation_cannot_reuse_the_same_landmark_after_cooldown() {
    let mut navigation = corner();
    navigation
        .tick_with_turn(6_000, None, None, None, Some(turn()))
        .unwrap();
    for time in (6_500..=20_500).step_by(500) {
        assert!(navigation.on_vehicle_speed(36.0, time).unwrap());
    }
    let mut baseline = navigation.clone();
    baseline.tick(21_000, None).unwrap();
    let repeat = TurnObservation {
        start_ms: 16_000,
        end_ms: 20_000,
        angle_deg: 90.0,
    };
    navigation
        .tick_with_turn(21_000, None, None, None, Some(repeat))
        .unwrap();
    assert_eq!(baseline.estimate(), navigation.estimate());
}

#[test]
fn wrong_sign_angle_stale_future_short_and_old_route_turns_are_inert() {
    for observation in [
        TurnObservation {
            angle_deg: -90.0,
            ..turn()
        },
        TurnObservation {
            angle_deg: 50.0,
            ..turn()
        },
        TurnObservation {
            angle_deg: f64::NAN,
            ..turn()
        },
        TurnObservation {
            start_ms: 0,
            end_ms: 3_000,
            ..turn()
        },
        TurnObservation {
            start_ms: 3_000,
            end_ms: 7_000,
            ..turn()
        },
        TurnObservation {
            start_ms: 4_000,
            ..turn()
        },
        TurnObservation {
            start_ms: -100,
            ..turn()
        },
    ] {
        let mut navigation = corner();
        let mut baseline = navigation.clone();
        baseline.tick(6_000, None).unwrap();
        navigation
            .tick_with_turn(6_000, None, None, None, Some(observation))
            .unwrap();
        assert_eq!(baseline.estimate(), navigation.estimate());
    }
}

#[test]
fn ambiguous_similar_turns_and_close_opposite_turns_are_rejected() {
    for (east, north) in [(100.0, 0.0), (60.0, 1000.0)] {
        let mut navigation = navigation(vec![
            point(0.0, 0.0),
            point(0.0, 500.0),
            point(east, 500.0),
            point(east, north),
        ]);
        assert_eq!(navigation.route.turns().len(), 2);
        let mut baseline = navigation.clone();
        baseline.tick(6_000, None).unwrap();
        navigation
            .tick_with_turn(6_000, None, None, None, Some(turn()))
            .unwrap();
        assert_eq!(baseline.estimate(), navigation.estimate());
    }
}

#[test]
fn stationary_walking_uncertain_and_far_predictions_do_not_match() {
    for (mode, position, speed, sigma) in [
        (TravelMode::Foot, 400.0, 10.0, 100.0),
        (TravelMode::Car, 400.0, 0.0, 100.0),
        (TravelMode::Car, 400.0, 30.0, 100.0),
        (TravelMode::Car, 400.0, 10.0, 500.0),
        (TravelMode::Car, 0.0, 10.0, 100.0),
    ] {
        let route = corner().route;
        let mut navigation = NavigationEstimator::new(
            route,
            InitialEstimate {
                position_m: position,
                speed_mps: speed,
                position_sigma_m: sigma,
                speed_sigma_mps: 2.0,
                systematic_drift_m: 0.0,
            },
            mode,
            0,
        )
        .unwrap();
        let mut baseline = navigation.clone();
        baseline.tick(6_000, None).unwrap();
        navigation
            .tick_with_turn(6_000, None, None, None, Some(turn()))
            .unwrap();
        assert_eq!(baseline.estimate(), navigation.estimate());
    }
}

#[test]
fn delayed_gps_at_same_time_overrides_turn_and_replays_exactly() {
    let mut timely = corner();
    let mut delayed = timely.clone();
    timely.tick(3_000, None).unwrap();
    delayed.tick(3_000, None).unwrap();
    let gps = GpsObservation {
        point: point(0.0, 460.0),
        elapsed_ms: 6_000,
        position_accuracy_m: Some(5.0),
        speed_mps: Some(10.0),
        speed_accuracy_mps: Some(0.5),
        trust: ObservationTrust::Good,
    };
    let mut baseline = timely.clone();
    baseline.tick(6_000, Some(gps)).unwrap();
    timely
        .tick_with_turn(6_000, Some(gps), None, None, Some(turn()))
        .unwrap();
    assert_eq!(timely.estimate(), baseline.estimate());
    delayed
        .tick_with_turn(6_000, None, None, None, Some(turn()))
        .unwrap();
    timely.tick(7_000, None).unwrap();
    delayed.tick(7_000, Some(gps)).unwrap();
    assert_eq!(timely.estimate(), delayed.estimate());
}

#[test]
fn reroute_discards_a_turn_started_on_the_previous_route() {
    let mut navigation = corner();
    navigation.tick(3_000, None).unwrap();
    navigation
        .replace_route(navigation.route.clone(), 430.0, 100.0)
        .unwrap();
    let mut baseline = navigation.clone();
    baseline.tick(6_000, None).unwrap();
    navigation
        .tick_with_turn(6_000, None, None, None, Some(turn()))
        .unwrap();
    assert_eq!(baseline.estimate(), navigation.estimate());
}

#[test]
fn dense_straight_segments_do_not_create_extra_turn_landmarks() {
    let mut points: Vec<_> = (0..=500)
        .step_by(5)
        .map(|north| point(0.0, f64::from(north)))
        .collect();
    points.extend(
        (5..=1000)
            .step_by(5)
            .map(|east| point(f64::from(east), 500.0)),
    );
    let geometry = RouteGeometry::new(points).unwrap();
    assert_eq!(geometry.turns().len(), 1);
    assert!((geometry.turns()[0].position_m - 500.0).abs() < 6.0);
    assert!((geometry.turns()[0].angle_deg - 90.0).abs() < 1.0);
}
