# Bounded verification of core rules

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

Kani explores many inputs inside stated bounds to check rules such as rejecting malformed evidence and leaving state unchanged after a failed update. These proofs complement tests and replay; they do not establish that every real drive is safe.

## Bounded formal verification

The native core has a pinned Kani proof suite for input rejection, state preservation, conservative
corrections, trust anchors, jamming hysteresis and network resets. Run `python3 tools/verify_native.py`
from the repository root. See [setup, exact bounds and the manual workflow](../native_verification.md);
these bounded contracts complement replay/testing and do not certify the whole navigator.

The workflow is manually dispatched. Descriptions on this page explain harness scope, not the
result of a run at the current revision. Ordinary Rust tests also compile and check the route
geometry fixture; they do not execute the Kani harnesses.

Finite inputs that overflow uncertainty or retained state calculations now return `FilterError`
before publishing the update. Failed OBD predictions also restore earlier prediction steps,
calibration and timestamps. The Kani suite checks numerical guards and bounded estimator rollback
sequences; real-geometry delayed-GPS rollback and retry are covered by Rust regression tests.

Timestamp proofs cover prediction-step progress, GPS/OBD ingress ordering and hint expiry. OBD
calibration proofs cover accepted GOOD GPS eligibility, stability/age limits and the 0.8–1.2 scale
interval. These decision helpers are shared with production; exact domains are documented in
[the verification scope](../native_verification.md).

Motion/walking proofs additionally check measured-speed priority, travel-mode isolation, repeated
prior bounds, and stop recovery without a position anchor. They exercise production setters with
symbolic inputs and retain the explicit fixture/sequence bounds in the verification scope.

Network evidence proofs check duplicate/rate protection, reroute cleanup, sensor priority, disjoint
position/speed allocation, bounded batch storage and recovery without reusing old samples. The
production bookkeeping runs on explicit bounded sequences and fit-result inputs; numerical fitting
remains regression-tested. See the verification scope for input domains.

Turn proofs run the production correction path with bounded landmark tables, checking input/quality
gates, cooldown, duplicate/landmark reuse, ambiguity and correction limits without claiming a fresh
GPS/OBD measurement. Geographic landmark construction remains regression-tested.

Trust policy proofs cover timestamp ordering, frozen-position escalation, receiver freshness and
quality thresholds, and bounded strong-jam confirmation chains. Only GOOD verdicts promote the
trusted anchor. Extreme timestamp differences now saturate for duration comparisons or reject
freshness/chain eligibility instead of overflowing. Geographic network agreement and complete
classifier integration remain regression-tested; the proof scope documents the helper boundaries.

Speed fusion ignores malformed network metadata and negative/non-finite speeds, and rejects
non-finite weighted results before publishing a capped speed. Proofs check those guards, source
combinations and sample storage through the 60-entry capacity. Network proofs check coarse-fix
anchor preservation, bounded reanchoring sequences, candidate-chain restart and duplicate evidence.
The verification scope lists the fixed fixtures and numerical/geographic exclusions.

Network-speed windows exclude future observations and include the exact expiry boundary. Queries
retain stored samples for later use. Outlier removal must leave at least four observations over the
required span; insufficient evidence and non-finite fits now reject instead of publishing the
unfiltered result. Window selection and final publication have separate Kani harnesses; numerical
regression fitting remains covered by Rust tests.
