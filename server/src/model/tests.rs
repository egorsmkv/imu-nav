use super::*;

#[test]
fn invalid_policy_is_rejected_before_serving_requests() {
    let invalid_samples = Policy {
        max_samples_per_device: 0,
        ..Policy::default()
    };
    assert!(matches!(
        invalid_samples.validate(),
        Err(PolicyError::NotPositive("max_samples_per_device"))
    ));

    let invalid_distance = Policy {
        max_jump_m: f64::NAN,
        ..Policy::default()
    };
    assert!(matches!(
        invalid_distance.validate(),
        Err(PolicyError::NotFinite("max_jump_m"))
    ));
}
