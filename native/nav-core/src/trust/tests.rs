use super::*;

#[test]
fn antipodal_jump_cannot_bypass_physical_reachability() {
    for latitude in -89..=89 {
        let mut classifier = TrustClassifier::new(TrustConfig::default());
        let first = LocationFix {
            latitude_deg: f64::from(latitude),
            longitude_deg: -179.0,
            ..fix(1_000)
        };
        assert_eq!(classifier.evaluate(input(first)).level, TrustLevel::Good);
        let second = LocationFix {
            latitude_deg: -f64::from(latitude),
            longitude_deg: 1.0,
            ..fix(2_000)
        };
        let verdict = classifier.evaluate(input(second));
        assert_eq!(verdict.level, TrustLevel::Bad, "latitude={latitude}");
        assert!(verdict.reasons.contains(&Reason::Jump));
        assert_eq!(classifier.last_good(), Some(first));
    }
}

#[test]
fn malformed_network_uncertainty_cannot_confirm_hard_jamming() {
    for accuracy in [-1.0, f64::NEG_INFINITY, f64::INFINITY, f64::NAN] {
        let mut context = input(fix(1_000));
        context.receiver.agc_db = Some(-20.0);
        context.network_fix = Some(LocationFix {
            horizontal_accuracy_m: Some(accuracy),
            ..context.fix
        });
        let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(context);
        assert_eq!(verdict.level, TrustLevel::Bad);
        assert!(verdict.reasons.contains(&Reason::Jam));
        assert!(!verdict.reasons.contains(&Reason::JamStrong));
    }
}

#[test]
fn invalid_network_coordinates_do_not_supply_confirmation_or_disagreement() {
    for (latitude, longitude) in [(91.0, 30.0), (50.0, 181.0), (f64::NAN, 30.0)] {
        let mut context = input(fix(1_000));
        context.fix.speed_mps = Some(0.0);
        context.network_fix = Some(LocationFix {
            latitude_deg: latitude,
            longitude_deg: longitude,
            ..context.fix
        });
        let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(context);
        assert_eq!(verdict.level, TrustLevel::Good);
        assert_eq!(verdict.reasons, []);
    }
}

fn fix(elapsed_ms: i64) -> LocationFix {
    LocationFix {
        wall_time_ms: 1_700_000_000_000 + elapsed_ms,
        elapsed_ms,
        latitude_deg: 50.45,
        longitude_deg: 30.52,
        altitude_m: Some(150.0),
        speed_mps: Some(10.0),
        bearing_deg: Some(0.0),
        horizontal_accuracy_m: Some(5.0),
        vertical_accuracy_m: Some(3.0),
        is_mock: false,
    }
}

fn healthy(elapsed_ms: i64) -> ReceiverHealth {
    ReceiverHealth {
        satellites_visible: 20,
        satellites_used: 12,
        mean_cn0_used: Some(35.0),
        cn0_spread_used: Some(5.0),
        agc_db: Some(-2.0),
        dual_frequency_used: 0,
        elapsed_ms,
    }
}

fn input(fix: LocationFix) -> TrustInput {
    TrustInput {
        fix,
        network_fix: None,
        receiver: healthy(fix.elapsed_ms - 100),
        wall_now_ms: fix.wall_time_ms,
        compass_deg: None,
        jammed: false,
        inside_service_area: true,
    }
}

#[test]
fn clean_fix_is_good_and_becomes_anchor() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let candidate = fix(1_000);
    assert_eq!(
        classifier.evaluate(input(candidate)).level,
        TrustLevel::Good
    );
    assert_eq!(classifier.last_good(), Some(candidate));
}

#[test]
fn invalid_clock_does_not_prevent_subsequent_recovery() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    assert_eq!(
        classifier.evaluate(input(fix(1000))).level,
        TrustLevel::Good
    );
    let mut future = input(fix(1_000_000));
    future.wall_now_ms = fix(1000).wall_time_ms;
    assert_eq!(classifier.evaluate(future).level, TrustLevel::Bad);
    assert_eq!(
        classifier.evaluate(input(fix(2000))).level,
        TrustLevel::Good
    );
}

