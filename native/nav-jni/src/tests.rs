use super::*;
use imu_nav_core::Covariance2;

#[test]
fn estimate_values_preserves_kotlin_wire_order() {
    let values = estimate_values(Estimate {
        position_m: 1.0,
        speed_mps: 2.0,
        covariance: Covariance2 {
            position: 3.0,
            position_speed: 4.0,
            speed: 5.0,
        },
        systematic_drift_m: 6.0,
    })
    .expect("the fixed safety multiplier is valid");
    let expected = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 6.0 + 2.0 * 3.0_f64.sqrt()];

    for (actual, expected) in values.into_iter().zip(expected) {
        assert!((actual - expected).abs() < f64::EPSILON);
    }
}
