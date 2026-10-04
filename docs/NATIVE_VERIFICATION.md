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
five minutes per harness, and imposes a 35-minute overall verification deadline. It retains Kani's
default overflow, pointer, assertion reachability and unwinding checks. A proof's `#[kani::unwind]`
is an explicit loop bound; exceeding it fails verification rather than assuming the loop terminates.
No production function is stubbed. The larger speed-storage inventory increases the overall
budget; the five-minute per-harness deadline and two-worker memory limit are unchanged.

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
| Prediction scheduling | Each step advances, stays within the target and 5 s cap, and stops at active hint/OBD expiry; expired OBD is not fresh | Arbitrary signed timestamps; target strictly later, OBD absent or nonnegative and no later than now, active hint deadlines strictly later than now. Includes `i64::MAX`; this proves step selection, not termination over an arbitrary duration |
| GPS/OBD timestamp gates | Future, duplicate, too-old and pre-checkpoint GPS cannot rewind; stale/duplicate OBD cannot pass ingress | All `i64` bit patterns; expected GPS age computed in `i128` to independently check saturating arithmetic. Same-time GPS is allowed only at an initial checkpoint; same-time OBD requires a newer input watermark |
| Hint expiry | Reapplying a hint never moves its deadline; an expired walking hint stops speed, an expired car stop restores cruise; no sensor freshness is invented | Nonnegative arbitrary current time and arbitrary deadline; initial speed 10 m/s, no GPS/OBD, walking speed 2 m/s or car stop factor 0. Full production setters/expiry handler; fixed position/covariance/drift remain unchanged |
| Car measured-speed priority | Fresh GPS or OBD makes every motion hint inert; a stop hint becomes eligible exactly at 2,500 ms age | Nonnegative measurement time ≤ arbitrary current time, age < 2,500 ms, no active motion control, speed 10 m/s; optional hint fields use all scalar bit patterns. Boundary proof uses current time 5,000 ms, ages 0–3,000 ms and a valid stop hint, separately covering GPS and OBD |
| Travel-mode isolation | Car motion hints cannot alter a walking estimator; walking hints cannot alter a car estimator | All hint scalar bit patterns/deadlines and optional absence; walking fixture has speed 1.5 m/s and an active step deadline, car fixture speed 10 m/s; full logical-state snapshots compared |
| Repeated car model hints | A repeated stop/ramp uses saved cruise speed rather than compounding slowdown; position, position variance and drift remain unchanged | Two identical hints at 5,000 ms, deadline 6,000 ms, no fresh sensors; initial speed 0 or 10 m/s with every floating-point factor 0–1; capped initial speed 63.75 m/s with factors 0–1 in 1/256 increments, fallback cruise 16 m/s, initial cross-covariance 2 |
| Motion veto and measurement recovery | Missing/invalid/network-moving hints release a modeled stop; accepted trusted speed clears control, while rejected recovery preserves state | Veto: stop from 10 m/s, then missing hint, NaN factor or network-moving flag. Recovery: false stop with saved cruise 35 m/s, measurement 35 m/s with sigma 0.6, recovery permission symbolic; permission is supplied by production GPS/OBD callers, whose classification is outside this harness |
| Walking GPS priority | Fresh GPS preserves measured speed and its original deadline despite repeated hints; step speed takes over at exact expiry | Speed 1.5 m/s, nonnegative GPS time ≤ current time < saturating GPS expiry; all optional hint bit patterns, two applications. Boundary proof uses age 0–3,000 ms at time 5,000 ms and a 2 m/s step hint |
| Repeated walking priors | Speed stays finite within 0–4 m/s; invalid/missing hints hold at zero; repetition never anchors position or refreshes a sensor timestamp | Two identical hints at 5,000 ms with all speed/deadline bit patterns or absence; no GPS/OBD, initial cross-covariance 2, fixed position variance 400 and drift 5 |
| OBD drift expiry | Prediction changes from OBD to estimated drift at the exact expiry boundary | Actual two-step prediction from 2,000 to 3,000 ms, OBD at 0, speed 10 m/s: total added drift 0.5 m |
| Calibration eligibility | Only accepted position AND speed from GOOD GPS, in car mode, with precise, fresh measurements and stable OBD may learn; rejection changes no state | Optional OBD/GPS inputs, all trust/mode/acceptance combinations, optional uncertainty with all `f64` bit patterns, arbitrary ordered nonnegative plateau times and arbitrary GPS time; OBD/GPS speeds fixed at 15/16 m/s, offset integer 0–255 m |
| Calibration arithmetic | Learning remains finite and inside 0.8–1.2, moving toward the measured ratio; low speeds/out-of-range ratios cannot learn | Blend: arbitrary `f64` values in 0.8–1.2 for prior and ratio. Production ratio gates: OBD/GPS speeds 0–63.75 m/s in quarter-m/s increments, prior scale 1, precise GOOD GPS and 3 s stable plateau; both ratio boundaries reachable |
| OBD plateau | A gap over 1 s or speed range over 0.5 m/s restarts stability; exact limits remain eligible | One production transition from a flat 15 m/s plateau; arbitrary ordered nonnegative timestamps, next speed 0–63.75 m/s in quarter-m/s increments |
| Finite numerical state | Successful construction, anchor/prior installation and numerical commits retain finite fields; errors preserve the old estimate | Constructor inputs and all six commit candidate fields use arbitrary `f64` bit patterns; anchor/prior value and sigma also unrestricted, against the original valid filter fixture |
| Finite predictions and measurements | Successful updates retain finite state; errors and innovation rejection preserve it | Prediction: arbitrary finite position/speed/nonnegative drift, covariance `(100, 2, 4)`, dt 1 or 5 s, acceleration sigma/drift rate 1. Measurements: arbitrary measurement/sigma bit patterns, state `(position=10, speed=2, drift=5)`, covariance `(100, 2, 4)`, gate 9; coarse caps 5 m / 1 m/s. Accepted, gated and error witnesses required |
| Radius and variance arithmetic | Successful diagonal covariance/radius results are finite | Arbitrary sigma for one diagonal (other sigma 1); arbitrary radius multiplier with position sigma 2 and drift 5 |
| Tick/state transaction boundaries | Failed updates publish nothing; successful updates publish their pending position/timestamps; fixed-state updates cannot touch history/watermarks | Separate success/error tick transactions and two fixed-state transactions with symbolic success/error choices and positions 0–255; pending work changes filter, calibration, input ages, model hints, turn/network state and (for ticks) history/watermarks/mode. Initial state at 0 ms with one checkpoint. These exercise the actual generic transaction helpers, not the complete numerical replay body |
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