#[test]
fn delayed_agc_cannot_shorten_recovery_hold() {
    let mut detector = JamDetector::default();
    assert!(detector.update(Some(-20.0), 10_000));
    assert!(!detector.update(Some(-5.0), 1000));
    assert!(!detector.update(Some(-5.0), 20_000));
    assert!(detector.jammed());
    assert!(detector.update(Some(-5.0), 35_000));
}

#[test]
fn high_altitude_worldwide_fix_is_allowed_but_invalid_coordinates_are_not() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut high_road = fix(1_000);
    high_road.latitude_deg = 28.0;
    high_road.longitude_deg = 86.0;
    high_road.altitude_m = Some(5_200.0);
    assert_eq!(
        classifier.evaluate(input(high_road)).level,
        TrustLevel::Good
    );

    let mut invalid = fix(2_000);
    invalid.latitude_deg = 95.0;
    let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(input(invalid));
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Invalid));
}

#[test]
fn mock_and_outside_fixes_are_bad() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut candidate = fix(1_000);
    candidate.is_mock = true;
    let mut context = input(candidate);
    context.inside_service_area = false;
    let verdict = classifier.evaluate(context);
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Mock));
    assert!(verdict.reasons.contains(&Reason::OutsideServiceArea));
}

#[test]
fn non_finite_and_negative_measurements_are_bad() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut candidate = fix(1_000);
    candidate.latitude_deg = f64::NAN;
    candidate.horizontal_accuracy_m = Some(-1.0);
    let verdict = classifier.evaluate(input(candidate));
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Invalid));
}

#[test]
fn teleport_and_hard_jam_are_bad() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    assert_eq!(
        classifier.evaluate(input(fix(1_000))).level,
        TrustLevel::Good
    );
    let mut teleported = fix(2_000);
    teleported.latitude_deg += 0.1;
    assert!(
        classifier
            .evaluate(input(teleported))
            .reasons
            .contains(&Reason::Jump)
    );

    classifier.reset();
    let candidate = fix(3_000);
    let mut context = input(candidate);
    context.receiver.agc_db = Some(-20.0);
    context.jammed = true;
    let verdict = classifier.evaluate(context);
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Jam));
}

#[test]
fn flat_signal_and_heading_disagreement_are_suspect() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut candidate = fix(1_000);
    candidate.bearing_deg = Some(180.0);
    let mut context = input(candidate);
    context.receiver.cn0_spread_used = Some(0.5);
    context.compass_deg = Some(0.0);
    let verdict = classifier.evaluate(context);
    assert_eq!(verdict.level, TrustLevel::Suspect);
    assert!(verdict.reasons.contains(&Reason::FlatSignal));
    assert!(verdict.reasons.contains(&Reason::HeadingDifference));
}

#[test]
fn jamming_hysteresis_requires_a_continuous_clear_hold() {
    let mut detector = JamDetector::default();
    assert!(!detector.update(Some(-11.0), 0));
    assert!(detector.update(Some(-13.0), 1_000));
    assert!(detector.jammed());
    assert!(!detector.update(Some(-7.0), 2_000));
    assert!(!detector.update(Some(-9.0), 10_000));
    assert!(!detector.update(Some(-7.0), 11_000));
    assert!(!detector.update(Some(-7.0), 25_999));
    assert!(detector.update(Some(-7.0), 26_000));
    assert!(!detector.jammed());
}

#[test]
fn jamming_detector_ignores_missing_and_non_finite_agc() {
    let mut detector = JamDetector::default();
    assert!(!detector.update(None, 0));
    assert!(!detector.update(Some(f64::NAN), 1_000));
    assert!(!detector.jammed());
}

