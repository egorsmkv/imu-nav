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

fn context(fix: LocationFix) -> TrustInput {
    TrustInput {
        wall_now_ms: fix.wall_time_ms,
        fix,
        network_fix: None,
        receiver: ReceiverHealth::default(),
        compass_deg: None,
        jammed: false,
        inside_service_area: true,
    }
}

#[kani::proof]
#[kani::unwind(8)]
fn clock_skew_uses_full_signed_timestamp_range() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.config.max_clock_skew_ms = kani::any();
    let candidate = LocationFix {
        wall_time_ms: kani::any(),
        ..fix()
    };
    let mut input = context(candidate);
    input.wall_now_ms = kani::any();
    let mut hard = Vec::with_capacity(2);
    classifier.check_fix(&input, &mut hard);
    let difference = (i128::from(candidate.wall_time_ms) - i128::from(input.wall_now_ms)).abs();
    let limit = i128::from(classifier.config.max_clock_skew_ms.max(0));
    assert_eq!(hard.contains(&Reason::ClockSkew), difference > limit);
    assert_eq!(hard.len(), usize::from(difference > limit));
    kani::cover!(difference == limit, "exact limit");
    kani::cover!(difference > i128::from(i64::MAX), "wide difference");
    kani::cover!(
        classifier.config.max_clock_skew_ms < 0 && difference == 0,
        "negative limit"
    );
}

#[kani::proof]
#[kani::unwind(5)]
fn sequence_order_and_frozen_duration_are_overflow_safe() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.last_good = Some(fix());
    classifier.frozen_since_ms = kani::any::<bool>().then(|| kani::any());
    let previous = LocationFix {
        wall_time_ms: kani::any(),
        elapsed_ms: kani::any(),
        ..fix()
    };
    let candidate = LocationFix {
        wall_time_ms: kani::any(),
        elapsed_ms: kani::any(),
        speed_mps: Some(10.0),
        ..fix()
    };
    let since = classifier.frozen_since_ms.unwrap_or(previous.elapsed_ms);
    let duration = i128::from(candidate.elapsed_ms) - i128::from(since);
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_sequence(candidate, Some(previous), &mut hard, &mut soft);
    assert_eq!(
        hard.contains(&Reason::DuplicateTime),
        candidate.elapsed_ms <= previous.elapsed_ms
            || candidate.wall_time_ms <= previous.wall_time_ms
    );
    assert_eq!(hard.contains(&Reason::Frozen), duration >= 15_000);
    assert_eq!(
        soft.contains(&Reason::Frozen),
        (5000..15_000).contains(&duration)
    );
    assert_eq!(classifier.frozen_since_ms, Some(since));
    assert_eq!(classifier.last_good(), Some(fix()));
    kani::cover!(duration > i128::from(i64::MAX), "wide forward duration");
    kani::cover!(duration < i128::from(i64::MIN), "wide backward duration");
    kani::cover!(soft.contains(&Reason::Frozen), "suspect");
    kani::cover!(
        hard.contains(&Reason::DuplicateTime),
        "duplicate or backward"
    );
}

#[kani::proof]
#[kani::unwind(5)]
fn frozen_sequence_escalates_at_exact_thresholds() {
    let start: i64 = kani::any();
    kani::assume((0..=i64::MAX - 20_000).contains(&start));
    let duration = i64::from(kani::any::<u16>());
    kani::assume((2..=20_000).contains(&duration));
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let previous = LocationFix {
        elapsed_ms: start,
        wall_time_ms: start,
        speed_mps: Some(10.0),
        ..fix()
    };
    let first = LocationFix {
        elapsed_ms: start + 1,
        wall_time_ms: start + 1,
        ..previous
    };
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_sequence(first, Some(previous), &mut hard, &mut soft);
    assert!(hard.is_empty() && soft.is_empty());
    assert_eq!(
        classifier.finish_verdict(first, hard, soft).level,
        TrustLevel::Good
    );
    let candidate = LocationFix {
        elapsed_ms: start + duration,
        wall_time_ms: start + duration,
        ..previous
    };
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_sequence(candidate, Some(first), &mut hard, &mut soft);
    let verdict = classifier.finish_verdict(candidate, hard, soft);
    let expected = if duration >= 15_000 {
        TrustLevel::Bad
    } else if duration >= 5000 {
        TrustLevel::Suspect
    } else {
        TrustLevel::Good
    };
    assert_eq!(verdict.level, expected);
    assert_eq!(
        classifier.last_good(),
        Some(if expected == TrustLevel::Good {
            candidate
        } else {
            first
        })
    );
    assert_eq!(classifier.frozen_since_ms, Some(start));
    kani::cover!(duration == 4999, "before suspect");
    kani::cover!(duration == 5000, "suspect boundary");
    kani::cover!(duration == 14_999, "before bad");
    kani::cover!(duration == 15_000, "bad boundary");
}

