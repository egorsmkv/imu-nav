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

#[test]
fn future_samples_cannot_supply_a_current_speed_estimate() {
    let mut estimator = NetworkSpeedEstimator::default();
    for index in 0..4 {
        estimator.add(
            f64::from(index) * 100.0,
            20.0,
            i64::from(index) * 10_000 + 1000,
        );
    }
    let before: Vec<_> = estimator
        .samples
        .iter()
        .map(|sample| {
            (
                sample.elapsed_ms,
                sample.position_m.to_bits(),
                sample.accuracy_m.to_bits(),
            )
        })
        .collect();
    assert!(estimator.estimate(0).is_none());
    assert!(estimator.strict_estimate(0).is_none());
    let after: Vec<_> = estimator
        .samples
        .iter()
        .map(|sample| {
            (
                sample.elapsed_ms,
                sample.position_m.to_bits(),
                sample.accuracy_m.to_bits(),
            )
        })
        .collect();
    assert_eq!(after, before);
    assert!(estimator.strict_estimate(31_000).is_some());
}

#[test]
fn future_observations_do_not_change_an_existing_estimate() {
    for interval_ms in [5000, 10_000] {
        let mut estimator = NetworkSpeedEstimator::default();
        for index in 0..4 {
            estimator.add(
                f64::from(index) * 50.0,
                20.0,
                i64::from(index) * interval_ms,
            );
        }
        let now_ms = 3 * interval_ms;
        let before = estimator.estimate(now_ms).unwrap();
        let strict_before = estimator.strict_estimate(now_ms);
        estimator.add(200.0, 20.0, 4 * interval_ms);
        assert_eq!(estimator.estimate(now_ms), Some(before));
        assert_eq!(estimator.strict_estimate(now_ms), strict_before);
    }
}

#[test]
fn speed_windows_include_the_exact_expiry_boundary_only() {
    for (window_ms, minimum_span_s) in [(30_000, 15.0), (40_000, 20.0), (90_000, 30.0)] {
        let mut estimator = NetworkSpeedEstimator::default();
        for index in 0..4 {
            estimator.add(
                f64::from(index) * 100.0,
                20.0,
                i64::from(index) * window_ms / 3,
            );
        }
        assert!(
            estimator
                .estimate_window(window_ms, window_ms, minimum_span_s)
                .is_some()
        );
        assert!(
            estimator
                .estimate_window(window_ms + 1, window_ms, minimum_span_s)
                .is_none()
        );
        assert!(
            estimator
                .estimate_window(window_ms - 1, window_ms, minimum_span_s)
                .is_none()
        );
    }
}

#[test]
fn too_few_inliers_reject_instead_of_reusing_the_unfiltered_fit() {
    let mut estimator = NetworkSpeedEstimator::default();
    for (index, position) in [0.0, 100.0, 200.0, 10_000.0].into_iter().enumerate() {
        estimator.add(position, 20.0, i64::try_from(index).unwrap() * 10_000);
    }
    assert!(estimator.estimate(30_000).is_none());
    assert!(estimator.strict_estimate(30_000).is_none());
}

#[test]
fn degenerate_finite_observations_cannot_publish_nonfinite_regression() {
    let mut estimator = NetworkSpeedEstimator::default();
    for index in 0..4 {
        estimator.add(
            f64::from(index) * 100.0,
            f64::MAX,
            i64::from(index) * 10_000,
        );
    }
    assert!(estimator.estimate(30_000).is_none());
    assert!(estimator.strict_estimate(30_000).is_none());
}

#[test]
fn removing_an_endpoint_rechecks_the_surviving_time_span() {
    let mut estimator = NetworkSpeedEstimator::default();
    for index in 0..6 {
        let position = if index == 0 {
            600.0
        } else {
            f64::from(index) * 30.0
        };
        estimator.add(position, 20.0, i64::from(index) * 3000);
    }
    let initial = fit(&estimator.samples).unwrap();
    let retained = select_inliers(&estimator.samples, initial);
    assert_eq!(retained.len(), 5);
    assert_eq!(retained.first().unwrap().elapsed_ms, 3000);
    assert_eq!(retained.last().unwrap().elapsed_ms, 15_000);
    assert!(estimator.estimate(15_000).is_none());
}

#[test]
fn regression_publication_checks_surviving_count_span_and_uncertainty() {
    let samples: Vec<_> = (0..4)
        .map(|index| Sample {
            elapsed_ms: i64::from(index) * 5000,
            position_m: f64::from(index) * 50.0,
            accuracy_m: 20.0,
        })
        .collect();
    let line = Line {
        intercept_m: 0.0,
        slope_mps: 10.0,
        slope_sigma_mps: MAX_NETWORK_SIGMA_MPS / REGRESSION_SIGMA_INFLATION,
        origin_ms: 0,
    };
    let accepted = finish_network_estimate(line, &samples, 15.0).unwrap();
    assert_eq!(accepted.samples, 4);
    assert_eq!(accepted.sigma_mps, MAX_NETWORK_SIGMA_MPS);
    assert!(finish_network_estimate(line, &samples[..3], 15.0).is_none());
    assert!(finish_network_estimate(line, &samples, 15.001).is_none());
    for sigma in [
        0.0,
        -1.0,
        f64::NAN,
        f64::INFINITY,
        f64::MAX,
        line.slope_sigma_mps + 1.0e-10,
    ] {
        assert!(
            finish_network_estimate(
                Line {
                    slope_sigma_mps: sigma,
                    ..line
                },
                &samples,
                15.0
            )
            .is_none()
        );
    }
}
