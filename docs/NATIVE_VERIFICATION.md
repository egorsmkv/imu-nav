# Native core bounded verification

[Kani](https://model-checking.github.io/kani/) verifies selected contracts in `imu-nav-core` by
exploring symbolic inputs within the harness bounds. This complements Rust tests, JNI parity tests,
trip replay and [production coverage](RUST_COVERAGE.md). It does not prove the whole navigator correct.
IMU Nav remains a research prototype, not a safety system.

## Run locally

On Linux x86-64, install the pinned verifier and its bundled compiler/solver:

```bash
cargo install --locked kani-verifier --version 0.68.0
cargo kani setup
python3 -m unittest discover -s tools/tests -v
python3 tools/verify_native.py
```

Kani 0.68.0 uses `nightly-2026-08-21` (`rustc 1.100.0-nightly`, commit `8925ea358`),
which satisfies the workspace's Rust 1.99 minimum. This is separate from the pinned Rust coverage
toolchain. Do not substitute another compiler, CBMC or solver. Kani is a developer tool, not a Cargo
or Android runtime dependency. The core remains dependency-free.

The runner checks the tool version, selects only `imu-nav-core`, uses two solver workers, allows
five minutes per harness, and imposes a 25-minute overall verification deadline. It retains Kani's
default overflow, pointer, assertion reachability and unwinding checks. A proof's `#[kani::unwind]`
is an explicit loop bound; exceeding it fails verification rather than assuming the loop terminates.
No production function is stubbed.

Results go to `build/native-verification/`:

- `verification.log` and `version.log`: commands, verifier output and diagnostics.
- `results.json`: Kani's structured checks, locations, tool versions and per-harness results.
- `summary.json`: the runner's final pass/fail result, including report-validation failures.
- `target/`: isolated compilation and solver artifacts, reused by the next invocation.

The runner maintains an explicit harness and reachability-witness inventory. A missing/duplicated
harness, malformed report, failed assertion, reachable unsupported operation, timeout, insufficient
unwind bound, or missing/unsatisfiable required cover property fails the run. Kani can report
unsupported constructs from unreachable library paths; those warnings alone do not invalidate a
proof, but reaching such a construct does. Successful assertions must exist in every harness.

## Verified contracts and bounds

Proof modules are separate `kani_proofs.rs` files behind `#[cfg(kani)]`. They are absent from normal
builds and excluded from production coverage. The runner inventory in `tools/verify_native.py` is
the authoritative list of required harnesses and named `kani::cover!` witnesses.

| Area | Contract | Domain / limits |
|---|---|---|
| Finite numerical state | Successful construction, anchor/prior installation and numerical commits retain finite fields; errors preserve the old estimate | Constructor inputs and all six commit candidate fields use arbitrary `f64` bit patterns; anchor/prior value and sigma also unrestricted, against the original valid filter fixture |
| Finite predictions and measurements | Successful updates retain finite state; errors and innovation rejection preserve it | Prediction: arbitrary finite position/speed/nonnegative drift, covariance `(100, 2, 4)`, dt 1 or 5 s, acceleration sigma/drift rate 1. Measurements: arbitrary measurement/sigma bit patterns, state `(position=10, speed=2, drift=5)`, covariance `(100, 2, 4)`, gate 9; coarse caps 5 m / 1 m/s. Accepted, gated and error witnesses required |
| Radius and variance arithmetic | Successful diagonal covariance/radius results are finite | Arbitrary sigma for one diagonal (other sigma 1); arbitrary radius multiplier with position sigma 2 and drift 5 |
| Tick/state transaction boundaries | Failed updates publish nothing; successful updates publish their pending values; fixed-state updates cannot touch history/watermarks | One tick transaction / two fixed-state transactions with symbolic success/error choices and positions 0–255; pending work changes filter, calibration, input ages, model hints, turn/network state and (for ticks) history/watermarks/mode. These exercise the actual generic transaction helpers, not the complete numerical replay body |
| Checkpoint ownership | The pending tick initially contains the same complete logical state and immutable route identity | One retained checkpoint at 0 ms, current state at 500 ms with calibration 1.01; structural comparison excludes allocator spare capacity |
| Filter validation | Constructor rejects invalid fields; non-finite measurements, negative/non-finite uncertainty, invalid gates, correction limits, prediction time/noise/drift reject without mutating state | Invalid scalar inputs use **all `f64` bit patterns**, including NaN, infinities and signed zero; operation selectors enumerate the applicable entry points |
| Anchor and drift reset | Anchor retains speed and speed variance; reset changes only drift; successful anchor floors position variance | Symbolic finite position ±1,000,000 m, speed 0–60 m/s, drift 0–10,000 m; new sigma 0–1,000 |
| Speed prior recovery | Restoring a prior cannot reduce previous speed variance or change position/drift | Same state domain with old speed variance 0.0625–1,000,000; new speed 0–60 m/s and sigma 0–1,000 |
| Coarse corrections | Position correction preserves speed/drift; speed correction preserves position/drift; accepted updates retain uncertainty floors; gate rejection preserves every state field | Correlated fixture: position/speed 0, drift 50, covariance `(100, 2, 4)`; every integer measurement −1,000…1,000; position sigma 10 / cap 5, speed sigma 2 / cap 1, gate 9; accepted and rejected witnesses required |
| Trust ingress | Invalid coordinates, mock flag and outside-service-area flag produce their hard reasons and BAD, never a trusted anchor | Arbitrary latitude/longitude bit patterns and both flags; **first fix**, default config, no network/history/receiver evidence/heading; optional fix fields absent, times zero |
| Trusted-anchor promotion | Only GOOD replaces the anchor; classifier reset clears history | Production commit helper called by `evaluate`; old anchor absent or fixed valid fix, new timestamps arbitrary `i64`; all three verdict levels |
| Jamming | Accurate change flag, ignored missing/non-finite AGC, entry threshold, reset, recovery hold boundary and interruption | Four arbitrary AGC/time observations from reset; separate recovery proof with start 0–1,000,000 ms and elapsed 0–30,000 ms; interruption at 1–14,999 ms |
| Network gating | Invalid observations cannot alter the anchor/candidates/history; coarse observations cannot establish an initial anchor | Arbitrary scalar bit patterns/timestamps; empty anchor or one fixed valid anchor, at most one sample/candidate |
| Network reset/clear | Both remove recent/history/speed samples; clear retains anchor/candidates/coordinates; reset removes them | One populated sample/candidate fixture; proof-only inspection checks the underlying speed sample collection |

Unless a row specifies its own fixture, filter state uses covariance `(100, 2, 4)` (position variance,
cross-covariance, speed variance). Filter state-preservation assertions compare floating-point **bits**, so
changing the sign of zero is detectable. Input assumptions restrict the domain, never the result of
an operation. Covers establish that claimed accepted/rejected cases actually remain reachable.

These proofs do **not** establish unrestricted floating-point numerical stability, PSD preservation
for arbitrary covariance matrices, geodesic accuracy, long-history trust decisions, route matching,
JNI/Android lifecycle safety, concurrency, or navigation accuracy. Finiteness guards are proved separately with unrestricted candidate bit patterns; public-operation
proofs retain the numerical fixtures listed above. This does not establish accuracy or acceptance
for every finite input. Replay
accuracy gates remain necessary.

## Numerical guards and rollback

Finite uncertainty can still overflow when squared. Covariance creation and anchor/prior setters
now validate that square before assignment. Prediction, scalar measurements and coarse corrections
validate the entire candidate estimate before publishing it; a covariance determinant calculation
with non-finite products is conservatively rejected. Radius calculation rejects non-finite output.
Ordinary-size arithmetic keeps the same formulas and rounding order. A rejected measurement's
innovation diagnostics may still be infinite; the finite-state contract concerns retained state.

OBD numerical work now receives an exclusively borrowed pending `FilterState`; prediction and
speed-update helpers live in `estimator/state.rs`. Errors cannot publish this state, and the
numerical callback has no access to estimator history/watermarks. Those are appended only after
success, avoiding a full history copy for each OBD sample. Tick updates use the equivalent
copy-and-commit transaction for the entire estimator, including history.

Verification is **compositional**: numerical finiteness, pending-state isolation, commit-on-success,
and checkpoint ownership are checked separately. Public ingress sequences are regression-tested.
Full inlined floating-point replay proofs
exceeded practical memory limits; these are not claimed as verified end-to-end sequences. Complete
multi-step prediction failures and valid-coordinate delayed-GPS failure/retry are Rust regression
tests using real routes and the actual public APIs. No application operation is stubbed.

Estimator comparisons exist only in test/Kani builds and cover every nested field plus immutable
route identity. Proof-only equality for the eight-entry coordinate cache and seven-entry speed
batch compares each array element explicitly, avoiding an eight-iteration bound for unrelated
algorithm loops merely to inspect a snapshot. Exhaustive destructuring forces updates when fields
are added. Transaction/copy harnesses use unwind 3, and all
unwinding assertions remain enabled. The straight route is a precomputed two-point fixture;
construction/geodesic accuracy and concurrency are outside scope. `Arc` shares immutable geometry.

Regression tests reproduce finite sigma overflow, position/drift/radius overflow, OBD failure after
an earlier successful prediction step, and delayed-GPS failure after position fusion. The latter
also checks a valid retry against an untouched estimator with learned calibration and history.

## CI and adding proofs

`.github/workflows/native-verification.yml` runs on every pull request, main push and manual dispatch,
without an Android SDK or JNI build. The failing check is **Native core bounded proofs**. Repository
branch protection/rulesets must list that check as required to enforce it at merge time; a workflow
file alone cannot configure repository rules. Reports/logs are uploaded even on failure, retained
for 14 days; the job deadline is 30 minutes.

Add a harness beside its production module, declare the assumptions and explicit unwind bound,
include named reachability witnesses, and add it to `REQUIRED`. Do not assume an accepted/rejected
outcome just to make a proof pass. Keep any proof-only inspection code in excluded proof sources.
Run the full runner twice to check both a fresh compilation and reuse of its target directory.

For a failure, find the harness/check in `results.json`, then reproduce it directly from `native/`:

```bash
cargo kani -p imu-nav-core --harness 'kani_proofs::coarse_position_is_conservative' --exact \
  --target-dir ../build/native-verification/target --output-format regular
# Where supported, print a concrete counterexample without editing source automatically:
cargo kani -p imu-nav-core --harness 'kani_proofs::coarse_position_is_conservative' --exact \
  -Z concrete-playback --concrete-playback print --output-format regular
```

Turn genuine counterexamples into ordinary Rust regression tests and fix the production defect.
For timeouts or unwind failures, investigate solver complexity or increase a justified bound;
do not disable overflow/unwinding checks, remove witnesses, or stub the failing production operation.
The reproduction command is diagnostic; only the complete runner is the CI gate.
