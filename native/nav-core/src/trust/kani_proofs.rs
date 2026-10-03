//! First-fix policy and bounded state transitions, without geodesic assumptions or stubs.

use super::*;

fn fix() -> LocationFix {
    LocationFix {
        wall_time_ms: 0,
        elapsed_ms: 0,
        latitude_deg: 50.0,
        longitude_deg: 30.0,
        altitude_m: None,
        speed_mps: None,
        bearing_deg: None,
        horizontal_accuracy_m: None,
        vertical_accuracy_m: None,
        is_mock: false,
    }
}

#[kani::proof]
#[kani::unwind(16)]
fn first_fix_hard_reasons_cannot_be_good() {
    let mut fix = fix();
    fix.latitude_deg = f64::from_bits(kani::any());
    fix.longitude_deg = f64::from_bits(kani::any());
    fix.is_mock = kani::any();
    let inside_service_area: bool = kani::any();
    let invalid = !fix.latitude_deg.is_finite()
        || !fix.longitude_deg.is_finite()
        || !(-90.0..=90.0).contains(&fix.latitude_deg)
        || !(-180.0..=180.0).contains(&fix.longitude_deg);
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let verdict = classifier.evaluate(TrustInput {
        fix,
        network_fix: None,
        receiver: ReceiverHealth::default(),
        wall_now_ms: 0,
        compass_deg: None,
        jammed: false,
        inside_service_area,
    });
    if invalid || fix.is_mock || !inside_service_area {
        assert_eq!(verdict.level, TrustLevel::Bad);
        assert!(classifier.last_good().is_none());
    } else {
        assert_eq!(verdict.level, TrustLevel::Good);
        assert_eq!(classifier.last_good(), Some(fix));
    }
    if invalid {
        assert!(verdict.reasons.contains(&Reason::Invalid));
    }
    if fix.is_mock {
        assert!(verdict.reasons.contains(&Reason::Mock));
    }
    if !inside_service_area {
        assert!(verdict.reasons.contains(&Reason::OutsideServiceArea));
    }
    kani::cover!(verdict.level == TrustLevel::Good, "accepted");
    kani::cover!(verdict.level == TrustLevel::Bad, "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn only_good_promotes_anchor() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let old = if kani::any() { Some(fix()) } else { None };
    classifier.last_good = old;
    let new = LocationFix {
        elapsed_ms: kani::any(),
        wall_time_ms: kani::any(),
        ..fix()
    };
    let level = match kani::any::<u8>() % 3 {
        0 => TrustLevel::Good,
        1 => TrustLevel::Suspect,
        _ => TrustLevel::Bad,
    };
    classifier.commit_trusted_anchor(new, level);
    assert_eq!(
        classifier.last_good(),
        if level == TrustLevel::Good {
            Some(new)
        } else {
            old
        }
    );
    kani::cover!(level == TrustLevel::Good, "accepted");
    kani::cover!(level == TrustLevel::Suspect, "suspect");
    kani::cover!(level == TrustLevel::Bad, "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn classifier_reset_removes_history() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.last_good = Some(fix());
    classifier.previous_raw = Some(fix());
    classifier.frozen_since_ms = Some(kani::any());
    classifier.strong_jam_at_ms = Some(kani::any());
    classifier.reset();
    assert!(classifier.last_good.is_none());
    assert!(classifier.previous_raw.is_none());
    assert!(classifier.frozen_since_ms.is_none());
    assert!(classifier.strong_jam_at_ms.is_none());
    kani::cover!(true, "reset");
}

#[kani::proof]
#[kani::unwind(5)]
fn jam_sequence_reports_changes_and_ignores_missing() {
    let mut detector = JamDetector::default();
    for _ in 0..4 {
        let before = detector;
        let agc = if kani::any() {
            Some(f64::from_bits(kani::any()))
        } else {
            None
        };
        let changed = detector.update(agc, kani::any());
        assert_eq!(changed, detector.jammed() != before.jammed());
        if agc.is_none_or(|value| !value.is_finite()) {
            assert_eq!(detector, before);
        }
        if !before.jammed() && agc.is_some_and(|value| value.is_finite() && value < -12.0) {
            assert!(detector.jammed());
        }
        kani::cover!(changed, "changed");
        kani::cover!(!changed, "unchanged");
    }
    detector.reset();
    assert_eq!(detector, JamDetector::default());
}

#[kani::proof]
#[kani::unwind(2)]
fn jam_recovery_honors_hold_boundary() {
    let start: i64 = kani::any();
    kani::assume((0..=1_000_000).contains(&start));
    let duration: i64 = kani::any();
    kani::assume((0..=30_000).contains(&duration));
    let mut detector = JamDetector::default();
    assert!(detector.update(Some(-13.0), start));
    assert!(!detector.update(Some(-8.0), start));
    let changed = detector.update(Some(-8.0), start + duration);
    assert_eq!(changed, duration >= 15_000);
    assert_eq!(detector.jammed(), duration < 15_000);
    kani::cover!(duration == 14_999 && detector.jammed(), "before boundary");
    kani::cover!(duration == 15_000 && !detector.jammed(), "at boundary");
}

#[kani::proof]
#[kani::unwind(2)]
fn jam_interruption_restarts_hold() {
    let duration: i64 = kani::any();
    kani::assume((1..15_000).contains(&duration));
    let mut detector = JamDetector::default();
    detector.update(Some(-13.0), 0);
    detector.update(Some(-8.0), 0);
    assert!(!detector.update(Some(-9.0), duration));
    assert!(detector.above_since_ms.is_none());
    assert!(!detector.update(Some(-8.0), 15_000));
    assert!(detector.jammed());
    assert_eq!(detector.above_since_ms, Some(15_000));
    kani::cover!(true, "interrupted");
}