#[test]
fn physical_thresholds_accept_the_boundary_and_reject_beyond_it() {
    let config = TrustConfig::default();
    for (altitude, bad) in [
        (config.altitude_min_m - 3.0, false),
        (config.altitude_min_m - 3.1, true),
        (config.altitude_max_m + 3.0, false),
        (config.altitude_max_m + 3.1, true),
    ] {
        let mut candidate = fix(1000);
        candidate.altitude_m = Some(altitude);
        let verdict = TrustClassifier::new(config).evaluate(input(candidate));
        assert_eq!(verdict.reasons.contains(&Reason::Altitude), bad);
    }
    for (speed, bad) in [
        (config.max_speed_mps, false),
        (config.max_speed_mps + 0.01, true),
    ] {
        let mut candidate = fix(1000);
        candidate.speed_mps = Some(speed);
        let verdict = TrustClassifier::new(config).evaluate(input(candidate));
        assert_eq!(verdict.reasons.contains(&Reason::Speed), bad);
    }
    for (accuracy, bad) in [(100.0, false), (100.01, true)] {
        let mut candidate = fix(1000);
        candidate.horizontal_accuracy_m = Some(accuracy);
        let verdict = TrustClassifier::new(config).evaluate(input(candidate));
        assert_eq!(verdict.reasons.contains(&Reason::Accuracy), bad);
    }
    for (skew, bad) in [
        (-30_001, true),
        (-30_000, false),
        (30_000, false),
        (30_001, true),
    ] {
        let mut context = input(fix(1000));
        context.wall_now_ms += skew;
        let verdict = TrustClassifier::new(config).evaluate(context);
        assert_eq!(verdict.reasons.contains(&Reason::ClockSkew), bad);
    }
}

#[test]
fn rejected_fixes_preserve_the_trusted_anchor_and_reset_removes_history() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let anchor = fix(1000);
    assert_eq!(classifier.evaluate(input(anchor)).level, TrustLevel::Good);
    let mut inaccurate = fix(2000);
    inaccurate.horizontal_accuracy_m = Some(16.0);
    assert!(
        classifier
            .evaluate(input(inaccurate))
            .reasons
            .contains(&Reason::Accuracy)
    );
    assert_eq!(classifier.last_good(), Some(anchor));
    assert!(
        classifier
            .evaluate(input(anchor))
            .reasons
            .contains(&Reason::DuplicateTime)
    );
    classifier.reset();
    assert_eq!(classifier.last_good(), None);
    assert_eq!(classifier.evaluate(input(anchor)).level, TrustLevel::Good);
}

#[test]
fn frozen_position_escalates_at_exact_durations_and_movement_clears_it() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    for (time, expected) in [
        (1000, TrustLevel::Good),
        (5999, TrustLevel::Good),
        (6000, TrustLevel::Suspect),
        (16_000, TrustLevel::Bad),
    ] {
        let verdict = classifier.evaluate(input(fix(time)));
        assert_eq!(verdict.level, expected, "time={time}, {verdict:?}");
        assert_eq!(verdict.reasons.contains(&Reason::Frozen), time >= 6000);
    }
    let mut moving = fix(17_000);
    moving.latitude_deg += 0.0001;
    assert!(
        !classifier
            .evaluate(input(moving))
            .reasons
            .contains(&Reason::Frozen)
    );
}

#[test]
fn only_fresh_precise_network_positions_can_disagree_with_slow_gps() {
    let mut context = input(fix(10_000));
    context.fix.speed_mps = Some(1.0);
    let mut network = context.fix;
    network.latitude_deg += 0.02;
    context.network_fix = Some(network);
    assert_eq!(
        TrustClassifier::new(TrustConfig::default())
            .evaluate(context)
            .reasons,
        vec![Reason::NetworkDifference]
    );
    for kind in 0..4 {
        let mut ignored = context;
        let mut network = network;
        match kind {
            0 => network.horizontal_accuracy_m = None,
            1 => network.horizontal_accuracy_m = Some(100.0),
            2 => network.elapsed_ms -= 5001,
            _ => ignored.fix.speed_mps = Some(8.0),
        }
        ignored.network_fix = Some(network);
        assert!(
            !TrustClassifier::new(TrustConfig::default())
                .evaluate(ignored)
                .reasons
                .contains(&Reason::NetworkDifference)
        );
    }
}

