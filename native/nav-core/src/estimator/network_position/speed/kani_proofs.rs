//! Complete state inspection without slice-equality loops inflating unrelated algorithm bounds.
//! This compares the same fields as derived PartialEq; no production operation is replaced.
use super::SpeedBatch;

impl PartialEq for SpeedBatch {
    fn eq(&self, other: &Self) -> bool {
        // Exhaustive destructuring forces a proof update whenever a state field is added.
        let Self {
            samples,
            count,
            reserve_next,
            prior_speed_mps,
            previous_reserved,
            previous_departure,
            recovery_until_ms,
            reference_speed_mps,
            previous_change_departure,
            relearning,
        } = self;
        (
            (
                &samples[0],
                &samples[1],
                &samples[2],
                &samples[3],
                &samples[4],
                &samples[5],
                &samples[6],
            ),
            count,
            reserve_next,
            prior_speed_mps,
            previous_reserved,
            previous_departure,
            recovery_until_ms,
            reference_speed_mps,
            previous_change_departure,
            relearning,
        ) == (
            (
                &other.samples[0],
                &other.samples[1],
                &other.samples[2],
                &other.samples[3],
                &other.samples[4],
                &other.samples[5],
                &other.samples[6],
            ),
            &other.count,
            &other.reserve_next,
            &other.prior_speed_mps,
            &other.previous_reserved,
            &other.previous_departure,
            &other.recovery_until_ms,
            &other.reference_speed_mps,
            &other.previous_change_departure,
            &other.relearning,
        )
    }
}

use super::*;

fn sample(time: i64, position_m: f64) -> Candidate {
    Candidate {
        first_ms: 0,
        last_ms: time,
        position_m,
        accuracy_m: 30.0,
        residual_m: 0.0,
        count: 10,
    }
}

/// Initial partial state for rejection proofs; production allocation establishes the three slots.
pub(in crate::estimator::network_position) fn partial_batch() -> SpeedBatch {
    let mut batch = SpeedBatch::new();
    for index in 0..5 {
        let candidate = sample(i64::from(index) * 5000, f64::from(index) * 60.0);
        if batch.reserve_for_speed(candidate.last_ms) {
            assert!(!batch.push_sample(candidate));
        }
    }
    batch
}

/// Drive the production bookkeeping at its fit-result boundary. Fitting itself is not modeled
/// or replaced; its numerical implementation is outside this bookkeeping proof.
fn allocate_and_finish(
    batch: &mut SpeedBatch,
    candidate: Candidate,
    estimate: Option<SpeedEstimate>,
) -> NetworkUse {
    if !batch.reserve_for_speed(candidate.last_ms) {
        return NetworkUse::Position;
    }
    if !batch.push_sample(candidate) {
        return NetworkUse::Reserved;
    }
    batch.finish_batch(estimate, candidate.last_ms)
}

fn two_disjoint_batches(fit_succeeds: bool) {
    let mut batch = SpeedBatch::new();
    for index in 0..15 {
        let time = i64::from(index) * 5000;
        let position = if index % 2 == 0 {
            f64::from(index) * 60.0
        } else {
            f64::from_bits(kani::any())
        };
        let before = batch.clone();
        let estimate = fit_succeeds.then_some(SpeedEstimate {
            speed_mps: 12.0,
            sigma_mps: 2.0,
            samples: 4,
            span_s: 30.0,
        });
        let outcome = allocate_and_finish(&mut batch, sample(time, position), estimate);
        if index % 2 == 1 {
            assert!(matches!(outcome, NetworkUse::Position));
            batch.reserve_next = before.reserve_next;
            assert!(batch == before);
            batch.reserve_next = true;
        } else if index % 8 == 6 {
            assert_eq!(matches!(outcome, NetworkUse::Speed(_)), fit_succeeds);
            assert!(!matches!(outcome, NetworkUse::Position));
            assert_eq!(batch.count, 0);
            assert!(batch.samples.iter().all(Option::is_none));
        } else {
            assert!(matches!(outcome, NetworkUse::Reserved));
            assert_eq!(batch.count, usize::try_from(index % 8 / 2 + 1).unwrap());
            for retained in batch.samples.iter().flatten() {
                assert!(retained.last_ms >= i64::from(index / 8) * 40_000);
                assert_eq!(retained.last_ms % 10_000, 0);
            }
        }
        assert!(batch.count < MAX_BATCH_SIZE);
    }
}

