use super::*;

const OPEN_GATE: f64 = 1.0e12;

#[test]
fn prediction_propagates_state_and_full_covariance() {
    let mut filter = RouteFilter::new(10.0, 4.0, 3.0, 2.0, 5.0).unwrap();
    filter.predict(0.5, 1.0, 0.08).unwrap();
    let estimate = filter.estimate();
    assert!((estimate.position_m - 12.0).abs() < 1.0e-12);
    assert!((estimate.covariance.position - 10.015_625).abs() < 1.0e-12);
    assert!((estimate.covariance.position_speed - 2.0625).abs() < 1.0e-12);
    assert!((estimate.covariance.speed - 4.25).abs() < 1.0e-12);
    assert!((estimate.systematic_drift_m - 5.16).abs() < 1.0e-12);
}

#[test]
fn position_update_is_symmetric_and_adjusts_correlated_speed() {
    let mut filter = RouteFilter::new(0.0, 10.0, 10.0, 2.0, 0.0).unwrap();
    filter.predict(1.0, 0.0, 0.0).unwrap();
    let before = filter.estimate();
    assert!(before.covariance.position_speed > 0.0);
    let outcome = filter.update_position(20.0, 2.0, OPEN_GATE).unwrap();
    let after = filter.estimate();
    assert!(outcome.accepted);
    assert!(after.position_m > before.position_m);
    assert!(after.speed_mps > before.speed_mps);
    assert!(after.covariance.is_valid());
}

#[test]
fn speed_update_does_not_require_a_matrix_inverse() {
    let mut filter = RouteFilter::new(100.0, 8.0, 20.0, 4.0, 0.0).unwrap();
    let outcome = filter.update_speed(12.0, 1.0, OPEN_GATE).unwrap();
    assert!(outcome.accepted);
    assert!(filter.estimate().speed_mps > 11.0);
    assert!(filter.estimate().speed_sigma_mps() < 1.0);
}

#[test]
fn innovation_gate_rejects_without_mutating_state() {
    let mut filter = RouteFilter::new(100.0, 8.0, 5.0, 2.0, 7.0).unwrap();
    let before = filter.estimate();
    let outcome = filter.update_position(1000.0, 5.0, 9.0).unwrap();
    assert!(!outcome.accepted);
    assert_eq!(before, filter.estimate());
}

#[test]
fn anchor_resets_systematic_drift_but_not_speed_variance() {
    let mut filter = RouteFilter::new(0.0, 10.0, 5.0, 3.0, 0.0).unwrap();
    filter.predict(2.0, 1.0, 0.08).unwrap();
    let speed_variance = filter.estimate().covariance.speed;
    filter.anchor_position(21.0, 4.0).unwrap();
    let estimate = filter.estimate();
    assert!((estimate.position_m - 21.0).abs() < f64::EPSILON);
    assert!((estimate.covariance.position - 16.0).abs() < f64::EPSILON);
    assert!(estimate.covariance.position_speed.abs() < f64::EPSILON);
    assert!((estimate.covariance.speed - speed_variance).abs() < f64::EPSILON);
    assert!(estimate.systematic_drift_m.abs() < f64::EPSILON);
}

#[test]
fn independent_fix_can_reset_only_the_systematic_allowance() {
    let mut filter = RouteFilter::new(0.0, 10.0, 5.0, 3.0, 0.0).unwrap();
    filter.predict(2.0, 1.0, 0.08).unwrap();
    let before = filter.estimate();
    assert!(before.systematic_drift_m > 0.0);
    filter.reset_systematic_drift();
    let after = filter.estimate();
    assert!(after.systematic_drift_m.abs() < f64::EPSILON);
    assert!((after.position_m - before.position_m).abs() < f64::EPSILON);
    assert!((after.speed_mps - before.speed_mps).abs() < f64::EPSILON);
    assert_eq!(after.covariance, before.covariance);
}

#[test]
fn invalid_inputs_do_not_mutate_state() {
    let mut filter = RouteFilter::new(0.0, 1.0, 1.0, 1.0, 0.0).unwrap();
    let before = filter.estimate();
    assert_eq!(
        filter.predict(f64::NAN, 1.0, 0.08),
        Err(FilterError::InvalidTimeStep)
    );
    assert_eq!(
        filter.update_position(0.0, -1.0, 9.0),
        Err(FilterError::InvalidSigma)
    );
    assert_eq!(before, filter.estimate());
}

#[test]
fn long_sequence_preserves_valid_covariance() {
    let mut filter = RouteFilter::new(0.0, 15.0, 25.0, 5.0, 0.0).unwrap();
    for index in 1..=100_000 {
        filter.predict(0.5, 1.5, 0.02).unwrap();
        if index % 2 == 0 {
            let truth = 15.0 * f64::from(index) * 0.5;
            let noise = (f64::from(index % 11) - 5.0) * 0.4;
            filter.update_position(truth + noise, 8.0, 25.0).unwrap();
        }
        if index % 5 == 0 {
            filter.update_speed(15.0, 0.8, 25.0).unwrap();
        }
        assert!(filter.estimate().covariance.is_valid());
    }
}

#[test]
fn finite_uncertainty_overflow_is_rejected_before_mutation() {
    assert_eq!(
        Covariance2::diagonal(f64::MAX, 1.0),
        Err(FilterError::InvalidCovariance)
    );
    assert_eq!(
        Covariance2::diagonal(1.0, f64::MAX),
        Err(FilterError::InvalidCovariance)
    );
    assert!(RouteFilter::new(0.0, 1.0, f64::MAX, 1.0, 0.0).is_err());
    let mut filter = RouteFilter::new(10.0, 2.0, 3.0, 4.0, 5.0).unwrap();
    let before = filter.estimate();
    assert_eq!(
        filter.anchor_position(50.0, f64::MAX),
        Err(FilterError::InvalidCovariance)
    );
    assert_eq!(filter.estimate(), before);
    assert_eq!(
        filter.set_speed_prior(8.0, f64::MAX),
        Err(FilterError::InvalidCovariance)
    );
    assert_eq!(filter.estimate(), before);
    assert_eq!(
        filter.restore_speed_prior(8.0, f64::MAX),
        Err(FilterError::InvalidCovariance)
    );
    assert_eq!(filter.estimate(), before);
}

#[test]
fn prediction_rejects_position_and_drift_overflow_atomically() {
    for (position, speed, drift) in [(f64::MAX, f64::MAX, 0.0), (0.0, f64::MAX, f64::MAX)] {
        let mut filter = RouteFilter::new(position, speed, 1.0, 1.0, drift).unwrap();
        let before = filter.estimate();
        assert_eq!(filter.predict(1.0, 0.0, 1.0), Err(FilterError::NonFinite));
        assert_eq!(filter.estimate(), before);
    }
}

#[test]
fn safety_radius_rejects_finite_arithmetic_overflow() {
    let filter = RouteFilter::new(0.0, 0.0, 2.0, 1.0, 0.0).unwrap();
    assert_eq!(
        filter.estimate().safety_radius_m(f64::MAX),
        Err(FilterError::NonFinite)
    );
}
