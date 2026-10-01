//! Changes away from an old prior need fresh coherent fits, not a forced return to that prior.
use super::*;
use crate::estimator::regression_tests::{estimator, gps};
use crate::estimator::{MotionObservation, NavigationEstimator, NetworkObservation};
use crate::route::GeoPoint;

fn sample(time: i64, position_m: f64) -> Candidate {
    Candidate {
        first_ms: 0,
        last_ms: time,
        position_m,
        accuracy_m: 30.0,
        residual_m: 0.0,
        count: 10,
    }
}

fn fit(speed_mps: f64) -> SpeedEstimate {
    SpeedEstimate {
        speed_mps,
        sigma_mps: 2.0,
        samples: 4,
        span_s: 30.0,
    }
}

fn reserved(batch: &mut SpeedBatch, time: i64, position_m: f64, model: f64) -> NetworkUse {
    let outcome = batch.select(sample(time, position_m), model);
    assert!(matches!(
        batch.select(sample(time + 5_000, position_m), model),
        NetworkUse::Position
    ));
    outcome
}

#[test]
fn worse_prior_does_not_block_change_detection_but_a_full_fit_is_still_required() {
    let mut batch = SpeedBatch::new();
    batch.accepted(10.0, fit(12.0), 12.0);
    for (time, position) in [(0, 0.0), (10_000, 220.0)] {
        assert!(matches!(
            reserved(&mut batch, time, position, 12.0),
            NetworkUse::Reserved
        ));
        assert!(!batch.relearning());
    }
    assert!(matches!(
        reserved(&mut batch, 20_000, 440.0, 12.0),
        NetworkUse::Reserved
    ));
    assert!(batch.relearning());
    let NetworkUse::Speed(estimate) = reserved(&mut batch, 30_000, 660.0, 12.0) else {
        panic!("a complete coherent window must fit");
    };
    assert!((estimate.speed_mps - 22.0).abs() < 1e-8);
    assert_eq!(batch.prior_speed_mps, Some(10.0));
    batch.accepted(12.0, estimate, 16.0);
    assert!(batch.relearning());
    // A still-lagging model must not invalidate evidence that already agrees with the new trend.
    reserved(&mut batch, 40_000, 880.0, 16.0);
    reserved(&mut batch, 50_000, 1100.0, 16.0);
    assert_eq!(batch.previous_change_departure, 0.0);
    batch.accepted(16.0, estimate, 20.5);
    assert!(!batch.relearning());
}

#[test]
fn isolated_or_alternating_tower_errors_do_not_enable_faster_updates() {
    for positions in [[0.0, 350.0, 550.0, 750.0], [0.0, 350.0, 400.0, 750.0]] {
        let mut batch = SpeedBatch::new();
        batch.accepted(10.0, fit(20.0), 20.0);
        for (index, position) in positions.into_iter().enumerate() {
            reserved(
                &mut batch,
                i64::try_from(index).unwrap() * 10_000,
                position,
                20.0,
            );
            assert!(!batch.relearning());
        }
    }
}

#[test]
fn mixed_manoeuvre_window_is_discarded_and_position_gets_the_next_fixes() {
    let mut batch = SpeedBatch::new();
    batch.accepted(10.0, fit(12.0), 12.0);
    for (index, position) in [0.0, 200.0, 450.0, 750.0].into_iter().enumerate() {
        assert!(matches!(
            reserved(
                &mut batch,
                i64::try_from(index).unwrap() * 10_000,
                position,
                12.0
            ),
            NetworkUse::Reserved
        ));
    }
    assert!(batch.relearning());
    assert_eq!(batch.count, 0);
    assert!(batch.samples.iter().all(Option::is_none));
    assert!(matches!(
        batch.select(sample(40_000, 1050.0), 12.0),
        NetworkUse::Position
    ));
    assert!(matches!(
        batch.select(sample(45_000, 1200.0), 12.0),
        NetworkUse::Reserved
    ));
    assert_eq!(batch.count, 1);
    batch.clear();
    assert!(!batch.relearning());
    assert!(batch.reference_speed_mps.is_none());
}

#[test]
fn slow_moving_fits_can_extend_without_reusing_any_position_samples() {
    let mut batch = SpeedBatch::new();
    for index in 0..=8 {
        let time = index * 5_000;
        let position = if index % 2 == 0 {
            milliseconds_to_seconds(time) * 9.0
        } else {
            -10_000.0
        };
        let mut observation = sample(time, position);
        observation.accuracy_m = 40.0;
        let outcome = batch.select(observation, 12.0);
        if index % 2 == 1 {
            assert!(matches!(outcome, NetworkUse::Position));
        } else if index < 8 {
            assert!(matches!(outcome, NetworkUse::Reserved));
        } else {
            let NetworkUse::Speed(estimate) = outcome else {
                panic!("the longer window resolves slow movement");
            };
            assert!((estimate.speed_mps - 9.0).abs() < 1e-8);
            assert_eq!(estimate.samples, 5);
            assert_eq!(estimate.span_s, 40.0);
            assert!(estimate.speed_mps - 3.0 * estimate.sigma_mps > 1.0);
            assert_eq!(batch.count, 0);
        }
    }
}