#[kani::proof]
#[kani::unwind(18)]
fn completed_batches_are_disjoint_and_position_slots_are_inert() {
    two_disjoint_batches(true);
    kani::cover!(true, "second complete batch");
}

#[kani::proof]
#[kani::unwind(18)]
fn failed_batches_are_disjoint_and_position_slots_are_inert() {
    two_disjoint_batches(false);
    kani::cover!(true, "failed fits consumed");
}

#[kani::proof]
#[kani::unwind(16)]
fn extended_batch_reaches_capacity_and_clears_storage() {
    let mut batch = SpeedBatch::new();
    for index in 0..13 {
        let time = i64::from(index) * 5000;
        let position = if index % 2 == 0 {
            f64::from(index) * 35.5
        } else {
            f64::from_bits(kani::any())
        };
        // An unresolved but extendable fit; finalization must stop even if it stays unresolved.
        let fit = SpeedEstimate {
            speed_mps: 7.1,
            sigma_mps: 3.0,
            samples: usize::try_from(index / 2 + 1).unwrap(),
            span_s: 30.0,
        };
        let outcome = allocate_and_finish(&mut batch, sample(time, position), Some(fit));
        assert_eq!(matches!(outcome, NetworkUse::Position), index % 2 == 1);
        if index % 2 == 0 && index < 12 {
            assert!(matches!(outcome, NetworkUse::Reserved));
            assert_eq!(batch.count, usize::try_from(index / 2 + 1).unwrap());
        }
        if index == 12 {
            assert!(matches!(outcome, NetworkUse::Reserved));
            assert_eq!(batch.count, 0);
            assert!(batch.samples.iter().all(Option::is_none));
            kani::cover!(true, "capacity reached");
        }
        assert!(batch.count < MAX_BATCH_SIZE);
    }
}

#[kani::proof]
#[kani::unwind(10)]
fn failed_batch_never_returns_reserved_fixes_to_position() {
    let mut batch = partial_batch();
    batch.accepted(
        10.0,
        SpeedEstimate {
            speed_mps: 12.0,
            sigma_mps: 2.0,
            samples: 4,
            span_s: 30.0,
        },
        12.0,
    );
    assert!(batch.observe_departure(20.0, 5.0, 12.0).is_none());
    assert!(batch.observe_departure(25.0, 5.0, 12.0).is_none());
    assert!(matches!(
        allocate_and_finish(&mut batch, sample(25_000, 0.0), None),
        NetworkUse::Position
    ));
    assert!(matches!(
        allocate_and_finish(&mut batch, sample(30_000, 750.0), None),
        NetworkUse::Reserved
    ));
    assert!(batch.relearning());
    assert_eq!(batch.count, 0);
    assert!(batch.samples.iter().all(Option::is_none));
    assert_eq!(batch.recovery_until_ms, 45_000);
    let time = i64::from(kani::any::<u16>());
    kani::assume((30_001..=45_000).contains(&time));
    let outcome = allocate_and_finish(&mut batch, sample(time, 1000.0), None);
    assert_eq!(matches!(outcome, NetworkUse::Position), time < 45_000);
    assert_eq!(batch.count, if time < 45_000 { 0 } else { 1 });
    kani::cover!(time == 44_999, "cooldown");
    kani::cover!(time == 45_000, "fresh batch");
}

