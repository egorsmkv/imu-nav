//! Proof-only state inspection; no replacement of production operations.

use super::*;

/// Check the retained samples directly: fewer than four samples cannot produce an estimate.
pub(crate) fn assert_empty(estimator: &NetworkSpeedEstimator) {
    assert!(estimator.samples.is_empty());
}

/// Compare retained observations without relying on estimate eligibility or floating equality.
pub(crate) fn assert_same_samples(
    actual: &NetworkSpeedEstimator,
    expected: &NetworkSpeedEstimator,
) {
    assert_eq!(actual.samples.len(), expected.samples.len());
    for (actual, expected) in actual.samples.iter().zip(&expected.samples) {
        assert_eq!(actual.elapsed_ms, expected.elapsed_ms);
        assert_eq!(actual.position_m.to_bits(), expected.position_m.to_bits());
        assert_eq!(actual.accuracy_m.to_bits(), expected.accuracy_m.to_bits());
    }
}

#[kani::proof]
#[kani::unwind(2)]
fn fusion_finalization_never_publishes_nonfinite_values() {
    let result = finish_fusion(
        f64::from_bits(kani::any()),
        f64::from_bits(kani::any()),
        2,
        f64::from_bits(kani::any()),
    );
    if let Some(estimate) = result {
        assert!(
            estimate.speed_mps.is_finite() && (0.0..=MAX_SPEED_MPS).contains(&estimate.speed_mps)
        );
        assert!(estimate.sigma_mps.is_finite() && estimate.sigma_mps > 0.0);
        assert!(estimate.span_s.is_finite() && estimate.span_s >= 0.0);
        assert_eq!(estimate.samples, 2);
    }
    kani::cover!(result.is_some(), "accepted");
    kani::cover!(result.is_none(), "rejected");
    kani::cover!(
        result.is_some_and(|estimate| estimate.speed_mps == MAX_SPEED_MPS),
        "capped"
    );
}

