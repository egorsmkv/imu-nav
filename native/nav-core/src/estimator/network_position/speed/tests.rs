//! Independent batches, conservative covariance and replay/measurement priority regressions.
use super::*;
use crate::RouteFilter;
use crate::estimator::regression_tests::{estimator as base_estimator, gps};
use crate::estimator::{MotionObservation, NavigationEstimator, NetworkObservation};
use crate::route::GeoPoint;

fn estimator(sigma: f64) -> NavigationEstimator {
    let mut navigation = base_estimator(sigma);
    navigation.set_network_speed_enabled(true);
    navigation
}

fn candidate(time: i64, position_m: f64) -> Candidate {
    Candidate {
        first_ms: 0,
        last_ms: time,
        position_m,
        accuracy_m: 30.0,
        residual_m: 0.0,
        count: 10,
    }
}

fn fix(time: i64, speed: f64) -> NetworkObservation {
    NetworkObservation {
        point: GeoPoint {
            latitude_deg: 50.0 + (100.0 + milliseconds_to_seconds(time) * speed) / 111_194.926_6,
            longitude_deg: 30.0,
        },
        elapsed_ms: time,
        accuracy_m: 30.0,
    }
}

fn feed(navigation: &mut NavigationEstimator, time: i64) {
    navigation
        .tick_with_observations(time, None, None, Some(fix(time, 12.0)))
        .unwrap();
}

#[test]
fn alternating_fixes_and_complete_nonoverlapping_batches_only() {
    let mut batch = SpeedBatch::new();
    for index in 0..23 {
        let time = index * 5_000;
        // Position-only slots must not contaminate the speed fit.
        let position = if index % 2 == 0 {
            milliseconds_to_seconds(time) * 12.0 + 200.0
        } else {
            -1_000.0
        };
        match batch.select(candidate(time, position), 12.0) {
            NetworkUse::Position => assert_eq!(index % 2, 1),
            NetworkUse::Reserved => assert!(index % 2 == 0 && index % 8 != 6),
            NetworkUse::Speed(estimate) => {
                assert_eq!(index % 8, 6);
                assert!((estimate.speed_mps - 12.0).abs() < 1e-8);
                assert!(estimate.sigma_mps >= 2.0);
                assert_eq!(estimate.samples, 4);
                assert_eq!(estimate.span_s, 30.0);
            }
            NetworkUse::RestorePrior(_) => panic!("no prior has been learned"),
        }
    }
}

#[test]
fn stationary_reverse_jump_acceleration_and_poor_accuracy_batches_are_not_speed() {
    for positions in [
        [0.0, 0.0, 0.0, 0.0],
        [400.0, 300.0, 200.0, 100.0],
        [0.0, 120.0, 400.0, 520.0],
        [0.0, 50.0, 170.0, 360.0],
        [0.0, 120.0, 120.0, 120.0],
        [0.0, 0.0, 120.0, 240.0],
        [0.0, 60.0, 120.0, 180.0],
        [0.0, 500.0, 1000.0, 1500.0],
    ] {
        let mut batch = SpeedBatch::new();
        for index in 0..7 {
            assert!(!matches!(
                batch.select(
                    candidate(
                        index * 5_000,
                        positions[usize::try_from(index / 2).unwrap()]
                    ),
                    12.0
                ),
                NetworkUse::Speed(_)
            ));
        }
    }
    let mut batch = SpeedBatch::new();
    for index in 0..7 {
        let mut sample = candidate(
            index * 5_000,
            f64::from(i32::try_from(index).unwrap()) * 60.0,
        );
        sample.accuracy_m = 200.0;
        assert!(!matches!(batch.select(sample, 12.0), NetworkUse::Speed(_)));
    }
}

#[test]
fn coarse_speed_never_corrects_position_clears_drift_or_claims_precision() {
    let mut filter = RouteFilter::new(100.0, 10.0, 50.0, 6.0, 70.0).unwrap();
    let original = filter.estimate();
    assert!(filter.update_coarse_speed(20.0, 2.0, 9.0, 2.0).unwrap());
    assert_eq!(filter.estimate().speed_mps, 12.0);
    for _ in 0..100 {
        assert!(filter.update_coarse_speed(20.0, 2.0, 9.0, 2.0).unwrap());
        let state = filter.estimate();
        assert_eq!(state.position_m, original.position_m);
        assert_eq!(state.systematic_drift_m, 70.0);
        assert_eq!(state.covariance.position, original.covariance.position);
        assert_eq!(state.covariance.position_speed, 0.0);
        assert!(state.covariance.speed >= 4.0);
    }
    let unchanged = filter.estimate();
    assert!(!filter.update_coarse_speed(200.0, 2.0, 9.0, 2.0).unwrap());
    assert_eq!(filter.estimate(), unchanged);
    for (speed, sigma, gate, cap) in [
        (f64::NAN, 2.0, 9.0, 2.0),
        (12.0, -1.0, 9.0, 2.0),
        (12.0, 2.0, 0.0, 2.0),
        (12.0, 2.0, 9.0, -1.0),
    ] {
        assert!(filter.update_coarse_speed(speed, sigma, gate, cap).is_err());
        assert_eq!(filter.estimate(), unchanged);
    }
}