#[kani::proof]
#[kani::unwind(5)]
fn movement_or_low_speed_restarts_frozen_detection() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.frozen_since_ms = Some(0);
    let kind: u8 = kani::any();
    kani::assume(kind < 3);
    let low_speed = f64::from_bits(kani::any());
    kani::assume((0.0..=3.0).contains(&low_speed));
    let previous = LocationFix {
        elapsed_ms: 19_000,
        wall_time_ms: 19_000,
        speed_mps: Some(10.0),
        ..fix()
    };
    let candidate = LocationFix {
        elapsed_ms: 20_000,
        wall_time_ms: 20_000,
        latitude_deg: if kind == 0 { 50.01 } else { 50.0 },
        speed_mps: match kind {
            0 => Some(10.0),
            1 => Some(low_speed),
            _ => None,
        },
        ..fix()
    };
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_sequence(candidate, Some(previous), &mut hard, &mut soft);
    assert!(classifier.frozen_since_ms.is_none());
    assert!(hard.is_empty() && soft.is_empty());
    let next = LocationFix {
        elapsed_ms: 21_000,
        wall_time_ms: 21_000,
        speed_mps: Some(10.0),
        ..candidate
    };
    classifier.check_sequence(next, Some(candidate), &mut hard, &mut soft);
    assert_eq!(classifier.frozen_since_ms, Some(20_000));
    assert!(hard.is_empty() && soft.is_empty());
    kani::cover!(kind == 0, "movement");
    kani::cover!(kind == 1 && low_speed == 3.0, "speed boundary");
    kani::cover!(kind == 2, "missing speed");
}

#[kani::proof]
#[kani::unwind(5)]
fn hard_and_soft_reasons_control_verdict_and_anchor() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.last_good = Some(fix());
    classifier.previous_raw = Some(fix());
    classifier.frozen_since_ms = Some(1000);
    classifier.strong_jam_at_ms = Some(2000);
    let candidate = LocationFix {
        elapsed_ms: kani::any(),
        wall_time_ms: kani::any(),
        ..fix()
    };
    let has_hard: bool = kani::any();
    let has_soft: bool = kani::any();
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    if has_hard {
        hard.push(Reason::DuplicateTime);
    }
    if has_soft {
        soft.push(Reason::JamStrong);
    }
    let verdict = classifier.finish_verdict(candidate, hard, soft);
    let expected = if has_hard {
        TrustLevel::Bad
    } else if has_soft {
        TrustLevel::Suspect
    } else {
        TrustLevel::Good
    };
    assert_eq!(verdict.level, expected);
    assert_eq!(verdict.reasons.contains(&Reason::DuplicateTime), has_hard);
    assert_eq!(verdict.reasons.contains(&Reason::JamStrong), has_soft);
    assert_eq!(
        verdict.reasons.len(),
        usize::from(has_hard) + usize::from(has_soft)
    );
    assert_eq!(
        classifier.last_good(),
        Some(if expected == TrustLevel::Good {
            candidate
        } else {
            fix()
        })
    );
    assert_eq!(classifier.previous_raw, Some(fix()));
    assert_eq!(classifier.frozen_since_ms, Some(1000));
    assert_eq!(classifier.strong_jam_at_ms, Some(2000));
    kani::cover!(has_hard && has_soft, "hard dominates");
    kani::cover!(!has_hard && has_soft, "suspect keeps anchor");
    kani::cover!(!has_hard && !has_soft, "good promotes");
}

