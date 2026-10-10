use super::*;

#[test]
fn antipodal_distance_remains_finite() {
    for latitude in -890..=890 {
        let latitude = f64::from(latitude) / 10.0;
        let distance = distance_m(latitude, 30.0, -latitude, -150.0);
        assert!(distance.is_finite(), "latitude={latitude}");
        assert!((distance - std::f64::consts::PI * 6_371_000.0).abs() < 1.0);
    }
}

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

#[test]
#[cfg(target_pointer_width = "64")]
fn publication_threshold_must_fit_sqlite_integer() {
    let policy = Policy {
        min_devices: usize::MAX,
        ..Policy::default()
    };
    assert!(matches!(
        policy.validate(),
        Err(PolicyError::TooLarge("min_devices"))
    ));
}
