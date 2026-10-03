//! Proof-only state inspection; no replacement of production operations.

use super::NetworkSpeedEstimator;

/// Check the retained samples directly: fewer than four samples cannot produce an estimate.
pub(crate) fn assert_empty(estimator: &NetworkSpeedEstimator) {
    assert!(estimator.samples.is_empty());
}
