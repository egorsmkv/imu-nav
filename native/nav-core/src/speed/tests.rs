use super::*;

#[test]
fn fusion_uses_inverse_variance_and_caps_speed() {
    let fused = fuse_speed(
        Some(10.0),
        0,
        Some(20.0),
        Some(SpeedEstimate {
            speed_mps: 12.0,
            sigma_mps: 1.0,
            samples: 5,
            span_s: 30.0,
        }),
    )
    .unwrap();
    assert!(fused.speed_mps > 10.0 && fused.speed_mps < 13.0);
    assert!(fused.sigma_mps < 1.0);
    assert_eq!(fused.samples, 3);
    assert!((fused.span_s - 30.0).abs() < f64::EPSILON);
    assert!(
        (fuse_speed(Some(100.0), 0, None, None).unwrap().speed_mps - MAX_SPEED_MPS).abs()
            < f64::EPSILON
    );
}

#[test]
fn weighted_regression_recovers_speed_and_drops_outlier() {
    let mut estimator = NetworkSpeedEstimator::default();
    for index in 0_i32..8 {
        let elapsed_ms = i64::from(index) * 5_000;
        let position_m = if index == 3 {
            700.0
        } else {
            f64::from(index) * 50.0
        };
        estimator.add(position_m, 30.0, elapsed_ms);
    }
    let estimate = estimator.estimate(35_000).unwrap();
    assert!((estimate.speed_mps - 10.0).abs() < 0.1);
    assert_eq!(estimate.samples, 6);
    assert!((estimate.span_s - 30.0).abs() < f64::EPSILON);
}

#[test]
fn regression_rejects_short_or_imprecise_windows() {
    let mut estimator = NetworkSpeedEstimator::default();
    for index in 0_i32..4 {
        estimator.add(f64::from(index) * 10.0, 500.0, i64::from(index) * 1_000);
    }
    assert_eq!(estimator.estimate(3_000), None);
}

#[test]
fn invalid_network_metadata_cannot_contaminate_fusion() {
    for (sigma_mps, span_s) in [(f64::NAN, 30.0), (-1.0, 30.0), (1.0, f64::NAN), (1.0, -1.0)] {
        let network = SpeedEstimate {
            speed_mps: 12.0,
            sigma_mps,
            samples: 5,
            span_s,
        };
        assert_eq!(fuse_speed(None, 0, None, Some(network)), None);
        assert_eq!(
            fuse_speed(Some(10.0), 0, None, Some(network)),
            fuse_speed(Some(10.0), 0, None, None)
        );
    }
}

#[test]
fn overflowing_fusion_is_rejected_and_negative_speed_is_ignored() {
    assert!(fuse_speed(Some(-1.0), 0, None, None).is_none());
    let network = SpeedEstimate {
        speed_mps: f64::MAX,
        sigma_mps: 0.3,
        samples: 5,
        span_s: 30.0,
    };
    assert!(fuse_speed(None, 0, None, Some(network)).is_none());
}

#[test]
fn repeated_insertion_keeps_only_the_latest_sixty_observations() {
    let mut estimator = NetworkSpeedEstimator::default();
    for time in 0..125 {
        estimator.add(10.0, 20.0, time);
        assert!(estimator.samples.len() <= 60);
    }
    assert_eq!(estimator.samples.first().unwrap().elapsed_ms, 65);
    assert_eq!(estimator.samples.last().unwrap().elapsed_ms, 124);
    estimator.clear();
    assert!(estimator.samples.is_empty());
}
