//! Bounded contracts; see docs/NATIVE_VERIFICATION.md for domains and exclusions.

use super::*;

mod covariance;

/// Bit patterns include NaN, infinities and both signed zeros.
fn arbitrary_float() -> f64 {
    f64::from_bits(kani::any())
}

fn bounded_float(minimum: f64, maximum: f64) -> f64 {
    let value = arbitrary_float();
    kani::assume(value.is_finite() && value >= minimum && value <= maximum);
    value
}

/// State preservation does not require an arithmetic history; all fields are symbolic.
fn filter() -> RouteFilter {
    RouteFilter {
        estimate: Estimate {
            position_m: bounded_float(-1_000_000.0, 1_000_000.0),
            speed_mps: bounded_float(0.0, 60.0),
            covariance: Covariance2 {
                position: 100.0,
                position_speed: 2.0,
                speed: 4.0,
            },
            systematic_drift_m: bounded_float(0.0, 10_000.0),
        },
    }
}

/// Use bits so the contract also detects changing the sign of zero.
fn same_state(actual: Estimate, expected: Estimate) {
    assert_eq!(actual.position_m.to_bits(), expected.position_m.to_bits());
    assert_eq!(actual.speed_mps.to_bits(), expected.speed_mps.to_bits());
    assert_eq!(
        actual.covariance.position.to_bits(),
        expected.covariance.position.to_bits()
    );
    assert_eq!(
        actual.covariance.position_speed.to_bits(),
        expected.covariance.position_speed.to_bits()
    );
    assert_eq!(
        actual.covariance.speed.to_bits(),
        expected.covariance.speed.to_bits()
    );
    assert_eq!(
        actual.systematic_drift_m.to_bits(),
        expected.systematic_drift_m.to_bits()
    );
}

#[kani::proof]
#[kani::unwind(2)]
fn validators_reject_invalid_numbers() {
    let value = arbitrary_float();
    assert_eq!(validate_finite(value).is_ok(), value.is_finite());
    assert_eq!(
        validate_sigma(value).is_ok(),
        value.is_finite() && value >= 0.0
    );
    kani::cover!(value.is_nan(), "nan");
    kani::cover!(value == f64::INFINITY, "infinity");
    kani::cover!(value.to_bits() == (-0.0_f64).to_bits(), "negative zero");
}

/// Every public mutation accepting a scalar rejects a non-finite measurement atomically.
#[kani::proof]
#[kani::unwind(2)]
fn nonfinite_measurement_preserves_state() {
    let mut filter = filter();
    let before = filter.estimate();
    let value = arbitrary_float();
    kani::assume(!value.is_finite());
    let operation: u8 = kani::any();
    kani::assume(operation < 7);
    let rejected = match operation {
        0 => filter.update_position(value, 1.0, 9.0).is_err(),
        1 => filter.update_speed(value, 1.0, 9.0).is_err(),
        2 => filter.update_coarse_position(value, 1.0, 9.0, 1.0).is_err(),
        3 => filter.update_coarse_speed(value, 1.0, 9.0, 1.0).is_err(),
        4 => filter.anchor_position(value, 1.0).is_err(),
        5 => filter.set_speed_prior(value, 1.0).is_err(),
        _ => filter.restore_speed_prior(value, 1.0).is_err(),
    };
    assert!(rejected);
    same_state(filter.estimate(), before);
    kani::cover!(rejected, "rejected");
}