## Trust timestamps, frozen positions and receiver policy

Five timestamp/sequence harnesses and seven receiver/jamming harnesses call the actual production
helpers. Unless stated otherwise, configuration is default and valid coordinates are (50°, 30°).
They use unwind bounds 2–8, with safety and unwinding assertions enabled.

- Clock skew covers every signed fix/current wall timestamp and configured skew limit, comparing
  against an independent `i128` difference. Negative configured limits act as zero.
- Sequence ordering covers every signed previous/current elapsed and wall timestamp and optional
  frozen-start timestamp. Either non-increasing clock adds `DuplicateTime`. With unchanged position
  and speed 10 m/s, frozen duration adds SUSPECT at 5,000 ms and BAD at 15,000 ms. Independent `i128`
  arithmetic checks extreme forward and backward differences. A two-step sequence covers start
  0…`i64::MAX - 20000` and duration 2–20,000 ms, including each exact escalation boundary and anchor
  retention. Another sequence at 19/20/21 s checks that movement to latitude 50.01°, every floating
  speed 0–3 m/s, or missing speed clears the old marker before frozen detection restarts.
- Verdict composition covers arbitrary candidate timestamps and optional hard `DuplicateTime` and
  soft `JamStrong` reasons. Hard reasons dominate, all supplied reasons survive, and only GOOD
  promotes the trusted anchor; other classifier bookkeeping is unchanged.
