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

#[kani::proof]
#[kani::unwind(8)]
fn coarse_observations_preserve_an_established_anchor_and_candidates() {
    let mut tracker = tracker();
    let before = tracker.clone();
    let position = f64::from_bits(kani::any());
    let accuracy = f64::from_bits(kani::any());
    kani::assume(position.is_finite() && accuracy.is_finite() && accuracy > 300.0);
    let result = tracker.gate(kani::any(), position, accuracy);
    assert_ne!(result, GateResult::Reanchored);
    assert_anchor(tracker.anchor, before.anchor);
    assert_eq!(tracker.candidates.len(), before.candidates.len());
    assert_anchor(
        tracker.candidates.first().copied(),
        before.candidates.first().copied(),
    );
    assert_eq!(tracker.recent, before.recent);
    assert_eq!(tracker.history, before.history);
    crate::speed::kani_proofs::assert_same_samples(&tracker.speed, &before.speed);
    assert_eq!(tracker.last_latitude_deg, before.last_latitude_deg);
    assert_eq!(tracker.last_longitude_deg, before.last_longitude_deg);
    kani::cover!(result == GateResult::Accepted, "confirmed");
    kani::cover!(result == GateResult::Rejected, "unreachable");
}

#[kani::proof]
#[kani::unwind(4)]
fn reanchoring_requires_span_reachability_and_direction() {
    let mut tracker = NetworkTracker::default();
    assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(1000, 2000.0, 30.0), GateResult::Rejected);
    assert_eq!(tracker.candidates.len(), 1);
    assert_eq!(tracker.anchor.unwrap().position_m, 0.0);
    let elapsed_ms = match kani::any::<u8>() % 4 {
        0 => 3000,
        1 => 12_999,
        2 => 13_000,
        _ => 14_000,
    };
    let delta = match kani::any::<u8>() % 5 {
        0 => -100.0,
        1 => -72.0,
        2 => 0.0,
        3 => 100.0,
        _ => 10_000.0,
    };
    let result = tracker.gate(elapsed_ms, 2000.0 + delta, 30.0);
    // For these integer-coordinate fixtures, delta=-100 exceeds the backward limit even at 13 s.
    // Delta=-72 becomes eligible at exactly 12 s. A 10 km jump is unreachable in every case.
    let eligible = elapsed_ms >= 13_000 && delta >= -72.0 && delta < 10_000.0;
    assert_eq!(result == GateResult::Reanchored, eligible);
    assert_ne!(result, GateResult::Accepted);
    if eligible {
        assert_eq!(tracker.anchor.unwrap().position_m, 2000.0 + delta);
        assert!(tracker.candidates.is_empty());
    } else {
        assert_eq!(tracker.anchor.unwrap().position_m, 0.0);
        assert_eq!(
            tracker.candidates.len(),
            if delta == 10_000.0 { 1 } else { 2 }
        );
    }
    kani::cover!(elapsed_ms == 12_999 && delta == 0.0, "before span");
    kani::cover!(
        elapsed_ms == 13_000 && delta == -72.0 && eligible,
        "span and direction boundary"
    );
    kani::cover!(
        elapsed_ms == 14_000 && delta == -100.0 && !eligible,
        "backward veto"
    );
    kani::cover!(delta == 10_000.0 && !eligible, "unreachable restart");
}

#[kani::proof]
#[kani::unwind(4)]
fn a_broken_candidate_chain_restarts_its_confirmation_span() {
    let mut tracker = NetworkTracker::default();
    assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(1000, 2000.0, 30.0), GateResult::Rejected);
    assert_eq!(tracker.gate(2000, 10_000.0, 30.0), GateResult::Rejected);
    assert_eq!(tracker.candidates.len(), 1);
    assert_eq!(tracker.candidates[0].time_s, 2.0);
    assert_eq!(tracker.candidates[0].position_m, 10_000.0);
    let boundary: bool = kani::any();
    let result = tracker.gate(if boundary { 14_000 } else { 13_999 }, 10_100.0, 30.0);
    assert_eq!(
        result,
        if boundary {
            GateResult::Reanchored
        } else {
            GateResult::Rejected
        }
    );
    assert_eq!(
        tracker.anchor.unwrap().position_m,
        if boundary { 10_100.0 } else { 0.0 }
    );
    assert_eq!(tracker.candidates.len(), if boundary { 0 } else { 2 });
    kani::cover!(boundary, "new span boundary");
    kani::cover!(!boundary, "old span cannot count");
}

#[kani::proof]
#[kani::unwind(8)]
fn reachable_precise_fixes_clear_abandoned_candidates() {
    let mut tracker = NetworkTracker::default();
    assert_eq!(tracker.gate(0, 0.0, 30.0), GateResult::Accepted);
    assert_eq!(tracker.gate(1000, 2000.0, 30.0), GateResult::Rejected);
    let position = f64::from(kani::any::<u8>());
    kani::assume(position <= 100.0);
    assert_eq!(tracker.gate(2000, position, 30.0), GateResult::Accepted);
    assert_eq!(tracker.anchor.unwrap().position_m, position);
    assert!(tracker.candidates.is_empty());
    kani::cover!(position == 100.0, "recovered");
}

#[kani::proof]
#[kani::unwind(8)]
fn duplicate_coordinates_cannot_add_speed_or_history_evidence() {
    let mut tracker = tracker();
    let before = tracker.clone();
    let position = f64::from_bits(kani::any());
    let accuracy = f64::from_bits(kani::any());
    kani::assume(position.is_finite() && accuracy.is_finite() && accuracy >= 0.0);
    let sample = NetworkSample {
        elapsed_ms: kani::any(),
        position_m: position,
        accuracy_m: accuracy,
        offset_m: 0.0,
    };
    tracker.record(sample, 50.0, 30.0);
    assert_eq!(tracker.history, before.history);
    crate::speed::kani_proofs::assert_same_samples(&tracker.speed, &before.speed);
    assert_anchor(tracker.anchor, before.anchor);
    assert_eq!(tracker.candidates.len(), before.candidates.len());
    assert_anchor(
        tracker.candidates.first().copied(),
        before.candidates.first().copied(),
    );
    assert_eq!(tracker.recent.len(), 2);
    let newest = tracker.recent.last().unwrap();
    assert_eq!(newest.elapsed_ms, sample.elapsed_ms);
    assert_eq!(newest.position_m.to_bits(), sample.position_m.to_bits());
    assert_eq!(newest.accuracy_m.to_bits(), sample.accuracy_m.to_bits());
    kani::cover!(sample.elapsed_ms > 1000, "new timestamp same coordinate");
    kani::cover!(sample.elapsed_ms <= 1000, "old timestamp same coordinate");
}
