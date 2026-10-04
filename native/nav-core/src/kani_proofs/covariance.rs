//! Correlated covariance families, kept separate from the arbitrary-scalar overflow contracts.

use super::{assert_finite, same_state};
use crate::{Covariance2, Estimate, MIN_VARIANCE, RouteFilter, SYMMETRY_TOLERANCE};

/// A finite grid of independently varying standard deviations and correlation coefficients.
/// Binary scaling keeps the input construction exact, including the rank-one endpoints.
fn family() -> Covariance2 {
    let position_units: u8 = kani::any();
    let speed_units: u8 = kani::any();
    let correlation_kind: u8 = kani::any();
    kani::assume((1..=4).contains(&position_units));
    kani::assume((1..=4).contains(&speed_units));
    kani::assume(correlation_kind < 5);
    let scale = if kani::any() { 1.0 } else { 1.0 / 65536.0 };
    let position_sigma = f64::from(position_units) * scale;
    let speed_sigma = f64::from(speed_units) * scale;
    let correlation = match correlation_kind {
        0 => -1.0,
        1 => -(1.0 - 1.0 / 1048576.0),
        2 => 0.0,
        3 => 1.0 - 1.0 / 1048576.0,
        _ => 1.0,
    };
    Covariance2 {
        position: (position_sigma * position_sigma).max(MIN_VARIANCE),
        position_speed: correlation * position_sigma * speed_sigma,
        speed: (speed_sigma * speed_sigma).max(MIN_VARIANCE),
    }
}

/// Check the public numerical contract without calling the production validity predicate.
fn assert_covariance(covariance: Covariance2) {
    assert!(covariance.position.is_finite());
    assert!(covariance.speed.is_finite());
    assert!(covariance.position_speed.is_finite());
    assert!(covariance.position >= MIN_VARIANCE);
    assert!(covariance.speed >= MIN_VARIANCE);
    let product = covariance.position * covariance.speed;
    let cross_squared = covariance.position_speed * covariance.position_speed;
    assert!(product.is_finite() && cross_squared.is_finite());
    assert!(product - cross_squared >= -SYMMETRY_TOLERANCE * (1.0 + product));
}

fn filter() -> RouteFilter {
    let covariance = family();
    assert_covariance(covariance);
    RouteFilter {
        estimate: Estimate {
            position_m: 10.0,
            speed_mps: 2.0,
            covariance,
            systematic_drift_m: 5.0,
        },
    }
}

/// Witness the difficult input shapes on successful operations, rather than on rejection paths.
fn cover_shapes(before: Covariance2, after: Covariance2, accepted: bool) {
    let product = before.position * before.speed;
    let determinant = product - before.position_speed * before.position_speed;
    kani::cover!(
        accepted && before.position_speed < 0.0,
        "negative correlation"
    );
    kani::cover!(
        accepted && before.position_speed > 0.0,
        "positive correlation"
    );
    kani::cover!(accepted && before.position_speed == 0.0, "diagonal");
    kani::cover!(accepted && determinant == 0.0, "singular");
    kani::cover!(
        accepted && determinant > 0.0 && determinant < product / 100000.0,
        "near singular"
    );
    kani::cover!(
        accepted && (after.position == MIN_VARIANCE || after.speed == MIN_VARIANCE),
        "variance floor"
    );
}

/// Every valid step must succeed; the invalid step must preserve every state bit.
#[kani::proof]
#[kani::unwind(2)]
fn prediction_preserves_covariance_family() {
    let mut filter = filter();
    let before = filter.estimate();
    let step: u8 = kani::any();
    kani::assume(step < 4);
    let dt = match step {
        0 => 0.0,
        1 => 0.5,
        2 => 5.0,
        _ => -1.0,
    };
    let result = filter.predict(dt, 0.5, 0.25);
    assert_eq!(result.is_ok(), step < 3);
    if result.is_err() {
        same_state(filter.estimate(), before);
    }
    let after = filter.estimate();
    assert_finite(after);
    assert_covariance(after.covariance);
    assert_eq!(after.speed_mps.to_bits(), before.speed_mps.to_bits());
    assert!(after.systematic_drift_m >= before.systematic_drift_m);
    cover_shapes(before.covariance, after.covariance, result.is_ok());
    kani::cover!(result.is_err(), "error");
    kani::cover!(step == 2 && result.is_ok(), "maximum step");
}

/// Exercise real Joseph/coarse updates with zero, small, and ordinary measurement uncertainty.
/// Innovation rejection and invalid uncertainty must leave all fields bit-for-bit unchanged.
fn measurement(operation: u8) {
    let mut filter = filter();
    let before = filter.estimate();
    let noise: u8 = kani::any();
    let residual: u8 = kani::any();
    kani::assume(noise < 4 && residual < 3);
    let sigma = match noise {
        0 => 0.0,
        1 => 0.25,
        2 => 2.0,
        _ => -1.0,
    };
    let innovation = match residual {
        0 => 0.0,
        1 => 0.5,
        _ => 64.0,
    };
    let measured = if operation == 0 || operation == 2 {
        before.position_m + innovation
    } else {
        before.speed_mps + innovation
    };
    let result = match operation {
        0 => filter
            .update_position(measured, sigma, 9.0)
            .map(|outcome| outcome.accepted),
        1 => filter
            .update_speed(measured, sigma, 9.0)
            .map(|outcome| outcome.accepted),
        2 => filter.update_coarse_position(measured, sigma, 9.0, 5.0),
        _ => filter.update_coarse_speed(measured, sigma, 9.0, 1.0),
    };
    assert_eq!(result.is_ok(), noise < 3);
    if result != Ok(true) {
        same_state(filter.estimate(), before);
    }
    let after = filter.estimate();
    assert_finite(after);
    assert_covariance(after.covariance);
    assert_eq!(
        after.systematic_drift_m.to_bits(),
        before.systematic_drift_m.to_bits()
    );
    if result == Ok(true) && operation == 2 {
        assert_eq!(after.speed_mps.to_bits(), before.speed_mps.to_bits());
        assert_eq!(
            after.covariance.speed.to_bits(),
            before.covariance.speed.to_bits()
        );
        assert!(after.covariance.position >= sigma * sigma);
        assert!((after.position_m - before.position_m).abs() <= 5.0);
    }
    if result == Ok(true) && operation == 3 {
        assert_eq!(after.position_m.to_bits(), before.position_m.to_bits());
        assert_eq!(
            after.covariance.position.to_bits(),
            before.covariance.position.to_bits()
        );
        assert!(after.covariance.speed >= sigma * sigma);
        assert!((after.speed_mps - before.speed_mps).abs() <= 1.0);
    }
    cover_shapes(before.covariance, after.covariance, result == Ok(true));
    kani::cover!(result == Ok(true) && residual == 1, "nonzero correction");
    kani::cover!(result == Ok(false), "gated");
    kani::cover!(result.is_err(), "error");
}

#[kani::proof]
#[kani::unwind(2)]
fn position_update_preserves_covariance_family() {
    measurement(0);
}

#[kani::proof]
#[kani::unwind(2)]
fn speed_update_preserves_covariance_family() {
    measurement(1);
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_position_preserves_covariance_family() {
    measurement(2);
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_speed_preserves_covariance_family() {
    measurement(3);
}