#[test]
fn speed_fix_has_no_position_update_and_does_not_refresh_measured_speed() {
    let mut navigation = estimator(100.0);
    for index in 0..7 {
        let time = 1_000 + index * 5_000;
        let mut predicted = navigation.clone();
        predicted.tick(time, None).unwrap();
        feed(&mut navigation, time);
        if index % 2 == 0 {
            assert_eq!(
                navigation.estimate().position_m,
                predicted.estimate().position_m
            );
            assert_eq!(
                navigation.estimate().systematic_drift_m,
                predicted.estimate().systematic_drift_m
            );
        }
        if index < 6 {
            assert_eq!(navigation.estimate().speed_mps, 10.0);
        }
    }
    assert!(navigation.estimate().speed_mps > 10.5);
    assert!(navigation.estimate().speed_mps < 12.0);
    assert!(navigation.estimate().speed_sigma_mps() >= 2.0);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
}

#[test]
fn intervening_stop_measurement_gap_and_reroute_discard_partial_window() {
    for scenario in 0..4 {
        let mut navigation = estimator(100.0);
        for time in [1_000, 6_000, 11_000, 16_000, 21_000] {
            feed(&mut navigation, time);
        }
        match scenario {
            0 => {
                navigation
                    .tick_with_motion(
                        22_000,
                        None,
                        Some(MotionObservation {
                            factor: 0.0,
                            cruise_speed_mps: 10.0,
                            valid_until_ms: 24_000,
                            network_moving: false,
                        }),
                    )
                    .unwrap();
                navigation.tick(25_000, None).unwrap();
            }
            1 => {
                assert!(navigation.on_vehicle_speed(36.0, 22_000).unwrap());
            }
            2 => {
                navigation
                    .replace_route(
                        navigation.route.clone(),
                        navigation.estimate().position_m,
                        100.0,
                    )
                    .unwrap();
            }
            _ => {
                navigation.tick(40_000, None).unwrap();
            }
        }
        let speed = navigation.estimate().speed_mps;
        for time in if scenario == 3 {
            [41_000, 46_000]
        } else {
            [26_000, 31_000]
        } {
            feed(&mut navigation, time);
            assert_eq!(navigation.estimate().speed_mps, speed);
        }
    }
}

#[test]
fn fresh_vehicle_speed_wins_over_a_completing_batch() {
    let mut navigation = estimator(100.0);
    for time in [1_000, 6_000, 11_000, 16_000, 21_000, 26_000] {
        feed(&mut navigation, time);
    }
    assert!(navigation.on_vehicle_speed(36.0, 30_000).unwrap());
    let speed = navigation.estimate().speed_mps;
    feed(&mut navigation, 31_000);
    assert_eq!(navigation.estimate().speed_mps, speed);
}

#[test]
fn a_current_stop_overrides_a_completing_speed_batch() {
    let mut navigation = estimator(100.0);
    for time in [1_000, 6_000, 11_000, 16_000, 21_000, 26_000] {
        feed(&mut navigation, time);
    }
    navigation
        .tick_with_observations(
            31_000,
            None,
            Some(MotionObservation {
                factor: 0.0,
                cruise_speed_mps: 10.0,
                valid_until_ms: 33_000,
                network_moving: false,
            }),
            Some(fix(31_000, 12.0)),
        )
        .unwrap();
    assert_eq!(navigation.estimate().speed_mps, 0.0);
}

#[test]
fn walking_and_cached_fixes_cannot_learn_speed_even_when_opted_in() {
    for walking in [false, true] {
        let mut navigation = estimator(100.0);
        if walking {
            navigation.mode = crate::estimator::TravelMode::Foot;
        }
        for time in (1_000..200_000).step_by(5_000) {
            let mut observation = fix(if walking { time } else { 1_000 }, 12.0);
            observation.elapsed_ms = time;
            navigation
                .tick_with_observations(time, None, None, Some(observation))
                .unwrap();
            assert_eq!(navigation.estimate().speed_mps, 10.0);
        }
    }
}

#[test]
fn delayed_gps_replays_batch_partition_and_same_time_priority() {
    for gps_time in [29_000, 31_000] {
        let mut timely = estimator(100.0);
        for time in [1_000, 6_000, 11_000, 16_000, 21_000, 26_000] {
            feed(&mut timely, time);
        }
        let mut delayed = timely.clone();
        let observation = gps(gps_time, fix(gps_time, 12.0).point.latitude_deg);
        if gps_time < 31_000 {
            timely.tick(gps_time, Some(observation)).unwrap();
            delayed.tick(gps_time, None).unwrap();
            feed(&mut timely, 31_000);
        } else {
            timely
                .tick_with_observations(31_000, Some(observation), None, Some(fix(31_000, 12.0)))
                .unwrap();
        }
        feed(&mut delayed, 31_000);
        timely.tick(32_000, None).unwrap();
        delayed.tick(32_000, Some(observation)).unwrap();
        assert_eq!(timely.estimate(), delayed.estimate());
        for time in [
            36_000, 41_000, 46_000, 51_000, 56_000, 61_000, 66_000, 71_000,
        ] {
            feed(&mut timely, time);
            feed(&mut delayed, time);
            assert_eq!(timely.estimate(), delayed.estimate());
        }
    }
}
