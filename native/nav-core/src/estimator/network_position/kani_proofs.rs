//! Complete state inspection without slice-equality loops inflating unrelated algorithm bounds.
//! This compares the same fields as derived PartialEq; no production operation is replaced.
use super::NetworkEvidence;

impl PartialEq for NetworkEvidence {
    fn eq(&self, other: &Self) -> bool {
        // Exhaustive destructuring forces a proof update whenever a state field is added.
        let Self {
            valid_after_ms,
            last_input_ms,
            last_evaluated_ms,
            seen,
            next_seen,
            candidate,
            speed,
        } = self;
        (
            valid_after_ms,
            last_input_ms,
            last_evaluated_ms,
            (
                &seen[0], &seen[1], &seen[2], &seen[3], &seen[4], &seen[5], &seen[6], &seen[7],
            ),
            next_seen,
            candidate,
            speed,
        ) == (
            &other.valid_after_ms,
            &other.last_input_ms,
            &other.last_evaluated_ms,
            (
                &other.seen[0],
                &other.seen[1],
                &other.seen[2],
                &other.seen[3],
                &other.seen[4],
                &other.seen[5],
                &other.seen[6],
                &other.seen[7],
            ),
            &other.next_seen,
            &other.candidate,
            &other.speed,
        )
    }
}