// Separate entry points keep solver formulas small without narrowing the scalar domain.
fn invalid_uncertainty_preserves_state(operation: u8) {
    let mut filter = filter();
    let before = filter.estimate();
    let sigma = arbitrary_float();
    kani::assume(!sigma.is_finite() || sigma < 0.0);
    let rejected = match operation {
        0 => filter.update_position(0.0, sigma, 9.0).is_err(),
        1 => filter.update_speed(0.0, sigma, 9.0).is_err(),
        2 => filter.update_coarse_position(0.0, sigma, 9.0, 1.0).is_err(),
        3 => filter.update_coarse_speed(0.0, sigma, 9.0, 1.0).is_err(),
        4 => filter.anchor_position(0.0, sigma).is_err(),
        5 => filter.set_speed_prior(0.0, sigma).is_err(),
        6 => filter.restore_speed_prior(0.0, sigma).is_err(),
        _ => filter.predict(1.0, sigma, 0.1).is_err(),
    };
    assert!(rejected);
    same_state(filter.estimate(), before);
    kani::cover!(rejected, "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_gate_preserves_state() {
    let mut filter = filter();
    let before = filter.estimate();
    let gate = arbitrary_float();
    kani::assume(!gate.is_finite() || gate <= 0.0);
    let operation: u8 = kani::any();
    kani::assume(operation < 4);
    let rejected = match operation {
        0 => filter.update_position(0.0, 1.0, gate).is_err(),
        1 => filter.update_speed(0.0, 1.0, gate).is_err(),
        2 => filter.update_coarse_position(0.0, 1.0, gate, 1.0).is_err(),
        _ => filter.update_coarse_speed(0.0, 1.0, gate, 1.0).is_err(),
    };
    assert!(rejected);
    same_state(filter.estimate(), before);
    kani::cover!(rejected, "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_prediction_preserves_state() {
    let mut filter = filter();
    let before = filter.estimate();
    let value = arbitrary_float();
    let timing: bool = kani::any();
    let maximum = if timing { 5.0 } else { 1.0 };
    kani::assume(!value.is_finite() || value < 0.0 || value > maximum);
    let result = if timing {
        filter.predict(value, 1.0, 0.1)
    } else {
        filter.predict(1.0, 1.0, value)
    };
    assert!(result.is_err());
    same_state(filter.estimate(), before);
    kani::cover!(result.is_err(), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn anchor_and_reset_preserve_speed() {
    let mut filter = filter();
    let before = filter.estimate();
    let position = bounded_float(-1_000_000.0, 1_000_000.0);
    let sigma = bounded_float(0.0, 1000.0);
    assert!(filter.anchor_position(position, sigma).is_ok());
    let after = filter.estimate();
    assert_eq!(after.position_m.to_bits(), position.to_bits());
    assert_eq!(after.speed_mps.to_bits(), before.speed_mps.to_bits());
    assert_eq!(after.covariance.speed, before.covariance.speed);
    assert_eq!(after.systematic_drift_m, 0.0);
    assert!(after.covariance.position >= MIN_VARIANCE);
    filter.estimate = before;
    filter.reset_systematic_drift();
    same_state(
        filter.estimate(),
        Estimate {
            systematic_drift_m: 0.0,
            ..before
        },
    );
    kani::cover!(true, "accepted");
}

#[kani::proof]
#[kani::unwind(2)]
fn restore_prior_never_reduces_variance() {
    let mut filter = filter();
    filter.estimate.covariance.speed = bounded_float(0.0625, 1_000_000.0);
    let before = filter.estimate();
    let speed = bounded_float(0.0, 60.0);
    let sigma = bounded_float(0.0, 1000.0);
    assert!(filter.restore_speed_prior(speed, sigma).is_ok());
    let after = filter.estimate();
    assert!(after.covariance.speed >= before.covariance.speed);
    assert_eq!(after.position_m.to_bits(), before.position_m.to_bits());
    assert_eq!(
        after.systematic_drift_m.to_bits(),
        before.systematic_drift_m.to_bits()
    );
    assert_eq!(after.speed_mps.to_bits(), speed.to_bits());
    kani::cover!(true, "accepted");
}

/// Fixed valid correlated covariance; integer measurements cover both gate outcomes.
/// Avoid claiming unrestricted floating-point stability from these bounded corrections.
fn coarse_correction(position: bool) {
    let mut filter = RouteFilter::new(0.0, 0.0, 10.0, 2.0, 50.0).unwrap();
    filter.estimate.covariance.position_speed = 2.0;
    let before = filter.estimate();
    let measurement: i16 = kani::any();
    kani::assume((-1000..=1000).contains(&measurement));
    let result = if position {
        filter.update_coarse_position(f64::from(measurement), 10.0, 9.0, 5.0)
    } else {
        filter.update_coarse_speed(f64::from(measurement), 2.0, 9.0, 1.0)
    };
    let after = filter.estimate();
    assert_eq!(after.systematic_drift_m, before.systematic_drift_m);
    if position {
        assert_eq!(after.speed_mps, before.speed_mps);
    } else {
        assert_eq!(after.position_m, before.position_m);
    }
    assert!(result.is_ok());
    if result == Ok(true) {
        assert!(after.covariance.position >= 100.0);
        assert!(after.covariance.speed >= 4.0);
        assert_eq!(after.covariance.position_speed, 0.0);
    } else {
        same_state(after, before);
    }
    kani::cover!(result == Ok(true), "accepted");
    kani::cover!(result == Ok(false), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_position_is_conservative() {
    coarse_correction(true);
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_speed_is_conservative() {
    coarse_correction(false);
}

#[kani::proof]
#[kani::unwind(2)]
fn constructor_rejects_invalid_inputs() {
    let value = arbitrary_float();
    let field: u8 = kani::any();
    kani::assume(field < 5);
    kani::assume(!value.is_finite() || (field >= 2 && value < 0.0));
    let result = match field {
        0 => RouteFilter::new(value, 0.0, 1.0, 1.0, 0.0),
        1 => RouteFilter::new(0.0, value, 1.0, 1.0, 0.0),
        2 => RouteFilter::new(0.0, 0.0, value, 1.0, 0.0),
        3 => RouteFilter::new(0.0, 0.0, 1.0, value, 0.0),
        _ => RouteFilter::new(0.0, 0.0, 1.0, 1.0, value),
    };
    assert!(result.is_err());
    kani::cover!(result.is_err(), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_correction_limit_preserves_state() {
    let mut filter = filter();
    let before = filter.estimate();
    let limit = arbitrary_float();
    kani::assume(!limit.is_finite() || limit < 0.0);
    let result = if kani::any() {
        filter.update_coarse_position(0.0, 1.0, 9.0, limit)
    } else {
        filter.update_coarse_speed(0.0, 1.0, 9.0, limit)
    };
    assert!(result.is_err());
    same_state(filter.estimate(), before);
    kani::cover!(result.is_err(), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_position_update_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(0);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_speed_update_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(1);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_coarse_position_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(2);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_coarse_speed_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(3);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_anchor_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(4);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_speed_prior_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(5);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_restore_prior_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(6);
}

#[kani::proof]
#[kani::unwind(2)]
fn invalid_prediction_uncertainty_preserves_state() {
    invalid_uncertainty_preserves_state(7);
}

/// Full scalar domain, including finite values whose square overflows.
#[kani::proof]
#[kani::unwind(2)]
fn diagonal_success_is_finite() {
    let sigma = arbitrary_float();
    let result = Covariance2::diagonal(sigma, 1.0);
    if let Ok(covariance) = result {
        assert!(covariance.position.is_finite());
        assert!(covariance.speed.is_finite());
        assert!(covariance.position_speed.is_finite());
    }
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}

fn assert_finite(estimate: Estimate) {
    assert!(estimate.position_m.is_finite());
    assert!(estimate.speed_mps.is_finite());
    assert!(estimate.systematic_drift_m.is_finite());
    assert!(estimate.covariance.position.is_finite());
    assert!(estimate.covariance.position_speed.is_finite());
    assert!(estimate.covariance.speed.is_finite());
}

/// Every candidate bit pattern is checked; no numerical-result assumptions or stubs.
#[kani::proof]
#[kani::unwind(2)]
fn commit_is_atomic_and_finite() {
    let mut filter = filter();
    let before = filter.estimate();
    let candidate = Estimate {
        position_m: arbitrary_float(),
        speed_mps: arbitrary_float(),
        systematic_drift_m: arbitrary_float(),
        covariance: Covariance2 {
            position: arbitrary_float(),
            position_speed: arbitrary_float(),
            speed: arbitrary_float(),
        },
    };
    let result = filter.commit_estimate(candidate);
    if result.is_err() {
        same_state(filter.estimate(), before);
    }
    assert_finite(filter.estimate());
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn constructor_success_is_finite() {
    let result = RouteFilter::new(
        arbitrary_float(),
        arbitrary_float(),
        arbitrary_float(),
        arbitrary_float(),
        arbitrary_float(),
    );
    if let Ok(ref filter) = result {
        assert_finite(filter.estimate());
    }
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}

fn prior_success_is_finite(operation: u8) {
    let mut filter = filter();
    let before = filter.estimate();
    let value = arbitrary_float();
    let sigma = arbitrary_float();
    let result = match operation {
        0 => filter.anchor_position(value, sigma),
        1 => filter.set_speed_prior(value, sigma),
        _ => filter.restore_speed_prior(value, sigma),
    };
    if result.is_err() {
        same_state(filter.estimate(), before);
    }
    assert_finite(filter.estimate());
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn anchor_success_is_finite() {
    prior_success_is_finite(0);
}

#[kani::proof]
#[kani::unwind(2)]
fn speed_prior_success_is_finite() {
    prior_success_is_finite(1);
}

#[kani::proof]
#[kani::unwind(2)]
fn restored_prior_success_is_finite() {
    prior_success_is_finite(2);
}

/// Arbitrary finite state scalars; fixed valid covariance isolates state arithmetic overflow.
#[kani::proof]
#[kani::unwind(2)]
fn prediction_success_is_finite() {
    let mut filter = filter();
    let position = arbitrary_float();
    let speed = arbitrary_float();
    let drift = arbitrary_float();
    kani::assume(position.is_finite() && speed.is_finite() && drift.is_finite() && drift >= 0.0);
    filter.estimate.position_m = position;
    filter.estimate.speed_mps = speed;
    filter.estimate.systematic_drift_m = drift;
    let before = filter.estimate();
    let dt = if kani::any() { 1.0 } else { 5.0 };
    let result = filter.predict(dt, 1.0, 1.0);
    if result.is_err() {
        same_state(filter.estimate(), before);
    }
    assert_finite(filter.estimate());
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}

/// All measurement/sigma bit patterns against the documented valid correlated fixture.
fn measurement_success_is_finite(operation: u8) {
    let mut filter = RouteFilter::new(10.0, 2.0, 10.0, 2.0, 5.0).unwrap();
    filter.estimate.covariance.position_speed = 2.0;
    let before = filter.estimate();
    let measurement = arbitrary_float();
    let sigma = arbitrary_float();
    let result = match operation {
        0 => filter
            .update_position(measurement, sigma, 9.0)
            .map(|outcome| outcome.accepted),
        1 => filter
            .update_speed(measurement, sigma, 9.0)
            .map(|outcome| outcome.accepted),
        2 => filter.update_coarse_position(measurement, sigma, 9.0, 5.0),
        _ => filter.update_coarse_speed(measurement, sigma, 9.0, 1.0),
    };
    if result != Ok(true) {
        same_state(filter.estimate(), before);
    }
    assert_finite(filter.estimate());
    kani::cover!(result == Ok(true), "accepted");
    kani::cover!(result == Ok(false), "gated");
    kani::cover!(result.is_err(), "error");
}

#[kani::proof]
#[kani::unwind(2)]
fn position_update_success_is_finite() {
    measurement_success_is_finite(0);
}

#[kani::proof]
#[kani::unwind(2)]
fn speed_update_success_is_finite() {
    measurement_success_is_finite(1);
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_position_success_is_finite() {
    measurement_success_is_finite(2);
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_speed_success_is_finite() {
    measurement_success_is_finite(3);
}

#[kani::proof]
#[kani::unwind(2)]
fn safety_radius_success_is_finite() {
    let estimate = RouteFilter::new(0.0, 0.0, 2.0, 1.0, 5.0)
        .unwrap()
        .estimate();
    let result = estimate.safety_radius_m(arbitrary_float());
    if let Ok(radius) = result {
        assert!(radius.is_finite());
    }
    kani::cover!(result.is_ok(), "accepted");
    kani::cover!(result.is_err(), "rejected");
}
