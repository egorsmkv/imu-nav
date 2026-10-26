use super::*;

#[test]
fn intrinsic_tower_validation_is_independent_of_publication_policy() {
    let tower = CellTower {
        key: CellKey {
            radio: Radio::Nr,
            mcc: crate::Mcc::new(255).unwrap(),
            mnc: crate::Mnc::new(0).unwrap(),
            area: crate::CellArea::new(0).unwrap(),
            cid: crate::CellId::new(0).unwrap(),
        },
        lat: 90.0,
        lon: 180.0,
        range_m: 1.0,
        samples: 1,
    };
    assert!(tower.validated().is_ok());
    assert!(!plausible(
        &tower,
        &Policy {
            ukraine_only: true,
            ..Policy::default()
        }
    ));
    for invalid in [
        CellTower {
            lat: f64::NAN,
            ..tower.clone()
        },
        CellTower {
            range_m: f64::INFINITY,
            ..tower.clone()
        },
        CellTower {
            samples: 0,
            ..tower
        },
    ] {
        assert!(invalid.validated().is_err());
    }
}

#[test]
fn radio_parsing_is_ascii_case_insensitive_and_does_not_trim_input() {
    for (name, expected) in [
        ("gSm", Radio::Gsm),
        ("uMtS", Radio::Umts),
        ("lTe", Radio::Lte),
        ("nR", Radio::Nr),
        ("cDmA", Radio::Cdma),
    ] {
        assert_eq!(name.parse::<Radio>().unwrap(), expected);
        assert_eq!(expected.to_string(), name.to_ascii_uppercase());
    }
    for name in ["", " LTE", "LTE ", "ＬＴＥ", "unknown"] {
        assert_eq!(name.parse::<Radio>().unwrap_err().0, name);
    }
}

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