#[kani::proof]
#[kani::unwind(2)]
fn receiver_freshness_rejects_future_stale_and_overflowing_ages() {
    let candidate = LocationFix {
        elapsed_ms: kani::any(),
        ..fix()
    };
    let receiver = ReceiverHealth {
        elapsed_ms: kani::any(),
        ..ReceiverHealth::default()
    };
    let age = i128::from(candidate.elapsed_ms) - i128::from(receiver.elapsed_ms);
    let fresh = receiver_fresh(candidate, receiver);
    assert_eq!(fresh, receiver.elapsed_ms > 0 && (0..5000).contains(&age));
    kani::cover!(age == 4999 && fresh, "fresh boundary");
    kani::cover!(age == 5000 && !fresh, "stale boundary");
    kani::cover!(age < i128::from(i64::MIN) && !fresh, "overflowing future");
    kani::cover!(receiver.elapsed_ms == 0 && !fresh, "missing timestamp");
}

#[kani::proof]
#[kani::unwind(6)]
fn receiver_reason_thresholds_require_fresh_data() {
    let classifier = TrustClassifier::new(TrustConfig::default());
    let mut input = context(LocationFix {
        elapsed_ms: 10_000,
        ..fix()
    });
    input.receiver = ReceiverHealth {
        elapsed_ms: kani::any(),
        satellites_used: kani::any(),
        satellites_visible: kani::any(),
        mean_cn0_used: kani::any::<bool>().then(|| f64::from_bits(kani::any())),
        cn0_spread_used: kani::any::<bool>().then(|| f64::from_bits(kani::any())),
        ..ReceiverHealth::default()
    };
    let fresh = (5001..=10_000).contains(&input.receiver.elapsed_ms);
    let mut hard = Vec::with_capacity(1);
    let mut soft = Vec::with_capacity(3);
    classifier.check_receiver(&input, &mut hard, &mut soft);
    let used = input.receiver.satellites_used;
    assert_eq!(
        hard.contains(&Reason::NoSatellites),
        fresh && used == 0 && input.receiver.satellites_visible > 0
    );
    assert_eq!(
        soft.contains(&Reason::FewSatellites),
        fresh && (1..5).contains(&used)
    );
    assert_eq!(
        soft.contains(&Reason::WeakSignal),
        fresh
            && input
                .receiver
                .mean_cn0_used
                .is_some_and(|value| value < 20.0)
    );
    assert_eq!(
        soft.contains(&Reason::FlatSignal),
        fresh
            && used >= 4
            && input
                .receiver
                .cn0_spread_used
                .is_some_and(|value| value < 1.5)
    );
    if !fresh {
        assert!(hard.is_empty() && soft.is_empty());
    }
    kani::cover!(fresh && used == 0 && !hard.is_empty(), "no satellites");
    kani::cover!(
        fresh && used == 4 && soft.contains(&Reason::FewSatellites),
        "few boundary"
    );
    kani::cover!(
        fresh && used == 5 && !soft.contains(&Reason::FewSatellites),
        "enough boundary"
    );
    kani::cover!(
        input.receiver.elapsed_ms == 5000 && hard.is_empty() && soft.is_empty(),
        "stale ignored"
    );
}