/// Recovery decisions consume a slope/error allowance; computing them with libc hypot is
/// outside this proof. This exercises the same decision and cleanup helpers as select.
#[kani::proof]
#[kani::unwind(10)]
fn recovery_restores_original_prior_and_starts_without_old_samples() {
    let mut batch = partial_batch();
    let estimate = SpeedEstimate {
        speed_mps: 20.0,
        sigma_mps: 2.0,
        samples: 4,
        span_s: 30.0,
    };
    batch.accepted(15.0, estimate, 20.0);
    batch.accepted(18.0, estimate, 20.0);
    assert_eq!(batch.prior_speed_mps, Some(15.0));
    assert!(batch.observe_departure(12.0, 5.0, 20.0).is_none());
    let prior = batch.observe_departure(12.0, 5.0, 20.0).unwrap();
    assert_eq!(prior, 15.0);
    assert!(matches!(
        batch.restore_prior(prior, 20_000),
        NetworkUse::RestorePrior(15.0)
    ));
    let mut expected = SpeedBatch::new();
    expected.recovery_until_ms = 50_000;
    assert!(batch == expected);
    let now = i64::from(kani::any::<u16>());
    kani::assume((20_001..=50_000).contains(&now));
    let outcome = allocate_and_finish(&mut batch, sample(now, 600.0), None);
    if now < 50_000 {
        assert!(matches!(outcome, NetworkUse::Position));
        assert!(batch == expected);
    } else {
        assert!(matches!(outcome, NetworkUse::Reserved));
        assert_eq!(batch.count, 1);
        assert_eq!(batch.samples[0].unwrap().last_ms, now);
        assert!(batch.samples[1..].iter().all(Option::is_none));
    }
    batch.clear();
    assert!(batch == SpeedBatch::new());
    kani::cover!(now == 49_999, "cooldown");
    kani::cover!(now == 50_000, "recovery boundary");
}

#[kani::proof]
#[kani::unwind(3)]
fn departure_confirmation_requires_consistent_evidence() {
    let mut batch = SpeedBatch::new();
    let standing: bool = kani::any();
    let second: u8 = kani::any();
    kani::assume(second < 3);
    let prior = if standing { 0.0 } else { 15.0 };
    batch.accepted(
        prior,
        SpeedEstimate {
            speed_mps: 20.0,
            sigma_mps: 2.0,
            samples: 4,
            span_s: 30.0,
        },
        20.0,
    );
    assert!(batch.observe_departure(12.0, 5.0, 20.0).is_none());
    let slope = match second {
        0 => 12.0,
        1 => 20.0,
        _ => 28.0,
    };
    let result = batch.observe_departure(slope, 5.0, 20.0);
    assert_eq!(result.is_some(), !standing && second == 0);
    assert_eq!(batch.relearning(), second == 0);
    kani::cover!(standing && result.is_none(), "standing prior");
    kani::cover!(result.is_some(), "confirmed");
    kani::cover!(second == 2 && result.is_none(), "opposite departure");
}

/// Any admissible fitted summary must either leave room for another sample or empty storage.
#[kani::proof]
#[kani::unwind(10)]
fn fit_finalization_always_preserves_storage_capacity() {
    let mut batch = SpeedBatch::new();
    let count = usize::from(kani::any::<u8>());
    kani::assume((4..=7).contains(&count));
    batch.count = count;
    for index in 0..count {
        batch.samples[index] = Some(sample(i64::try_from(index).unwrap() * 10_000, 0.0));
    }
    batch.relearning = kani::any();
    let speed = f64::from_bits(kani::any());
    let sigma = f64::from_bits(kani::any());
    let span = f64::from_bits(kani::any());
    kani::assume((0.0..=MAX_SPEED_MPS).contains(&speed));
    kani::assume((2.0..=4.0).contains(&sigma));
    kani::assume((30.0..=60.0).contains(&span));
    let estimate = kani::any::<bool>().then_some(SpeedEstimate {
        speed_mps: speed,
        sigma_mps: sigma,
        samples: count,
        span_s: span,
    });
    let outcome = batch.finish_batch(estimate, 60_000);
    assert!(!matches!(
        outcome,
        NetworkUse::Position | NetworkUse::RestorePrior(_)
    ));
    assert!(batch.count < MAX_BATCH_SIZE);
    if batch.count != 0 {
        assert_eq!(batch.count, count);
        assert!(matches!(outcome, NetworkUse::Reserved));
        assert!(count < 7 && span < 60.0);
    } else {
        assert!(batch.samples.iter().all(Option::is_none));
    }
    if let NetworkUse::Speed(fit) = outcome {
        assert!(fit.speed_mps - 3.0 * fit.sigma_mps > 1.0);
    }
    kani::cover!(batch.count != 0, "extended");
    kani::cover!(count == 7 && batch.count == 0, "capacity limit");
    kani::cover!(span == 60.0 && batch.count == 0, "span limit");
    kani::cover!(matches!(outcome, NetworkUse::Speed(_)), "usable speed");
}
