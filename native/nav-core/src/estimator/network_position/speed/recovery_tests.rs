//! Retracting cell learning must be conservative, uncertainty-preserving, and replayable.
use super::*;
use crate::estimator::regression_tests::{estimator, gps};
use crate::estimator::{NavigationEstimator, NetworkObservation};
use crate::route::GeoPoint;

fn fit(speed_mps: f64) -> SpeedEstimate {
    SpeedEstimate {
        speed_mps,
        sigma_mps: 2.0,
        samples: 4,
        span_s: 30.0,
    }
}

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

/// Only every other fix belongs to speed; adversarial position slots must not affect recovery.
fn select_reserved(
    batch: &mut SpeedBatch,
    time: i64,
    position_m: f64,
    speed_mps: f64,
) -> NetworkUse {
    let outcome = batch.select(sample(time, position_m), speed_mps);
    if !matches!(outcome, NetworkUse::RestorePrior(_)) {
        assert!(matches!(
            batch.select(sample(time + 5_000, -10_000.0), speed_mps),
            NetworkUse::Position
        ));
    }
    outcome
}

#[test]
fn two_consistent_departures_restore_only_an_accepted_prior() {
    let mut batch = SpeedBatch::new();
    batch.accepted(15.0, fit(20.0), 20.0);
    batch.accepted(18.0, fit(20.0), 20.0); // Later learning must not replace the original prior.
    assert!(matches!(
        select_reserved(&mut batch, 0, 0.0, 20.0),
        NetworkUse::Reserved
    ));
    assert!(matches!(
        select_reserved(&mut batch, 10_000, 120.0, 20.0),
        NetworkUse::Reserved
    ));
    assert!(matches!(
        batch.select(sample(20_000, 240.0), 20.0),
        NetworkUse::RestorePrior(15.0)
    ));
    assert!(batch.prior_speed_mps.is_none());
    // The triggering sample is never also a position fix; only subsequent fixes get full cadence.
    for time in [25_000, 30_000, 35_000, 40_000, 45_000] {
        assert!(matches!(
            batch.select(sample(time, 240.0), 15.0),
            NetworkUse::Position
        ));
    }
    assert!(matches!(
        batch.select(sample(50_000, 600.0), 15.0),
        NetworkUse::Reserved
    ));
    assert_eq!(batch.count, 1); // No pre-recovery sample survives to shorten a new fit.
}

#[test]
fn isolated_steps_noise_and_a_worse_prior_do_not_restore_speed() {
    for (prior, model, positions) in [
        (Some(15.0), 20.0, [0.0, 120.0, 320.0, 520.0]), // One tower step.
        (Some(15.0), 20.0, [0.0, 120.0, 400.0, 520.0]), // Alternating error.
        (Some(10.0), 12.0, [0.0, 200.0, 400.0, 600.0]), // The saved prior is worse.
        (Some(0.0), 20.0, [0.0, 80.0, 160.0, 240.0]),   // Startup zero is not evidence of a stop.
        (None, 20.0, [0.0, 120.0, 240.0, 360.0]),       // No accepted cell learning to retract.
    ] {
        let mut batch = SpeedBatch::new();
        if let Some(speed) = prior {
            batch.accepted(speed, fit(model), model);
        }
        for (index, position) in positions.into_iter().enumerate() {
            let outcome = select_reserved(
                &mut batch,
                i64::try_from(index).unwrap() * 10_000,
                position,
                model,
            );
            assert!(!matches!(outcome, NetworkUse::RestorePrior(_)));
        }
    }
    let mut batch = SpeedBatch::new();
    batch.accepted(15.0, fit(20.0), 20.0);
    for index in 0..7 {
        let mut observation = sample(
            index * 5_000,
            f64::from(i32::try_from(index).unwrap()) * 60.0,
        );
        observation.accuracy_m = 100.0;
        assert!(!matches!(
            batch.select(observation, 20.0),
            NetworkUse::RestorePrior(_)
        ));
    }
}

#[test]
fn an_accepted_fit_or_evidence_reset_breaks_departure_confirmation() {
    for clear in [false, true] {
        let mut batch = SpeedBatch::new();
        batch.accepted(15.0, fit(20.0), 20.0);
        select_reserved(&mut batch, 0, 0.0, 20.0);
        select_reserved(&mut batch, 10_000, 120.0, 20.0);
        if clear {
            batch.clear();
        } else {
            batch.accepted(20.0, fit(20.0), 20.0);
        }
        assert!(!matches!(
            batch.select(sample(20_000, 240.0), 20.0),
            NetworkUse::RestorePrior(_)
        ));
    }
}

fn fix(time: i64) -> NetworkObservation {
    let seconds = milliseconds_to_seconds(time);
    let distance = 100.0 + seconds.min(191.0) * 20.0 + (seconds - 191.0).max(0.0) * 12.0;
    NetworkObservation {
        point: GeoPoint {
            latitude_deg: 50.0 + distance / 111_194.926_6,
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

fn learned_navigation() -> NavigationEstimator {
    let mut navigation = estimator(100.0);
    navigation.state.filter.set_speed_prior(15.0, 6.0).unwrap();
    navigation.set_network_speed_enabled(true); // Checkpoint the initial model before observations.
    for time in (1_000..=206_000).step_by(5_000) {
        feed(&mut navigation, time);
    }
    assert!(navigation.estimate().speed_mps > 19.0);
    navigation
}

#[test]
fn recovery_does_not_move_position_reduce_uncertainty_or_refresh_measurement_age() {
    let mut navigation = learned_navigation();
    let mut predicted = navigation.clone();
    predicted.tick(211_000, None).unwrap();
    feed(&mut navigation, 211_000);
    assert_eq!(navigation.estimate().speed_mps, 15.0);
    assert_eq!(
        navigation.estimate().position_m,
        predicted.estimate().position_m
    );
    assert_eq!(
        navigation.estimate().systematic_drift_m,
        predicted.estimate().systematic_drift_m
    );
    assert_eq!(
        navigation.estimate().covariance.position,
        predicted.estimate().covariance.position
    );
    assert!(navigation.estimate().covariance.speed >= predicted.estimate().covariance.speed);
    assert!(navigation.estimate().speed_sigma_mps() >= 6.0);
    assert_eq!(navigation.estimate().covariance.position_speed, 0.0);
    assert_eq!(navigation.state.last_gps_speed_ms, -1);
    assert_eq!(navigation.state.last_vehicle_speed_ms, -1);
}

#[test]
fn delayed_gps_replays_and_vetoes_recovery_including_cooldown() {
    for time in [210_000, 211_000] {
        let mut timely = learned_navigation();
        let mut delayed = timely.clone();
        let observation = gps(time, fix(time).point.latitude_deg);
        if time < 211_000 {
            timely.tick(time, Some(observation)).unwrap();
            delayed.tick(time, None).unwrap();
            feed(&mut timely, 211_000);
        } else {
            timely
                .tick_with_observations(time, Some(observation), None, Some(fix(time)))
                .unwrap();
        }
        feed(&mut delayed, 211_000);
        assert_eq!(delayed.estimate().speed_mps, 15.0);
        timely.tick(212_000, None).unwrap();
        delayed.tick(212_000, Some(observation)).unwrap();
        assert_eq!(timely.estimate(), delayed.estimate());
        for later in (216_000..=296_000).step_by(5_000) {
            feed(&mut timely, later);
            feed(&mut delayed, later);
            assert_eq!(timely.estimate(), delayed.estimate());
        }
    }
}
