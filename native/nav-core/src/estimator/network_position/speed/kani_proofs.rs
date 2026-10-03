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
