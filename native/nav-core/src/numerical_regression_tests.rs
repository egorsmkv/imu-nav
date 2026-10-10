//! Independent closed-form and partition checks for the numerical contracts found in the audit.
use super::*;

#[test]
fn continuous_prediction_matches_closed_form_for_every_partition() {
    // Integrated white acceleration of intensity 2 over 60 s: Qss=q*T^3/3,
    // Qsv=q*T^2/2, Qvv=q*T. This oracle does not call the production transition.
    for steps in [12_u32, 60, 120, 300, 600] {
        let mut filter = RouteFilter::new(0.0, 15.0, 25.0, 6.0, 0.0).unwrap();
        for _ in 0..steps {
            filter
                .predict(60.0 / f64::from(steps), std::f64::consts::SQRT_2, 0.08)
                .unwrap();
        }
        let actual = filter.estimate();
        assert!((actual.position_m - 900.0).abs() < 1e-9);
        assert!((actual.covariance.position - 274_225.0).abs() < 1e-6);
        assert!((actual.covariance.position_speed - 5_760.0).abs() < 1e-7);
        assert!((actual.covariance.speed - 156.0).abs() < 1e-9);
        assert!((actual.systematic_drift_m - 72.0).abs() < 1e-9);
    }
}

#[test]
fn huge_valid_covariance_accepts_zero_prediction_and_finite_updates() {
    let mut filter = RouteFilter::new(0.0, 1.0, 1e100, 1e100, 0.0).unwrap();
    let before = filter.estimate();
    filter.predict(0.0, 0.0, 0.0).unwrap();
    assert_eq!(before, filter.estimate());
    filter.predict(0.5, 1.0, 0.08).unwrap();
    assert!(filter.update_position(0.5, 3.0, 25.0).unwrap().accepted);
    assert!(filter.estimate().covariance.is_valid());
}

#[test]
fn scaled_covariance_check_rejects_overflowed_indefinite_matrices() {
    for cross in [1.1e200, f64::MAX] {
        let covariance = Covariance2 {
            position: 1e200,
            position_speed: cross,
            speed: 1e200,
        };
        assert!(!covariance.is_valid());
    }
    assert!(
        Covariance2 {
            position: 1e200,
            position_speed: -1e200,
            speed: 1e200
        }
        .is_valid()
    );
}