#[kani::proof]
#[kani::unwind(2)]
fn malformed_network_metadata_is_ignored() {
    let network = SpeedEstimate {
        speed_mps: f64::from_bits(kani::any()),
        sigma_mps: f64::from_bits(kani::any()),
        span_s: f64::from_bits(kani::any()),
        samples: kani::any(),
    };
    let invalid = !network.speed_mps.is_finite()
        || network.speed_mps < 0.0
        || !network.sigma_mps.is_finite()
        || network.sigma_mps < 0.0
        || !network.span_s.is_finite()
        || network.span_s < 0.0;
    kani::assume(invalid);
    assert!(fuse_speed(None, kani::any(), None, Some(network)).is_none());
    let actual = fuse_speed(Some(10.0), 0, None, Some(network));
    assert_eq!(actual, fuse_speed(Some(10.0), 0, None, None));
    kani::cover!(network.sigma_mps.is_nan(), "nan uncertainty");
    kani::cover!(network.span_s < 0.0, "negative span");
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_measured_speeds_cannot_add_weight() {
    let speed = f64::from_bits(kani::any());
    kani::assume(!speed.is_finite() || speed < 0.0);
    assert!(fuse_speed(Some(speed), kani::any(), Some(speed), None).is_none());
    kani::cover!(speed < 0.0, "negative");
    kani::cover!(speed.is_nan(), "nan");
}

/// Split source combinations retain the full signed age domain without one large disjunction.
fn check_ordinary_fusion(gps: bool, route: bool, network: bool, age: i64) {
    let result = fuse_speed(
        gps.then_some(10.0),
        age,
        route.then_some(20.0),
        network.then_some(SpeedEstimate {
            speed_mps: 12.0,
            sigma_mps: 1.0,
            samples: 5,
            span_s: 30.0,
        }),
    );
    let count = usize::from(gps) + usize::from(route) + usize::from(network);
    assert_eq!(result.is_some(), count > 0);
    if let Some(estimate) = result {
        assert_eq!(estimate.samples, count);
        // Allow rounding at either endpoint of the weighted average.
        assert!((9.999..=20.001).contains(&estimate.speed_mps));
        assert!(estimate.sigma_mps.is_finite() && estimate.sigma_mps > 0.0);
        assert_eq!(estimate.span_s, if network { 30.0 } else { 0.0 });
    }
    kani::cover!(true, "sources");
}

#[kani::proof]
#[kani::unwind(4)]
fn invalid_or_nonincreasing_samples_preserve_storage() {
    let mut estimator = NetworkSpeedEstimator::default();
    let previous: i64 = kani::any();
    estimator.add(10.0, 20.0, previous);
    let before = estimator.clone();
    let time: i64 = kani::any();
    let position = f64::from_bits(kani::any());
    let accuracy = f64::from_bits(kani::any());
    kani::assume(
        !position.is_finite() || !accuracy.is_finite() || accuracy < 0.0 || time <= previous,
    );
    estimator.add(position, accuracy, time);
    assert_same_samples(&estimator, &before);
    kani::cover!(
        time == previous && position.is_finite() && accuracy == 0.0,
        "duplicate"
    );
    kani::cover!(time < previous, "backward");
    kani::cover!(position.is_nan(), "invalid");
}

/// Fixed initialized storage avoids symbolic reallocations while covering each occupancy separately.
fn stored_samples() -> Vec<Sample> {
    let mut samples = Vec::with_capacity(MAX_SAMPLES + 1);
    for index in 0..MAX_SAMPLES {
        samples.push(Sample {
            position_m: 10.0,
            accuracy_m: 20.0,
            elapsed_ms: i64::try_from(index).unwrap(),
        });
    }
    samples
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_retains_the_newest_sixty_samples() {
    let mut estimator = NetworkSpeedEstimator {
        samples: stored_samples(),
    };
    let position = f64::from_bits(kani::any());
    let accuracy = f64::from_bits(kani::any());
    kani::assume(position.is_finite() && accuracy.is_finite() && accuracy >= 0.0);
    estimator.add(position, accuracy, 1000);
    assert_eq!(estimator.samples.len(), MAX_SAMPLES);
    for (index, sample) in estimator.samples.iter().take(MAX_SAMPLES - 1).enumerate() {
        assert_eq!(sample.elapsed_ms, i64::try_from(index + 1).unwrap());
        assert_eq!(sample.position_m, 10.0);
        assert_eq!(sample.accuracy_m, 20.0);
    }
    let last = estimator.samples.last().unwrap();
    assert_eq!(last.elapsed_ms, 1000);
    assert_eq!(last.position_m.to_bits(), position.to_bits());
    assert_eq!(last.accuracy_m.to_bits(), accuracy.to_bits());
    estimator.clear();
    assert!(estimator.samples.is_empty());
    kani::cover!(true, "eviction");
}

/// Independent fixed-size fixtures prevent multi-update heap formulas from growing exponentially.
fn check_spare_capacity(count: usize) {
    let mut estimator = NetworkSpeedEstimator {
        samples: stored_samples(),
    };
    estimator.samples.truncate(count);
    estimator.add(30.0, 40.0, 1000);
    assert_eq!(estimator.samples.len(), count + 1);
    for (index, sample) in estimator.samples.iter().take(count).enumerate() {
        assert_eq!(sample.elapsed_ms, i64::try_from(index).unwrap());
        assert_eq!(sample.position_m, 10.0);
        assert_eq!(sample.accuracy_m, 20.0);
    }
    let last = estimator.samples.last().unwrap();
    assert_eq!(last.elapsed_ms, 1000);
    assert_eq!(last.position_m, 30.0);
    assert_eq!(last.accuracy_m, 40.0);
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_gps_only() {
    check_ordinary_fusion(true, false, false, kani::any());
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_gps_route() {
    check_ordinary_fusion(true, true, false, kani::any());
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_gps_network() {
    check_ordinary_fusion(true, false, true, kani::any());
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_all_nonpositive() {
    let age: i64 = kani::any();
    kani::assume(age <= 0);
    check_ordinary_fusion(true, true, true, age);
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_all_recent() {
    let age: i64 = kani::any();
    kani::assume((1..=60_000).contains(&age));
    check_ordinary_fusion(true, true, true, age);
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_all_old() {
    let age: i64 = kani::any();
    kani::assume((60_001..=3_600_000).contains(&age));
    check_ordinary_fusion(true, true, true, age);
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_all_ancient() {
    let age: i64 = kani::any();
    kani::assume(age > 3_600_000);
    check_ordinary_fusion(true, true, true, age);
}

#[kani::proof]
#[kani::unwind(2)]
fn ordinary_fusion_without_gps() {
    check_ordinary_fusion(false, kani::any(), kani::any(), kani::any());
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_0() {
    check_spare_capacity(0);
    check_spare_capacity(1);
    check_spare_capacity(2);
    check_spare_capacity(3);
    check_spare_capacity(4);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_5() {
    check_spare_capacity(5);
    check_spare_capacity(6);
    check_spare_capacity(7);
    check_spare_capacity(8);
    check_spare_capacity(9);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_10() {
    check_spare_capacity(10);
    check_spare_capacity(11);
    check_spare_capacity(12);
    check_spare_capacity(13);
    check_spare_capacity(14);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_15() {
    check_spare_capacity(15);
    check_spare_capacity(16);
    check_spare_capacity(17);
    check_spare_capacity(18);
    check_spare_capacity(19);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_20() {
    check_spare_capacity(20);
    check_spare_capacity(21);
    check_spare_capacity(22);
    check_spare_capacity(23);
    check_spare_capacity(24);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_25() {
    check_spare_capacity(25);
    check_spare_capacity(26);
    check_spare_capacity(27);
    check_spare_capacity(28);
    check_spare_capacity(29);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_30() {
    check_spare_capacity(30);
    check_spare_capacity(31);
    check_spare_capacity(32);
    check_spare_capacity(33);
    check_spare_capacity(34);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_35() {
    check_spare_capacity(35);
    check_spare_capacity(36);
    check_spare_capacity(37);
    check_spare_capacity(38);
    check_spare_capacity(39);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_40() {
    check_spare_capacity(40);
    check_spare_capacity(41);
    check_spare_capacity(42);
    check_spare_capacity(43);
    check_spare_capacity(44);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_45() {
    check_spare_capacity(45);
    check_spare_capacity(46);
    check_spare_capacity(47);
    check_spare_capacity(48);
    check_spare_capacity(49);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_50() {
    check_spare_capacity(50);
    check_spare_capacity(51);
    check_spare_capacity(52);
    check_spare_capacity(53);
    check_spare_capacity(54);
    kani::cover!(true, "inserted");
}

#[kani::proof]
#[kani::unwind(62)]
fn insertion_with_spare_capacity_from_55() {
    check_spare_capacity(55);
    check_spare_capacity(56);
    check_spare_capacity(57);
    check_spare_capacity(58);
    check_spare_capacity(59);
    kani::cover!(true, "inserted");
}