#[test]
fn strong_jam_requires_independent_confirmation_then_a_short_continuous_chain() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut context = input(fix(10_000));
    context.receiver.agc_db = Some(-20.0);
    context.network_fix = Some(context.fix);
    assert_eq!(
        classifier.evaluate(context).reasons,
        vec![Reason::JamStrong]
    );
    assert_eq!(
        classifier.last_good(),
        None,
        "jammed fixes never become trusted anchors"
    );
    let mut next = input(fix(13_000));
    next.receiver.agc_db = Some(-20.0);
    assert_eq!(classifier.evaluate(next).reasons, vec![Reason::JamStrong]);
    next.fix = fix(16_001);
    next.receiver.elapsed_ms = 16_000;
    assert_eq!(classifier.evaluate(next).level, TrustLevel::Bad);
    classifier.reset();
    assert_eq!(classifier.evaluate(next).level, TrustLevel::Bad);
}

#[test]
fn dual_frequency_and_network_quality_gate_strong_jam_exception() {
    let mut context = input(fix(20_000));
    context.receiver.agc_db = Some(-20.0);
    context.receiver.satellites_used = 6;
    context.receiver.dual_frequency_used = 2;
    context.network_fix = Some(context.fix);
    assert_eq!(
        TrustClassifier::new(TrustConfig::default())
            .evaluate(context)
            .level,
        TrustLevel::Suspect
    );
    for kind in 0..6 {
        let mut invalid = context;
        let mut network = context.fix;
        match kind {
            0 => invalid.receiver.dual_frequency_used = 0,
            1 => network.horizontal_accuracy_m = None,
            2 => network.horizontal_accuracy_m = Some(151.0),
            3 => network.elapsed_ms -= 10_001,
            4 => network.latitude_deg += 0.1,
            _ => invalid.receiver.cn0_spread_used = None,
        }
        invalid.network_fix = Some(network);
        let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(invalid);
        assert!(
            verdict.reasons.contains(&Reason::Jam),
            "{kind}: {verdict:?}"
        );
    }
}

#[test]
fn receiver_checks_and_weak_jam_require_fresh_observations() {
    for (used, mean, reason, level) in [
        (0, 35.0, Reason::NoSatellites, TrustLevel::Bad),
        (4, 35.0, Reason::FewSatellites, TrustLevel::Suspect),
        (8, 19.0, Reason::WeakSignal, TrustLevel::Suspect),
    ] {
        let mut context = input(fix(10_000));
        context.receiver.satellites_used = used;
        context.receiver.mean_cn0_used = Some(mean);
        let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(context);
        assert_eq!(verdict.level, level);
        assert!(verdict.reasons.contains(&reason));
        context.receiver.elapsed_ms = 5000;
        assert_eq!(
            TrustClassifier::new(TrustConfig::default())
                .evaluate(context)
                .level,
            TrustLevel::Good
        );
        context.receiver.elapsed_ms = 10_001;
        assert_eq!(
            TrustClassifier::new(TrustConfig::default())
                .evaluate(context)
                .level,
            TrustLevel::Good
        );
    }
    for used in [4, 12] {
        let mut context = input(fix(1000));
        context.receiver.satellites_used = used;
        context.receiver.agc_db = Some(-11.0);
        let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(context);
        assert!(verdict.reasons.contains(&Reason::JamWeak));
        assert_eq!(
            verdict.level,
            if used == 4 {
                TrustLevel::Bad
            } else {
                TrustLevel::Suspect
            }
        );
    }
    let mut detector = JamDetector::default();
    detector.update(Some(-20.0), 1000);
    detector.reset();
    assert!(!detector.jammed());
    assert!(!detector.update(Some(-8.0), 2000));
}