#[kani::proof]
#[kani::unwind(2)]
fn strong_constellation_requires_every_quality_threshold() {
    let classifier = TrustClassifier::new(TrustConfig::default());
    let receiver = ReceiverHealth {
        satellites_used: kani::any(),
        dual_frequency_used: kani::any(),
        mean_cn0_used: kani::any::<bool>().then(|| f64::from_bits(kani::any())),
        cn0_spread_used: kani::any::<bool>().then(|| f64::from_bits(kani::any())),
        ..ReceiverHealth::default()
    };
    let expected = receiver.satellites_used
        >= if receiver.dual_frequency_used >= 2 {
            6
        } else {
            8
        }
        && receiver.mean_cn0_used.is_some_and(|value| value >= 25.0)
        && receiver.cn0_spread_used.is_some_and(|value| value >= 3.0);
    let healthy = classifier.healthy_constellation(receiver);
    assert_eq!(healthy, expected);
    kani::cover!(
        healthy && receiver.satellites_used == 6 && receiver.dual_frequency_used == 2,
        "dual threshold"
    );
    kani::cover!(
        healthy && receiver.satellites_used == 8 && receiver.dual_frequency_used == 1,
        "single threshold"
    );
    kani::cover!(
        !healthy && receiver.mean_cn0_used.is_none(),
        "missing signal"
    );
    kani::cover!(
        !healthy && receiver.cn0_spread_used.is_none(),
        "missing spread"
    );
}

#[kani::proof]
#[kani::unwind(2)]
fn independent_confirmation_requires_age_and_precision() {
    let classifier = TrustClassifier::new(TrustConfig::default());
    let candidate = LocationFix {
        elapsed_ms: kani::any(),
        ..fix()
    };
    let network = LocationFix {
        elapsed_ms: kani::any(),
        horizontal_accuracy_m: kani::any::<bool>().then(|| f64::from_bits(kani::any())),
        ..fix()
    };
    let age = (i128::from(candidate.elapsed_ms) - i128::from(network.elapsed_ms)).abs();
    let eligible = classifier.network_confirmation_eligible(candidate, network);
    assert_eq!(
        eligible,
        age <= 10_000
            && network
                .horizontal_accuracy_m
                .is_some_and(|value| value.is_finite() && (0.0..=150.0).contains(&value))
    );
    kani::cover!(
        eligible && age == 10_000 && network.horizontal_accuracy_m == Some(150.0),
        "eligibility boundary"
    );
    kani::cover!(!eligible && age == 10_001, "expired");
    kani::cover!(
        !eligible && network.horizontal_accuracy_m.is_none(),
        "missing precision"
    );
}

fn jam_context(now: i64) -> TrustInput {
    let mut input = context(LocationFix {
        elapsed_ms: now,
        wall_time_ms: now,
        ..fix()
    });
    input.receiver = ReceiverHealth {
        elapsed_ms: now,
        satellites_used: 8,
        satellites_visible: 12,
        mean_cn0_used: Some(25.0),
        cn0_spread_used: Some(3.0),
        agc_db: Some(-20.0),
        ..ReceiverHealth::default()
    };
    input
}

#[kani::proof]
#[kani::unwind(4)]
fn strong_jam_policy_requires_fresh_healthy_confirmation() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.last_good = Some(fix());
    let now: i64 = kani::any();
    kani::assume(now > 0);
    let mut input = jam_context(now);
    let healthy: bool = kani::any();
    if !healthy {
        input.receiver.satellites_used = 7;
    }
    let confirmed: bool = kani::any();
    classifier.strong_jam_at_ms = kani::any::<bool>().then(|| kani::any());
    let old = classifier.strong_jam_at_ms;
    let chain = old.is_some_and(|time| (1..=3000).contains(&(i128::from(now) - i128::from(time))));
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_jamming_policy(&input, confirmed, &mut hard, &mut soft);
    let allowed = healthy && (confirmed || chain);
    assert_eq!(hard.contains(&Reason::Jam), !allowed);
    assert_eq!(soft.contains(&Reason::JamStrong), allowed);
    assert_eq!(
        classifier.strong_jam_at_ms,
        if allowed { Some(now) } else { None }
    );
    let verdict = classifier.finish_verdict(input.fix, hard, soft);
    assert_eq!(
        verdict.level,
        if allowed {
            TrustLevel::Suspect
        } else {
            TrustLevel::Bad
        }
    );
    assert_eq!(classifier.last_good(), Some(fix()));
    kani::cover!(
        healthy && confirmed && old.is_none() && allowed,
        "independent seed"
    );
    kani::cover!(
        old == Some(now - 3000) && now > 3000 && !confirmed && allowed,
        "chain boundary"
    );
    kani::cover!(
        old == Some(now - 3001) && now > 3001 && !confirmed && !allowed,
        "chain expired"
    );
    kani::cover!(!healthy && confirmed && !allowed, "unhealthy veto");
}