#[test]
fn extended_batches_are_bounded_by_sample_count_and_span() {
    for times in [
        vec![0, 10_000, 20_000, 30_000, 40_000, 50_000, 60_000],
        vec![0, 15_000, 35_000, 50_000, 70_000],
    ] {
        let mut batch = SpeedBatch::new();
        for &time in &times {
            let mut observation = sample(time, milliseconds_to_seconds(time) * 7.1);
            observation.accuracy_m = 59.0;
            let outcome = batch.select(observation, 10.0);
            if time == 60_000 {
                let NetworkUse::Speed(estimate) = outcome else {
                    panic!("seven samples resolve this slow window");
                };
                assert_eq!(estimate.samples, 7);
                assert_eq!(estimate.span_s, 60.0);
            } else {
                assert!(matches!(outcome, NetworkUse::Reserved));
            }
            assert!(matches!(
                batch.select(sample(time + 5_000, -10_000.0), 10.0),
                NetworkUse::Position
            ));
        }
        assert_eq!(batch.count, 0);
        assert!(batch.samples.iter().all(Option::is_none));
    }
}

fn fix(time: i64) -> NetworkObservation {
    let seconds = milliseconds_to_seconds(time);
    let position = 100.0 + seconds.min(191.0) * 12.0 + (seconds - 191.0).max(0.0) * 22.0;
    NetworkObservation {
        point: GeoPoint {
            latitude_deg: 50.0 + position / 111_194.926_6,
            longitude_deg: 30.0,
        },
        elapsed_ms: time,
        accuracy_m: 30.0,
    }
}

fn feed(navigation: &mut NavigationEstimator, time: i64) {
    navigation
        .tick_with_observations(time, None, None, Some(fix(time)))
        .unwrap();
}

fn accelerating_navigation() -> NavigationEstimator {
    let mut navigation = estimator(100.0);
    navigation.set_network_speed_enabled(true);
    for time in (1_000..=226_000).step_by(5_000) {
        feed(&mut navigation, time);
    }
    assert!(navigation.state.network_evidence.speed.relearning());
    navigation
}

#[test]
fn coherent_relearning_is_bounded_and_does_not_correct_position_or_reset_drift() {
    let mut navigation = accelerating_navigation();
    let mut predicted = navigation.clone();
    predicted.tick(231_000, None).unwrap();
    feed(&mut navigation, 231_000);
    let change = navigation.estimate().speed_mps - predicted.estimate().speed_mps;
    assert!(change > 2.0 && change <= 4.0);
    assert_eq!(
        navigation.estimate().position_m,
        predicted.estimate().position_m
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        predicted.estimate().systematic_drift_m
    );
    assert!(navigation.estimate().speed_sigma_mps() >= 2.0);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
}

#[test]
fn stop_and_fresh_obd_clear_relearning_before_a_stronger_fit() {
    let mut obd = accelerating_navigation();
    assert!(obd.on_vehicle_speed(79.2, 230_000).unwrap());
    let measured = obd.estimate().speed_mps;
    feed(&mut obd, 231_000);
    assert_eq!(obd.estimate().speed_mps, measured);
    assert!(!obd.state.network_evidence.speed.relearning());
    let mut stopped = accelerating_navigation();
    stopped
        .tick_with_observations(
            231_000,
            None,
            Some(MotionObservation {
                factor: 0.0,
                cruise_speed_mps: 12.0,
                valid_until_ms: 233_000,
                network_moving: false,
            }),
            Some(fix(231_000)),
        )
        .unwrap();
    assert_eq!(stopped.estimate().speed_mps, 0.0);
    assert!(!stopped.state.network_evidence.speed.relearning());
}

#[test]
fn delayed_gps_replays_change_detection_and_stronger_fit_priority() {
    for time in [230_000, 231_000] {
        let mut timely = accelerating_navigation();
        let mut delayed = timely.clone();
        let mut observation = gps(time, fix(time).point.latitude_deg);
        observation.speed_mps = Some(22.0);
        if time < 231_000 {
            timely.tick(time, Some(observation)).unwrap();
            delayed.tick(time, None).unwrap();
            feed(&mut timely, 231_000);
        } else {
            timely
                .tick_with_observations(time, Some(observation), None, Some(fix(time)))
                .unwrap();
        }
        feed(&mut delayed, 231_000);
        timely.tick(232_000, None).unwrap();
        delayed.tick(232_000, Some(observation)).unwrap();
        assert_eq!(timely.estimate(), delayed.estimate());
        for later in (236_000..=306_000).step_by(5_000) {
            feed(&mut timely, later);
            feed(&mut delayed, later);
            assert_eq!(timely.estimate(), delayed.estimate());
        }
    }
}