- Receiver freshness covers all signed fix/receiver timestamps: receiver time must be positive,
  and age must be 0–4,999 ms. Future, stale and overflowing differences cannot qualify. Receiver
  reasons use fix time 10,000 ms, arbitrary receiver time/satellite counts and optional signal
  fields with all floating-point bit patterns. They check no/few satellites, weak mean CN0 and
  flat spread thresholds, and absence of these reasons for stale data. Input validity remains
  the responsibility of `check_fix`; this helper proof characterizes comparisons, including NaN.
- Healthy-constellation eligibility covers all satellite/dual-frequency counts and optional signal
  bit patterns: at least 6 used satellites with 2 dual-frequency satellites, otherwise 8; mean CN0
  at least 25 and spread at least 3. Missing signal fields cannot qualify. Independent network
  eligibility separately checks all signed timestamps and optional accuracy bit patterns against
  the existing absolute-age ≤10 s and accuracy ≤150 m predicates. This is an age/upper-accuracy
  gate, not validation of network coordinates or nonnegative accuracy.
- Hard-jam policy uses fresh receivers at arbitrary positive times, AGC −20, healthy (8 satellites)
  or unhealthy (7 satellites) constellations, arbitrary optional previous confirmation times and
  a symbolic independent-confirmation result. A healthy receiver needs independent confirmation
  or a strictly positive chain age ≤3 s. The exception grants SUSPECT, never GOOD, and renews its
  marker; rejected hard-jam confirmation grants BAD and clears it. Three calls seeded at 1 s,
  interrupted by an unhealthy receiver at 2 s or expiry at 4.001 s, then healthy at the next
  millisecond prove the rejected chain cannot revive itself.
- AGC/receiver boundaries use fix time 10 s, arbitrary receiver time and satellite counts, AGC
  absent or −20/−16/−11/−10, and both jamming/independent-confirmation flags. They check strict AGC
  comparisons, stale/future receiver exclusion and that every emitted jam reason prevents GOOD.

`finish_verdict`, `check_jamming_policy` and `network_confirmation_eligible` are shared production
helpers, not stubs. Geographic network confirmation remains computed lazily by `check_jamming`
when fresh hard-jammed healthy receivers need it. Its boolean result is the policy proof boundary;
these proofs do not establish geodesic distance, last-good reachability, or unrestricted complete
`evaluate` histories. Existing real-coordinate classifier tests exercise their integration.

Four new public-classifier regression tests reproduce signed timestamp overflow in trusted-anchor
age, frozen duration, receiver freshness and strong-jam chain age. Duration comparisons now use
saturating subtraction; freshness/chain gates reject subtraction overflow. Ordinary timestamps
retain their previous behavior. The full timestamp domains above prove the sequence/freshness/
chain decisions; the geodesic trusted-anchor path is regression-tested.

## Speed fusion and sample storage

Fusion guards reject negative/non-finite source speeds and malformed network uncertainty/span
before applying the network uncertainty floor. Non-finite weighted arithmetic or final speed/sigma
returns no estimate; rejected network metadata cannot leak into a GPS-only result. Normal valid
fusion retains the same weights, arithmetic order and final speed cap.

The finalization proof covers all floating-point bit patterns for weighted sum, weight sum and
span, with source count 2. Successful output has finite speed in 0–150/3.6 m/s, finite positive
sigma and finite nonnegative span. This is a numerical publication contract, not a proof of
statistical calibration. Separate public-fusion proofs cover arbitrary invalid network fields and
source speeds, GPS-only fallback, and every subset of fixed GPS/route/network speeds 10/20/12 m/s
with arbitrary signed GPS age, split by source combination. The three-source case is further split into adjacent GPS-age
ranges ≤0, 1–60,000 ms, 60,001–3,600,000 ms, and >3,600,000 ms; their union retains every signed
age. The unsplit three-source proof exceeded five minutes. They check source
counts, span ownership and the weighted-average
range with a 0.001 m/s rounding tolerance. Fusion harnesses use unwind 2.

