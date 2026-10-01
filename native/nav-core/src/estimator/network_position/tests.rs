//! Coarse fixes must earn corrections without becoming precise anchors or speed sensors.
use super::super::regression_tests::{estimator as base_estimator, gps};
use super::*;
use crate::route::RouteGeometry;
use std::sync::Arc;

fn estimator(sigma: f64) -> NavigationEstimator {
    let mut navigation = base_estimator(sigma);
    navigation.set_network_speed_enabled(false);
    navigation
}

fn fix(time: i64, position_m: f64) -> NetworkObservation {
    NetworkObservation {
        point: GeoPoint {
            latitude_deg: 50.0 + position_m / 111_194.926_6,
            longitude_deg: 30.0,
        },
        elapsed_ms: time,
        accuracy_m: 30.0,
    }
}

fn tick(navigation: &mut NavigationEstimator, time: i64, observation: NetworkObservation) {
    navigation
        .tick_with_observations(time, None, None, Some(observation))
        .unwrap();
}

#[test]
fn three_fixes_correct_gradually_without_changing_speed_or_clearing_drift() {
    let mut navigation = estimator(200.0);
    let mut baseline = navigation.clone();
    for time in [1_000, 6_000] {
        tick(
            &mut navigation,
            time,
            fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0),
        );
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
    tick(&mut navigation, 11_000, fix(11_000, 310.0));
    baseline.tick(11_000, None).unwrap();
    let corrected = navigation.estimate();
    let original = baseline.estimate();
    assert!(corrected.position_m > original.position_m + 20.0);
    assert!(corrected.position_m <= original.position_m + MAX_CORRECTION_M);
    assert_eq!(corrected.speed_mps, original.speed_mps);
    assert_eq!(corrected.systematic_drift_m, original.systematic_drift_m);
    assert!(corrected.position_sigma_m() >= 60.0);
}

#[test]
fn cached_alternating_coordinates_and_fast_samples_do_not_build_confirmation() {
    let mut navigation = estimator(200.0);
    let mut baseline = navigation.clone();
    for (time, position) in [
        (1_000, 210.0),
        (2_000, 220.0),
        (6_000, 260.0),
        (11_000, 210.0),
        (16_000, 260.0),
        (21_000, 210.0),
    ] {
        tick(&mut navigation, time, fix(time, position));
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
}

#[test]
fn isolated_jump_breaks_confirmation_and_cannot_move_position() {
    let mut navigation = estimator(200.0);
    let mut baseline = navigation.clone();
    for (time, position) in [
        (1_000, 210.0),
        (6_000, 260.0),
        (11_000, 4000.0),
        (16_000, 360.0),
        (21_000, 410.0),
    ] {
        tick(&mut navigation, time, fix(time, position));
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
    tick(&mut navigation, 26_000, fix(26_000, 460.0));
    baseline.tick(26_000, None).unwrap();
    assert!(navigation.estimate().position_m > baseline.estimate().position_m);
}

#[test]
fn reachable_but_abrupt_residual_jump_requires_new_confirmation() {
    let mut navigation = estimator(200.0);
    for time in [1_000, 6_000, 11_000] {
        tick(
            &mut navigation,
            time,
            fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0),
        );
    }
    let mut baseline = navigation.clone();
    // 250 m in five seconds passes the speed+accuracy gate, but its residual is inconsistent.
    tick(&mut navigation, 16_000, fix(16_000, 560.0));
    baseline.tick(16_000, None).unwrap();
    assert_eq!(navigation.estimate(), baseline.estimate());
}

#[test]
fn stale_future_invalid_coarse_off_route_and_walking_inputs_are_inert() {
    for case in 0..8 {
        let mut navigation = estimator(200.0);
        if case == 7 {
            navigation.mode = TravelMode::Foot;
        }
        let mut baseline = navigation.clone();
        for time in [1_000, 6_000, 11_000] {
            let mut observation = fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0);
            match case {
                0 => observation.elapsed_ms -= 3_000,
                1 => observation.elapsed_ms += 1,
                2 => observation.accuracy_m = 201.0,
                3 => observation.accuracy_m = f64::NAN,
                4 => observation.point.longitude_deg += 0.01,
                5 => observation.point.latitude_deg = 91.0,
                6 => observation.accuracy_m = 0.0,
                _ => (),
            }
            tick(&mut navigation, time, observation);
            baseline.tick(time, None).unwrap();
            assert_eq!(navigation.estimate(), baseline.estimate());
        }
    }
}

#[test]
fn ambiguous_parallel_return_is_not_a_position_anchor() {
    let mut navigation = estimator(200.0);
    navigation.route = Arc::new(
        RouteGeometry::new(vec![
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.01,
                longitude_deg: 30.0,
            },
            GeoPoint {
                latitude_deg: 50.01,
                longitude_deg: 30.0002,
            },
            GeoPoint {
                latitude_deg: 50.0,
                longitude_deg: 30.0002,
            },
        ])
        .unwrap(),
    );
    let mut baseline = navigation.clone();
    for time in [1_000, 6_000, 11_000] {
        tick(
            &mut navigation,
            time,
            fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0),
        );
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
}

