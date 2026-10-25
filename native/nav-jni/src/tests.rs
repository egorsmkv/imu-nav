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

#[test]
fn tick_values_keeps_acceptance_flags_after_the_seven_estimate_fields() {
    let estimate = Estimate {
        position_m: 12.0,
        speed_mps: 3.0,
        covariance: Covariance2 {
            position: 4.0,
            position_speed: 0.0,
            speed: 1.0,
        },
        systematic_drift_m: 2.0,
    };
    for position_accepted in [false, true] {
        for speed_accepted in [false, true] {
            let values = tick_values(TickOutcome {
                estimate,
                projection: None,
                position_accepted,
                speed_accepted,
            })
            .unwrap();
            assert_eq!(&values[..7], &estimate_values(estimate).unwrap());
            assert!((values[7] - f64::from(position_accepted)).abs() < f64::EPSILON);
            assert!((values[8] - f64::from(speed_accepted)).abs() < f64::EPSILON);
        }
    }
}