Storage proofs call production `add`/`clear`: invalid numeric fields or non-increasing signed
timestamps preserve a one-sample fixture bit-for-bit (unwind 4). Twelve harnesses enumerate fixed
occupancies 0–59 (five independent fixtures each), checking one insertion's length, newest sample
and every retained payload/timestamp. Fixtures have times 0…count−1 ms, position 10 m, accuracy
20 m and capacity 61; insertion uses time 1,000 ms, position 30 m and accuracy 40 m. A separate
exact-capacity fixture checks eviction with arbitrary finite position and nonnegative finite
accuracy, then clear. Storage harnesses use unwind 62. Symbolic occupancy and multi-insertion
formulations exceeded five minutes or 27 GB of memory; independent fixed-size fixtures avoid
that growth without omitting occupancies. A 125-insertion Rust regression exercises allocation
growth and repeated eviction. These proofs do not establish regression-fitting accuracy or
arbitrary-history behavior.

## Network-speed window and surviving evidence

Window selection requires `0 <= now - sample_time <= window`, inclusive at expiry. Checked
subtraction rejects unrepresentable ages. A query never removes stored observations, so future
samples remain available for a later query. Outlier trimming must leave at least four observations;
otherwise the window rejects instead of reusing the unfiltered fit. Finalization recomputes span
from surviving endpoints, requires the configured minimum span, and publishes only finite fit
fields and positive uncertainty no greater than 4 m/s after inflation. Degenerate fits reject
before outlier selection. The 30/40/90 s windows, 15/20/30 s minimum spans, weighting and fallback
order are unchanged.

Six window/publication harnesses are registered for CI:

- Membership compares every signed sample/current/window timestamp against independent `i128`
  arithmetic; witnesses include each standard expiry boundary, future/current samples, an expired
  sample, subtraction overflow and a negative window (unwind 2).
- Selection checks three arbitrary timestamps and floating-point payload bit patterns, arbitrary
  signed current time/window, original order and bitwise preservation of stored/copied values
  (unwind 8). Invalid numeric payloads are deliberately included to isolate selection from ingress.
- Complete `estimate`/`strict_estimate` calls reject four future observations at 1/11/21/31 s for
  every current time <1 s and preserve storage (unwind 1). Ingress keeps timestamps increasing,
  so production rejects before allocating or iterating when the query precedes the first sample.
  The fixture initializes four samples directly and checks each stored value explicitly; ingress
  remains covered by the storage harnesses. Unwinding assertions enforce that no longer loop is
  reached. The original combined iterator/fit expansion exceeded the five-minute limit; neither
  the signed timestamp domain nor the public calls, safety checks or witnesses were removed.
- Inlier selection and finalization cover all 32 masks of five observations at 0/1/2/3/20 s,
  accuracy 20 m, position 0 or 1,000 m, and a supplied stationary fit with sigma 1 m/s. They check
  surviving count, shortened span and successful publication when sufficient evidence remains
  (unwind 8). The numerical fitting operation is outside this harness.
- Publication checks arbitrary fit-field/minimum-span floating-point bits against four fixed
  timestamps 0/5/10/15 s. Only finite nonnegative speed, positive finite sigma <=4 m/s and a
  sufficient span can be returned (unwind 2).
- Endpoint arithmetic covers every signed first/last timestamp, minimum spans 15/20/30 s and a
  supplied finite fit, using a four-element endpoint fixture. An independent `i128` duration checks
  exact acceptance, reversed endpoints and overflow (unwind 2). Timestamp uniqueness is an ingress
  responsibility and is not claimed by this endpoint-only fixture.

