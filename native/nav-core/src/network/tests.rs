use super::*;

#[test]
fn rejects_impossible_fix_then_reanchors_on_consistent_evidence() {
    let mut tracker = NetworkTracker::default();
    assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(1_000, 2_000.0, 30.0), GateResult::Rejected);
    assert_eq!(tracker.gate(14_000, 2_100.0, 30.0), GateResult::Reanchored);
    assert_eq!(tracker.gate(15_000, 2_110.0, 30.0), GateResult::Accepted);
}

#[test]
fn coarse_fix_can_confirm_but_not_create_anchor() {
    let mut tracker = NetworkTracker::default();
    assert_eq!(tracker.gate(0, 0.0, 500.0), GateResult::Rejected);
    assert_eq!(tracker.gate(1_000, 10.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(2_000, 20.0, 500.0), GateResult::Accepted);
}

#[test]
fn records_recent_history_and_ignores_duplicate_for_speed() {
    let mut tracker = NetworkTracker::default();
    for index in 0_i32..6 {
        tracker.record(
            NetworkSample {
                elapsed_ms: i64::from(index) * 10_000,
                position_m: f64::from(index) * 100.0,
                accuracy_m: 30.0,
                offset_m: 5.0,
            },
            50.0 + f64::from(index) / 1000.0,
            30.0,
        );
    }
    assert_eq!(tracker.recent().len(), 4);
    assert_eq!(tracker.history().len(), 6);
    assert!(tracker.last_two_consistent());
    assert!((tracker.speed_estimate(50_000).unwrap().speed_mps - 10.0).abs() < 0.1);
    tracker.prune_history(80_001);
    assert_eq!(tracker.history().len(), 0);
}

#[test]
fn invalid_observations_and_unreachable_coarse_fixes_do_not_replace_anchor() {
    let mut tracker = NetworkTracker::default();
    assert!(!tracker.last_two_consistent());
    assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
    for (position, accuracy) in [
        (f64::NAN, 1.0),
        (1.0, f64::INFINITY),
        (1.0, -1.0),
        (10_000.0, 500.0),
    ] {
        assert_eq!(tracker.gate(1000, position, accuracy), GateResult::Rejected);
    }
    assert_eq!(tracker.gate(2000, 20.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(3000, 2000.0, 30.0), GateResult::Rejected);
    assert_eq!(
        tracker.gate(4000, 2010.0, 30.0),
        GateResult::Rejected,
        "too little time to re-anchor"
    );
    assert_eq!(tracker.gate(15_000, 2100.0, 30.0), GateResult::Reanchored);
    tracker.reset();
    assert_eq!(tracker.gate(16_000, -20_000.0, 30.0), GateResult::Accepted);
}

#[test]
fn duplicate_and_invalid_samples_do_not_teach_speed_and_clear_removes_estimates() {
    let mut tracker = NetworkTracker::default();
    for index in 0_i32..=20 {
        let sample = NetworkSample {
            elapsed_ms: i64::from(index) * 5000,
            position_m: f64::from(index) * 50.0,
            accuracy_m: 20.0,
            offset_m: 0.0,
        };
        tracker.record(sample, 50.0 + f64::from(index) * 0.001, 30.0);
    }
    assert!(tracker.strict_speed_estimate(100_000).is_some());
    let history = tracker.history().len();
    let last = *tracker.recent().last().unwrap();
    tracker.record(last, 50.02, 30.0);
    assert_eq!(tracker.history().len(), history);
    assert!(
        !tracker.last_two_consistent(),
        "equal timestamps are not independent evidence"
    );
    tracker.record(
        NetworkSample {
            position_m: f64::NAN,
            ..last
        },
        50.0,
        30.0,
    );
    assert_eq!(tracker.history().len(), history);
    tracker.clear_samples();
    assert_eq!(tracker.history().len(), 0);
    assert_eq!(tracker.recent().len(), 0);
    assert!(tracker.speed_estimate(100_000).is_none());
    assert!(tracker.strict_speed_estimate(100_000).is_none());
    let same_time = [Anchor {
        time_s: 1.0,
        position_m: 1.0,
        accuracy_m: 1.0,
    }; 2];
    assert_eq!(slope(&same_time), 0.0);
}

#[test]
fn reanchor_span_and_reverse_direction_boundaries() {
    for (time, delta, expected) in [
        (12_999, 0.0, GateResult::Rejected),
        (13_000, -72.0, GateResult::Reanchored),
        (13_000, -100.0, GateResult::Rejected),
        (13_000, 100.0, GateResult::Reanchored),
        (14_000, -100.0, GateResult::Rejected),
    ] {
        let mut tracker = NetworkTracker::default();
        assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
        assert_eq!(tracker.gate(1000, 2000.0, 30.0), GateResult::Rejected);
        assert_eq!(tracker.gate(time, 2000.0 + delta, 30.0), expected);
    }
}