#[test]
fn extreme_sequence_timestamps_reject_without_overflow() {
    for (previous_ms, current_ms, frozen) in
        [(i64::MIN, i64::MAX, true), (i64::MAX, i64::MIN, false)]
    {
        let mut classifier = TrustClassifier::new(TrustConfig::default());
        let mut previous = fix(0);
        previous.elapsed_ms = previous_ms;
        classifier.previous_raw = Some(previous);
        let mut current = fix(1000);
        current.elapsed_ms = current_ms;
        let mut context = input(fix(1000));
        context.fix = current;
        context.receiver = ReceiverHealth::default();
        let verdict = classifier.evaluate(context);
        assert_eq!(verdict.level, TrustLevel::Bad);
        assert_eq!(verdict.reasons.contains(&Reason::Frozen), frozen);
        assert_eq!(verdict.reasons.contains(&Reason::DuplicateTime), !frozen);
        assert!(classifier.last_good().is_none());
    }
}

#[test]
fn extreme_anchor_age_does_not_overflow_reachability() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let mut previous = fix(0);
    previous.elapsed_ms = i64::MIN;
    classifier.last_good = Some(previous);
    let mut current = fix(1000);
    current.elapsed_ms = i64::MAX;
    current.speed_mps = None;
    let mut context = input(fix(1000));
    context.fix = current;
    context.receiver = ReceiverHealth::default();
    assert_eq!(classifier.evaluate(context).level, TrustLevel::Good);
    assert_eq!(classifier.last_good(), Some(current));
}

#[test]
fn extreme_receiver_age_is_stale_without_overflow() {
    let mut candidate = fix(1000);
    candidate.elapsed_ms = i64::MIN;
    candidate.is_mock = true;
    let mut context = input(fix(1000));
    context.fix = candidate;
    context.receiver = healthy(1);
    context.receiver.agc_db = Some(-20.0);
    let verdict = TrustClassifier::new(TrustConfig::default()).evaluate(context);
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Mock));
    assert!(!verdict.reasons.contains(&Reason::Jam));
    assert!(!verdict.reasons.contains(&Reason::JamStrong));
}

#[test]
fn extreme_jam_chain_age_cannot_extend_confirmation() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    classifier.strong_jam_at_ms = Some(i64::MIN);
    let mut context = input(fix(5000));
    context.receiver.agc_db = Some(-20.0);
    let verdict = classifier.evaluate(context);
    assert_eq!(verdict.level, TrustLevel::Bad);
    assert!(verdict.reasons.contains(&Reason::Jam));
    assert!(classifier.strong_jam_at_ms.is_none());
}

#[test]
fn rejected_time_does_not_rewind_sequence_or_detector_state() {
    let mut classifier = TrustClassifier::new(TrustConfig::default());
    let latest = fix(10_000);
    classifier.evaluate(input(latest));
    classifier.frozen_since_ms = Some(1000);
    classifier.strong_jam_at_ms = Some(9000);
    for rejected in [
        fix(9000),
        latest,
        LocationFix {
            wall_time_ms: latest.wall_time_ms,
            ..fix(11_000)
        },
    ] {
        let verdict = classifier.evaluate(input(rejected));
        assert_eq!(verdict.level, TrustLevel::Bad);
        assert_eq!(verdict.reasons, vec![Reason::DuplicateTime]);
        assert_eq!(classifier.previous_raw, Some(latest));
        assert_eq!(classifier.last_good(), Some(latest));
        assert_eq!(classifier.frozen_since_ms, Some(1000));
        assert_eq!(classifier.strong_jam_at_ms, Some(9000));
    }
    classifier.reset();
    assert_eq!(
        classifier.evaluate(input(fix(1000))).level,
        TrustLevel::Good
    );
}