No fitter is stubbed. Numerical regression accuracy remains tested with ordinary Rust tests;
the new helper proofs establish selection/publication contracts at actual production boundaries.
Regression tests reproduce future evidence leakage, insufficient-inlier fallback and non-finite
results from extreme finite uncertainty, and check expiry, post-trim span and precision thresholds.
Timestamp-shift regressions also retain usable estimates near both signed clock limits after earlier
queries reject.

## Network reanchoring and duplicate evidence

These five harnesses call production `gate` and `record`, without stubs or changes to their
decision rules. The slope variance square uses explicit multiplication: Kani overapproximates
`powi` (see [intrinsic support](https://model-checking.github.io/kani/rust-feature-support/intrinsics.html)),
which produced spurious NaN/direction failures in ordinary-size fixtures. Concrete counterexample
inputs pass in Rust regression tests. The formula is unchanged and no operation is stubbed.
Coarse observations use every signed timestamp and finite position/accuracy bit
pattern with accuracy >300 m, from an established anchor and one stored candidate/sample.
Both accepted and rejected coarse observations preserve the anchor, candidates, recent/history,
coordinate cache and speed samples; they cannot reanchor.

Reanchoring starts at position 0 m/time 0, then rejects a candidate at 2,000 m/time 1 s (accuracy
30 m throughout). The next observation uses times 3/12.999/13/14 s and offsets −100/−72/0/100/10,000 m
from that candidate. All 20 combinations check the 12 s span, −6 m/s backward limit, reachability,
anchor publication and candidate cleanup, with explicit exact-boundary witnesses. A broken-chain
proof replaces that candidate with position 10,000 m/time 2 s, then checks 10,100 m at 13.999/14 s:
the discarded candidate's time cannot count toward the new span. Recovery to the original anchor
uses time 2 s and every integer position 0–100 m, checking that abandoned candidates clear.
These are bounded scenarios, not proofs of arbitrary-length regression slopes or all route positions.

Duplicate recording uses a one-sample fixture at (50°, 30°), arbitrary signed new time, finite
position and nonnegative finite accuracy. A repeated coordinate may update recent observations,
but cannot add history or speed samples or change anchor/candidates. Speed storage is compared
directly, rather than inferring preservation from an unavailable speed estimate. Harness unwind
bounds are 4 or 8; regression tests remain the longer-sequence integration checks.

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
Full inlined floating-point replay proofs exceeded practical memory limits; these are not claimed
as verified end-to-end sequences. Complete
multi-step prediction failures and valid-coordinate delayed-GPS failure/retry are Rust regression
tests using real routes and the actual public APIs. No application operation is stubbed.

Estimator comparisons exist only in test/Kani builds and cover every nested field plus immutable
route identity. Proof-only equality for the eight-entry coordinate cache and seven-entry speed
batch compares each array element explicitly, avoiding an eight-iteration bound for unrelated
algorithm loops merely to inspect a snapshot. Exhaustive destructuring forces updates when fields
are added. Transaction/copy harnesses use unwind 3, and all unwinding assertions remain enabled. The straight route is a precomputed two-point fixture;
construction/geodesic accuracy and concurrency are outside scope. `Arc` shares immutable geometry.

Regression tests reproduce finite sigma overflow, position/drift/radius overflow, OBD failure after
an earlier successful prediction step, and delayed-GPS failure after position fusion. The latter
also checks a valid retry against an untouched estimator with learned calibration and history.
Old/future GPS tests compare the complete estimator against a no-observation tick, then verify
that stale OBD still changes nothing.

## Timestamp and calibration boundaries

`estimator/timing.rs` contains the exact integer decisions used by prediction and GPS/OBD ingress.
The scheduler contract assumes expired controls have already been released, as `predict_to` does,
and that an OBD timestamp cannot be in the future relative to the current filter state. These
preconditions describe reachable state; the proof does not treat arbitrary malformed private
state as a valid estimator. At the representable timestamp limit, deadlines retain the existing
saturating-add behavior. Regression tests also run real car/walking ticks through `i64::MAX`.

`estimator/calibration.rs` receives the actual position/speed acceptance flags from `apply_gps`
and checks them together with GOOD trust before learning. Projection and Kalman fusion remain
separately tested: the eligibility proof does not assume or prove that a GPS fix deserves acceptance.
Scale arithmetic is proved independently over the full valid scale/ratio interval. Plateaus and
hint sequences have the explicit finite bounds above; these do not prove arbitrary event histories.
Kotlin modem timestamp tracking is outside this Rust proof suite and retains its own JVM tests.

## Motion and walking priority

The motion/walking harnesses call `apply_motion`, `apply_walking`, and
`update_measured_speed` directly. They do not replace those operations with models or stubs.
Accepted measured speed clears car motion control before fresh GPS/OBD timestamps are published;
the fresh-priority fixture therefore starts without active motion control. The recovery harness
checks this clearing transition on a false highway stop, with SUSPECT-style fallback denial as a
separate reachable branch. Real GPS/OBD classification and full delayed replay remain covered by
existing integration/regression tests.

Position preservation means the route position, its variance, and systematic drift remain
unchanged while installing a speed prior. Cross-covariance may intentionally be cleared, and speed
variance may change. Full-state no-op checks also inspect history and input watermarks. Repeated
hint proofs cover two applications without intervening prediction, not arbitrary driving histories.
These harnesses use unwind 3; none changes production arithmetic or disables verifier checks.
The repeated car-ramp proofs separate initial-speed cases after the larger quarter-m/s input
domain exceeded the five-minute solver budget. Normal/fallback cases retain every valid floating-point
factor; the slower capped case uses 257 factors at 1/256 intervals. The larger combined domain and
unrestricted capped-case factors are not claimed as verified. Every assertion is retained.

## Network evidence accounting

Network ingress proofs reject arbitrary observation scalar bit patterns before projection in a
populated fixture at 11,500 ms. The fixture retains a coordinate/input watermark at 9,000 ms,
a position candidate, and an unfinished speed batch. Invalid, stale, future, duplicate-time and
walking inputs preserve the whole estimator when no motion hint is present. Cached coordinates
and too-frequent fixes at 9,001–11,500 ms may consume only the input watermark; they cannot add
position confidence or speed samples. A separate reroute proof covers every timestamp from
9,000 ms through `i64::MAX`, clearing candidates/speed while retaining coordinate and input caches.

Allocation proofs call production `apply_network_estimates` with already-projected candidates;
projection, geographic accuracy and candidate-sequence eligibility are outside these contracts.
The position path requires at least three fixes over ten seconds (all byte counts, spans 0–30 s),
retains speed/drift, and caps its correction at 50 m on the fixed 100 m innovation fixture. A separate
production dispatch helper proves that successful, rejected, reserved and prior-restoration speed
allocations cannot fall through to position, reset drift, or fabricate measured freshness. It uses
a speed-10/position-0 fixture, fits of 12 or 200 m/s, 2 m/s fit uncertainty, and a restored prior of
15 m/s. Fresh GPS position/speed (ages 0–2,500 ms), OBD (0–2,499 ms), a stop hint, and disabled network speed each discard unfinished speed learning;
position remains eligible except under fresh GPS position.

Batch bookkeeping is checked through two four-sample batches over fifteen fixes, with separate
successful/failed fit cases, and a seven-sample extended batch over thirteen fixes with unresolved
fit summaries. Position-only slots have arbitrary floating-point positions and must leave all speed
state except the slot toggle unchanged. A separate finalization proof covers every count 4–7,
optional fit summaries with every floating-point speed 0–150/3.6 m/s, sigma 2–4 m/s and span 30–60 s,
and both relearning states. Finalization must leave spare capacity or empty all storage; accepted
summaries must pass the movement-confidence gate. No numerical fitting accuracy is claimed.

Recovery decisions use slopes 12/20/28 m/s, allowance 5 m/s, model 20 m/s, and original priors
0/15 m/s. Original-prior retention also checks a subsequent accepted fit. Symbolic cooldown times
cover the last excluded millisecond and exact resumption boundary after a rejected relearning fit
or restored prior. Clear removes the entire speed-learning state. A stop between cell scans,
from either active control or a new hint, clears only unfinished speed learning.

Kani cannot execute libc `hypot`, used to calculate departure uncertainty. Inlining the entire
allocation path also grew to about 22 GB of solver memory and was stopped; a numerical fitting
proof was stopped after more than three minutes to retain practical CI costs. A combined success/
failure two-batch harness exceeded five minutes; splitting those cases retains both domains and
all assertions. Production allocation, sample insertion, fit finalization, departure confirmation, recovery cleanup and speed dispatch
are therefore small separate helpers; arithmetic and invocation order are unchanged. Proofs call
these actual helpers at their input/result boundaries. They do not prove the numerical slope,
`hypot`, regression fitter, or the complete `select` path. No production operation is stubbed.
Existing real-route, fitting and delayed-GPS regressions exercise the complete path. Unwind bounds
are 3, 10, 16 or 18 as declared, with all bounds checks enabled; arbitrary histories remain outside
scope.

## Turn correction policy

Turn proofs call the complete production `apply_turn` operation with explicit landmark tables
(up to two entries). The table fixture does not establish geographic turn extraction or route
construction correctness. The filter starts at position 400 m, speed 10 m/s, position sigma 100 m,
speed sigma 2 m/s, cross-covariance 2 and drift 50 m; normal observation times are start 1,000 ms,
end 5,000 ms, current 6,000 ms and angle +90°. All harnesses use unwind 4.

- Ingress rejection covers every signed start/end timestamp and floating-point angle bit pattern,
  with route start 1,000 ms and previous input 3,000 ms. An independent `i128` age/duration calculation
  identifies invalid inputs; these must preserve the complete estimator.
- Fresh GPS position ages 0–2,500 ms, active motion control, walking, speed 0/19 m/s, and position
  variance 90,001 m² each prevent correction. Except for walking's early rejection, only the seen
  input watermark advances; it must not mark the turn as successfully used.
- Cooldown uses a second observation ending at 20,000 ms and all integer previous-use gaps 1–16,000 ms.
  A distinct matching landmark is usable exactly from 15,000 ms. Another proof starts with a used
  landmark at 5,000 ms, then rejects reuse at the cooldown boundary and every integer delay up to
  65,535 ms beyond it; only the seen-input watermark may advance.
- Two matching landmarks, an opposite-angle rival 0–63.75 m away in quarter-metre increments, or an
  empty table cannot change filter state or successful-use bookkeeping. A separate proof covers
  integer rival distances 0–255 m and checks acceptance exactly at the 80 m isolation boundary.
- One isolated matching landmark at every integer position 300–550 m produces an accepted correction
  bounded by 30 m in either direction, retains speed/speed variance/drift and position uncertainty
  of at least 30 m, and never refreshes GPS/OBD timestamps. Reoffering that observation preserves
  the entire logical state. Forward, backward and capped corrections have required witnesses.

Inlining successful correction and a later reuse attempt exceeded the five-minute solver budget.
Successful-use recording and rejection from an already-used state are therefore proved separately;
the full two-turn sequence remains a regression test. No operation is stubbed.

These are bounded policy contracts, not a proof that an observed rotation corresponds to a real
road turn. Existing real-route/delayed-GPS regression tests remain the integration checks.

## CI and adding proofs

`.github/workflows/native-verification.yml` runs on every pull request, main push and manual dispatch,
without an Android SDK or JNI build. The failing check is **Native core bounded proofs**. Repository
branch protection/rulesets must list that check as required to enforce it at merge time; a workflow
file alone cannot configure repository rules. Reports/logs are uploaded even on failure, retained
for 14 days; the job deadline is 40 minutes.

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
