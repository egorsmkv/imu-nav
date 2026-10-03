//! Network ingress/reset contracts, with at most one stored sample and candidate.

use super::*;

fn tracker() -> NetworkTracker {
    let anchor = Anchor {
        time_s: 1.0,
        position_m: 10.0,
        accuracy_m: 20.0,
    };
    let sample = NetworkSample {
        elapsed_ms: 1000,
        position_m: 10.0,
        accuracy_m: 20.0,
        offset_m: 0.0,
    };
    let mut tracker = NetworkTracker::default();
    tracker.anchor = Some(anchor);
    tracker.candidates.push(anchor);
    tracker.record(sample, 50.0, 30.0);
    tracker
}

fn assert_anchor(actual: Option<Anchor>, expected: Option<Anchor>) {
    match (actual, expected) {
        (Some(actual), Some(expected)) => {
            assert_eq!(actual.time_s, expected.time_s);
            assert_eq!(actual.position_m, expected.position_m);
            assert_eq!(actual.accuracy_m, expected.accuracy_m);
        }
        (None, None) => {}
        _ => panic!("anchor changed"),
    }
}

#[kani::proof]
#[kani::unwind(8)]
fn invalid_gate_preserves_network_state() {
    let mut tracker = tracker();
    if kani::any() {
        tracker.anchor = None;
    }
    let before = tracker.clone();
    let position = f64::from_bits(kani::any());
    let accuracy = f64::from_bits(kani::any());
    kani::assume(!position.is_finite() || !accuracy.is_finite() || accuracy < 0.0);
    assert_eq!(
        tracker.gate(kani::any(), position, accuracy),
        GateResult::Rejected
    );
    assert_anchor(tracker.anchor, before.anchor);
    assert_eq!(tracker.candidates.len(), before.candidates.len());
    assert_anchor(
        tracker.candidates.first().copied(),
        before.candidates.first().copied(),
    );
    assert_eq!(tracker.recent, before.recent);
    assert_eq!(tracker.history, before.history);
    assert_eq!(tracker.last_latitude_deg, before.last_latitude_deg);
    assert_eq!(tracker.last_longitude_deg, before.last_longitude_deg);
    kani::cover!(true, "rejected");
}

#[kani::proof]
#[kani::unwind(2)]
fn coarse_fix_cannot_establish_anchor() {
    let mut tracker = NetworkTracker::default();
    let accuracy = f64::from_bits(kani::any());
    kani::assume(accuracy.is_finite() && accuracy > COARSE_ACCURACY_M);
    let position = f64::from_bits(kani::any());
    kani::assume(position.is_finite());
    assert_eq!(
        tracker.gate(kani::any(), position, accuracy),
        GateResult::Rejected
    );
    assert!(tracker.anchor.is_none());
    assert!(tracker.candidates.is_empty());
    kani::cover!(true, "rejected");
}

#[kani::proof]
#[kani::unwind(8)]
fn reset_and_clear_remove_samples() {
    let mut tracker = tracker();
    let anchor = tracker.anchor;
    tracker.clear_samples();
    crate::speed::kani_proofs::assert_empty(&tracker.speed);
    assert!(tracker.recent().is_empty());
    assert!(tracker.history().is_empty());
    assert!(tracker.speed_estimate(1000).is_none());
    assert!(tracker.strict_speed_estimate(1000).is_none());
    assert_anchor(tracker.anchor, anchor);
    assert_eq!(tracker.candidates.len(), 1);
    assert_eq!(tracker.last_latitude_deg, Some(50.0));
    assert_eq!(tracker.last_longitude_deg, Some(30.0));
    // Reset must remove populated samples independently of clear_samples.
    tracker = self::tracker();
    tracker.reset();
    crate::speed::kani_proofs::assert_empty(&tracker.speed);
    assert!(tracker.anchor.is_none());
    assert!(tracker.candidates.is_empty());
    assert!(tracker.recent().is_empty());
    assert!(tracker.history().is_empty());
    assert!(tracker.speed_estimate(1000).is_none());
    assert!(tracker.strict_speed_estimate(1000).is_none());
    assert!(tracker.last_latitude_deg.is_none());
    assert!(tracker.last_longitude_deg.is_none());
    kani::cover!(true, "reset");
}