#[kani::proof]
#[kani::unwind(5)]
fn rejected_jam_confirmation_breaks_the_chain() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(2);
    classifier.check_jamming_policy(&jam_context(1000), true, &mut hard, &mut soft);
    assert_eq!(classifier.strong_jam_at_ms, Some(1000));
    assert!(hard.is_empty());
    soft.clear();
    let unhealthy: bool = kani::any();
    let mut second = jam_context(if unhealthy { 2000 } else { 4001 });
    if unhealthy {
        second.receiver.cn0_spread_used = Some(2.0);
    }
    classifier.check_jamming_policy(&second, false, &mut hard, &mut soft);
    assert!(hard.contains(&Reason::Jam) && soft.is_empty());
    assert!(classifier.strong_jam_at_ms.is_none());
    hard.clear();
    let third = jam_context(second.fix.elapsed_ms + 1);
    classifier.check_jamming_policy(&third, false, &mut hard, &mut soft);
    assert!(hard.contains(&Reason::Jam) && soft.is_empty());
    assert!(classifier.strong_jam_at_ms.is_none());
    kani::cover!(unhealthy, "health interruption");
    kani::cover!(!unhealthy, "expired interruption");
}

#[kani::proof]
#[kani::unwind(5)]
fn stale_receivers_and_agc_boundaries_cannot_grant_good_jammed_fixes() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut input = jam_context(10_000);
    input.receiver.elapsed_ms = kani::any();
    input.receiver.agc_db = match kani::any::<u8>() % 5 {
        0 => None,
        1 => Some(-20.0),
        2 => Some(-16.0),
        3 => Some(-10.0),
        _ => Some(-11.0),
    };
    input.receiver.satellites_used = kani::any::<u16>();
    input.jammed = kani::any();
    let confirmed: bool = kani::any();
    let mut hard = Vec::with_capacity(2);
    let mut soft = Vec::with_capacity(1);
    classifier.check_jamming_policy(&input, confirmed, &mut hard, &mut soft);
    let fresh = (5001..=10_000).contains(&input.receiver.elapsed_ms);
    let hard_jam = fresh && input.receiver.agc_db.is_some_and(|value| value < -16.0);
    let weak_jam = !hard_jam
        && (input.jammed || (fresh && input.receiver.agc_db.is_some_and(|value| value < -10.0)));
    let strong = hard_jam && input.receiver.satellites_used >= 8 && confirmed;
    assert_eq!(soft.contains(&Reason::JamStrong), strong);
    assert_eq!(hard.contains(&Reason::Jam), hard_jam && !strong);
    assert_eq!(
        hard.contains(&Reason::JamWeak),
        weak_jam && fresh && input.receiver.satellites_used < 5
    );
    assert_eq!(
        soft.contains(&Reason::JamWeak),
        weak_jam && (!fresh || input.receiver.satellites_used >= 5)
    );
    let verdict = classifier.finish_verdict(input.fix, hard, soft);
    if hard_jam || weak_jam {
        assert_ne!(verdict.level, TrustLevel::Good);
    }
    if !fresh {
        assert!(classifier.strong_jam_at_ms.is_none());
    }
    kani::cover!(
        fresh && input.receiver.agc_db == Some(-16.0) && weak_jam,
        "hard threshold is weak"
    );
    kani::cover!(
        fresh
            && input.receiver.agc_db == Some(-10.0)
            && !input.jammed
            && verdict.level == TrustLevel::Good,
        "weak threshold clear"
    );
    kani::cover!(
        !fresh && input.jammed && verdict.level == TrustLevel::Suspect,
        "stale jam indication"
    );
    kani::cover!(
        input.receiver.elapsed_ms > 10_000 && !strong,
        "future cannot confirm"
    );
}