#[test]
fn delayed_gps_replays_and_vetoes_a_coarse_correction() {
    let mut timely = estimator(200.0);
    let mut delayed = timely.clone();
    for time in [1_000, 6_000] {
        let observation = fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0);
        tick(&mut timely, time, observation);
        tick(&mut delayed, time, observation);
    }
    let observation = gps(8_500, 50.0008);
    timely.tick(9_000, Some(observation)).unwrap();
    delayed.tick(9_000, None).unwrap();
    tick(&mut timely, 11_000, fix(11_000, 310.0));
    tick(&mut delayed, 11_000, fix(11_000, 310.0));
    timely.tick(12_000, None).unwrap();
    delayed.tick(12_000, Some(observation)).unwrap();
    assert_eq!(timely.estimate(), delayed.estimate());
}

#[test]
fn delayed_gps_at_the_same_timestamp_precedes_coarse_correction() {
    let mut timely = estimator(200.0);
    let mut delayed = timely.clone();
    for time in [1_000, 6_000] {
        let observation = fix(time, 200.0 + milliseconds_to_seconds(time) * 10.0);
        tick(&mut timely, time, observation);
        tick(&mut delayed, time, observation);
    }
    let observation = gps(11_000, 50.001);
    timely
        .tick_with_observations(11_000, Some(observation), None, Some(fix(11_000, 310.0)))
        .unwrap();
    tick(&mut delayed, 11_000, fix(11_000, 310.0));
    timely.tick(12_000, None).unwrap();
    delayed.tick(12_000, Some(observation)).unwrap();
    assert_eq!(timely.estimate(), delayed.estimate());
}

#[test]
fn persistent_large_discrepancy_is_not_force_accepted() {
    let mut navigation = estimator(200.0);
    let mut baseline = navigation.clone();
    for time in [1_000, 6_000, 11_000, 16_000] {
        tick(
            &mut navigation,
            time,
            fix(time, 1000.0 + milliseconds_to_seconds(time) * 10.0),
        );
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
}

#[test]
fn gps_at_a_pruned_event_boundary_cannot_reuse_post_correction_state() {
    let mut navigation = estimator(200.0);
    tick(&mut navigation, 1_000, fix(1_000, 210.0));
    // Simulate the bounded history dropping the checkpoint preceding this event.
    navigation.history.pop_front();
    let mut baseline = navigation.clone();
    baseline.tick(2_000, None).unwrap();
    let result = navigation.tick(2_000, Some(gps(1_000, 50.001))).unwrap();
    assert!(!result.position_accepted && !result.speed_accepted);
    assert_eq!(navigation.estimate(), baseline.estimate());
}

#[test]
fn reroute_discards_confirmation_and_rejects_old_route_fixes() {
    let mut navigation = estimator(200.0);
    tick(&mut navigation, 1_000, fix(1_000, 210.0));
    tick(&mut navigation, 6_000, fix(6_000, 260.0));
    navigation
        .replace_route(navigation.route.clone(), 60.0, 200.0)
        .unwrap();
    let mut baseline = navigation.clone();
    for (time, observation) in [
        (6_500, fix(5_500, 270.0)),
        (11_000, fix(11_000, 310.0)),
        (16_000, fix(16_000, 360.0)),
    ] {
        tick(&mut navigation, time, observation);
        baseline.tick(time, None).unwrap();
        assert_eq!(navigation.estimate(), baseline.estimate());
    }
}

#[test]
fn coarse_filter_gates_and_caps_without_overconfidence() {
    let mut filter = crate::RouteFilter::new(0.0, 10.0, 100.0, 2.0, 50.0).unwrap();
    let original = filter.estimate();
    assert!(
        !filter
            .update_coarse_position(1000.0, 60.0, POSITION_GATE, 50.0)
            .unwrap()
    );
    assert_eq!(filter.estimate(), original);
    assert!(
        filter
            .update_coarse_position(f64::NAN, 60.0, POSITION_GATE, 50.0)
            .is_err()
    );
    assert_eq!(filter.estimate(), original);
    for _ in 0..100 {
        let previous = filter.estimate();
        assert!(
            filter
                .update_coarse_position(200.0, 60.0, POSITION_GATE, 50.0)
                .unwrap()
        );
        assert!(filter.estimate().position_m - previous.position_m <= 50.0);
        assert!(filter.estimate().position_sigma_m() >= 60.0);
    }
    assert_eq!(filter.estimate().speed_mps, original.speed_mps);
    assert_eq!(
        filter.estimate().systematic_drift_m,
        original.systematic_drift_m
    );
}
