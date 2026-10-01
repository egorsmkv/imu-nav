# Native Rust crates

The `native` Cargo workspace contains the route-constrained navigation code that Android runs
through JNI. It has two crates:

| Crate | Type | Purpose |
|---|---|---|
| [`imu-nav-core`](nav-core/) | Rust library | Android-independent navigation algorithms and state |
| [`imu-nav-jni`](nav-jni/) | `cdylib` | JNI adapter that exposes `imu-nav-core` to Kotlin as `libimu_nav_jni.so` |

The split keeps the algorithms deterministic and directly testable on the host. Android-specific
array conversion, handle ownership and error codes stay in the JNI crate.

## `imu-nav-core`

`imu-nav-core` has no external dependencies and forbids unsafe Rust. Its modules are:

### `lib.rs`: route-state filter

The crate root defines a fixed-size linear Kalman filter whose state is:

```text
x = [s, v]
```

`s` is distance in metres along the route and `v` is along-route speed in metres per second. The
filter provides:

- constant-velocity prediction with acceleration process noise;
- position and speed measurement updates;
- normalized-innovation-squared gates for statistical outliers;
- Joseph-form covariance updates;
- trusted position anchors;
- a distance-proportional systematic-drift allowance in addition to covariance; and
- a conservative safety radius that combines random uncertainty and systematic drift.

The main public types are `RouteFilter`, `Estimate`, `Covariance2`, `UpdateOutcome` and
`FilterError`.

### `estimator.rs`: navigation estimator

`NavigationEstimator` owns a `RouteFilter` and applies the policy needed to run it during a trip. It:

- selects car or walking process noise;
- advances the filter to measurement timestamps and elapsed-realtime ticks;
- applies delayed GNSS fixes at their observation time, replaying later predictions, coarse fixes, motion hints and OBD
  readings from a bounded history (up to five seconds / 128 checkpoints); future, duplicate,
  out-of-order and expired GNSS fixes are ignored;
- projects trusted or suspect GNSS observations onto the route;
- rejects both position and speed from SUSPECT fixes at least 60 m off-route or at least 300 m
  from the estimate at measurement time, independently of the innovation gates;
- derives measurement uncertainty from GNSS accuracy and trust level;
- incorporates GNSS speed and fresh OBD-II vehicle speed;
- uses shared IMU stop/resume hints for car dead reckoning, with measured-speed and network-motion vetoes;
- gently corrects car position from confirmed coarse CELL/NET fixes without treating them as precise anchors;
- applies one-shot, bounded position corrections from completed IMU rotations matching isolated route turns;
- learns a per-trip OBD speed scale from precise, accepted GOOD GNSS while raw OBD has remained
  stable for at least three seconds; subsequent OBD updates use the learned scale;
- uses lower drift growth only after an accepted OBD speed update and until its 2.5-second
  expiry; rejected readings cannot extend freshness, and duplicate or stale OBD inputs are ignored;
- resets systematic drift after an accepted GOOD GNSS position; and
- replaces and re-anchors route geometry after rerouting, discarding the previous route's history.

The SUSPECT gates match the live engine's normal consistency limits. The comparison estimator
does not receive jam-recovery state, so it does not apply the live engine's relaxed recovery jump
limit. Neither these checks nor Kalman innovation gating replace the upstream trust classifier.
Prediction splits long intervals into steps of at most five seconds and splits at OBD expiry,
so all elapsed travel is accounted for without applying fresh-OBD uncertainty to earlier travel.

OBD scale learning requires speeds of at least 5 m/s, a GNSS/OBD time difference no greater than
250 ms, GNSS speed uncertainty at most 0.8 m/s, position uncertainty at most 20 m, and route offset
below 25 m. The stable raw OBD range is at most 0.5 m/s with no gap longer than one second.
Only ratios in 0.8–1.2 qualify, blended by 5% per accepted GNSS observation. SUSPECT fixes and
missing accuracy fields cannot calibrate the scale. Scale and plateau history are checkpointed
with the filter so delayed GNSS and OBD replay produce the same calibration as chronological input.
The existing systematic-drift allowance remains; learning a scale does not eliminate uncertainty.

Stop/resume hints reuse the Kotlin detector's hold time, resume confirmation and acceleration ramp.
They are derived from recorded IMU/network inputs, not an independent velocity measurement. Fresh
accepted GOOD GNSS or OBD speed takes precedence; recent non-duplicate network positions veto a stop
when estimated speed minus three standard deviations exceeds 1 m/s. Walking ignores these hints.
Applying a hint changes the speed prior without reducing position uncertainty or resetting drift.
The pre-stop cruising speed is retained for resuming. Hints expire two seconds after the last actual
IMU sample, including across long tick gaps; missing or vetoed hints restore an uncertain cruising
prior instead of leaving the car permanently stopped. Delayed GNSS replays the recorded hints.

If the stopped/ramping speed model rejects a GOOD GNSS or OBD speed, the estimator also tests the
saved cruising-speed prior (6 m/s standard deviation) with the same innovation gate. This prevents
a false stop from repeatedly rejecting legitimate highway speeds above the stopped model's gate.
SUSPECT GNSS cannot use this alternative. Rejection by both models leaves the predicted state and
measurement freshness unchanged; accepting a speed clears motion control but does not correct
position, reduce its uncertainty, or erase systematic drift. A measured zero speed still uses the
stopped model first, rather than forcing a return to cruise.

Quiet highway motion cannot reliably be distinguished from standing still by vibration alone.
Likewise, prolonged phone handling can look like movement. If IMU disappears during a real stop,
the cruising fallback can drift forward. These are unresolved model limitations, not guarantees
of stop detection; the estimator remains comparison-only in this research prototype.

It returns a `TickOutcome` containing the estimate, optional route projection, and whether the
position and speed measurements passed their innovation gates.

### `estimator/network_position.rs`: coarse position correction

The comparison estimator receives `PositioningHub.lastNet`, independently of the live engine's
position corrections. The Kotlin JNI wrapper excludes mock, GPS and fused fixes. Only car mode
uses this input; no new recording fields or sensor polling are needed.

- Fixes must be no more than 2.5 seconds old, with a positive reported accuracy at most 200 m.
  Accuracy is floored at 30 m. Future/out-of-order timestamps and the last eight repeated coordinates
  cannot contribute evidence. Projection work is limited to one distinct fix per five observation seconds.
- Three fixes spanning at least ten seconds must be reachable in sequence, with no gap over fifteen
  seconds and no abrupt change in residual relative to the prediction. An inconsistent fix restarts
  confirmation. A global projection rejects a coarse error corridor touching distant route occurrences,
  such as parallel returns, loops or crossings; fixes farther off-route than their accuracy are ignored.
- Fresh accepted GOOD GNSS position takes precedence. Corrections also require a discrepancy no larger
  than 500 m and a normalized squared innovation no larger than 9. Coherent but very distant cell
  locations do not force reacquisition.
- The coarse model uses twice the floored accuracy, plus a 150 km/h travel allowance for input age.
  It is applied at the tick time, not extrapolated with the DR speed. Each correction is at most
  25% of the innovation and 50 m. Position uncertainty cannot fall below that coarse model's standard
  deviation; speed is unchanged and accumulated systematic drift is retained. Position-speed
  correlation is cleared so later speed updates cannot reuse a coarse correction as precise evidence.
- Evidence and raw correction inputs are checkpointed for delayed-GNSS replay. At equal timestamps,
  OBD precedes GNSS calibration, then GNSS precedes coarse positions, motion hints and turn evidence. Rerouting
  discards route-specific confirmation while retaining duplicate protection.

These gates cannot eliminate persistent, plausible cell bias. They deliberately decline very coarse
coverage, ambiguous routes and recovery beyond 500 m rather than forcing a large position jump.
Reported uncertainty remains a model, not a navigation safety guarantee.

### `estimator/network_position/speed.rs`: opt-in cell-derived speed

`--native-network-speed` enables this **off-by-default** replay experiment. The app comparison path
and default replay retain position-only corrections. The same eligible coarse input is used, with no
new recording format or polling. `--no-native-network` takes precedence and disables both correction
channels (shared Kotlin cell-derived motion vetoes remain separate).

- Eligible fixes alternate between speed and position. Four speed-reserved fixes spanning 30–60 s
  form a batch; batches never overlap. A speed-reserved fix never also updates position, even if the
  batch is incomplete or rejected. Position corrections consequently arrive less often.
- Weighted regression uses the raw projected positions, not corrected filter positions. Speed sigma
  is floored at 2 m/s and must be at most 4 m/s. The three-sigma lower bound must exceed 1 m/s, and
  speed must be at most 150 km/h. Adjacent segment speeds must agree within twice sigma (at least
  2 m/s), rejecting mixed stop/start or tower-jump windows. Input age adds 0.5 m/s per second to sigma.
- Speed updates pass an innovation gate of 9, have gain at most 0.5, and change speed by at most
  2 m/s. They leave position and systematic drift untouched, clear position-speed covariance and
  retain the speed-variance floor. They do not refresh GPS/OBD freshness or the OBD drift allowance.
- Fresh accepted GPS/OBD and stop/resume hints take priority. Accepted measured speed, accepted GOOD
  GPS position, intervening stop/ramp hints, rerouting, inconsistent fixes and gaps discard unfinished
  windows. Batch contents and allocation are checkpointed for delayed-GPS replay.
- Accepted cell learning saves the pre-learning speed prior. Two consecutive speed-reserved intervals
  can retract that learning before a full batch finishes: both interval speeds must disagree with the
  current speed in the same direction by more than `max(3 m/s, hypot(accuracy1, accuracy2) / dt)`, and
  both must be closer to the saved prior. The prior must exceed 1 m/s; a startup zero cannot invent a
  stop. This is a model-invalidation heuristic, not a confidence test or a new speed measurement.
  A single tower step, alternating errors, or a worse prior cannot trigger it.
- Recovery restores that prior, retains at least the previous speed variance and a 6 m/s sigma,
  and leaves position and accumulated drift unchanged. The triggering fix is speed-only; subsequent
  fixes get position-only corrections for 30 seconds. A fresh, disjoint batch is then required before
  learning resumes. Saved priors, departure confirmation and recovery timing are also checkpointed.

Disjoint fixes prevent direct sample reuse, not correlation between tower errors. Persistent coherent
tower drift can still look like speed. Recovery only helps when a saved prior better explains the
contradictory movement; smaller changes, changes away from that prior, and inaccurate cells still lag.
It is not an instantaneous speed sensor. Peak errors can exceed position-only correction, so the
experiment remains off by default pending broader real-drive validation.

### `estimator/turn.rs`: isolated turn matching

The shared pure-Kotlin `TurnDetector` uses recorded IMU samples and the existing gyro-bias estimate,
independently of the live engine's turn matching and snaps. It requires one second of low yaw before
rotation and 700 ms after it, a 1.5–8 second turn of 45–125 degrees, and at least 90% directional
consistency. Missing/non-finite inputs, gaps over 200 ms, yaw over 55 deg/s, excessive non-yaw rotation,
and acceleration above 6 m/s² invalidate evidence. Completed evidence expires two seconds after the
turn ends; repeated navigation ticks cannot refresh it. Trip and route changes reset this detector.

Rust indexes compact corners with straight approaches when immutable geometry is created. Broad
curves, U-turns and complex corner clusters are excluded. Car-only matching requires one candidate
within a 60–180 m prediction window and 15 degrees of the measured signed angle, with no other indexed
turn within 80 m. Fresh GOOD GPS, a stop/resume speed prior, speed outside 2–18 m/s, position sigma
above 300 m, or turn uncertainty above 100 m veto the correction. Events are consumed once; matching
has a 15-second cooldown and cannot immediately reuse the same landmark, even after that cooldown.

The turn midpoint is approximate: current speed advances its route position to the current tick.
Uncertainty includes a 30 m floor, half the modelled turn travel and speed uncertainty over that age.
The existing coarse-position update limits correction to 25% of the innovation and 30 m, keeps a
measurement-sized uncertainty floor, and changes neither speed nor accumulated systematic drift.
Turn evidence and deduplication state participate in delayed-GNSS replay, including equal timestamps;
reroutes reject rotations begun on the old route.

A slow, smooth phone yaw while driving can still resemble a vehicle turn. Likewise, a wrong road
branch with a similar angle can match planned geometry: this is not independent intersection or
off-route recognition. These unresolved ambiguities are why corrections remain weak and the native
estimator remains comparison-only in this research prototype.

### `route.rs`: route geometry

`RouteGeometry` stores a geographic polyline and its cumulative distances. It validates coordinates
and projects a `GeoPoint` to the nearest route segment, returning:

- distance along the route;
- perpendicular offset from the route;
- segment index; and
- the projected geographic point.

Projection first searches a window around the current `s` value. If that result is too far from the
route, it can fall back to a global search. This avoids snapping to a distant repeated section while
still allowing recovery from a large position error.

### `trust.rs`: GNSS trust firewall

`TrustClassifier` classifies each GNSS fix as `Good`, `Suspect` or `Bad` before the fix reaches the
navigation estimator. Its checks cover:

- invalid, mock and out-of-service-area fixes;
- altitude, speed, accuracy and wall-clock consistency;
- duplicate timestamps, impossible jumps and frozen coordinates;
- reported speed versus geographic displacement;
- disagreement with an independent network fix;
- satellite count, C/N0 strength and C/N0 spread;
- GNSS bearing versus compass heading; and
- weak, hard and chained jamming evidence.

The same module contains `JamDetector`, an AGC-based state machine with hysteresis. Trust checks are
separate from Kalman innovation gating because a gradual spoofing signal may remain statistically
plausible.

### `network.rs`: cell/network position tracking

`NetworkTracker` handles route-projected network and cell observations. It:

- rejects positions that are not physically reachable from the current anchor;
- accepts a new anchor only after consistent evidence;
- keeps short recent and 30-second history views;
- detects whether the last two samples are mutually consistent; and
- feeds suitable, non-duplicate samples to the network speed estimator.

### `speed.rs`: speed estimation and fusion

This module contains two related components:

- `NetworkSpeedEstimator` fits weighted position-over-time regression to recent network samples,
  removes large residual outliers, and reports speed with uncertainty.
- `fuse_speed` combines available GNSS speed, route-speed prior and network-derived speed using
  inverse-variance weighting, with increasing uncertainty as the last GNSS speed ages.

## `imu-nav-jni`

`imu-nav-jni` is the Android boundary. It does not contain navigation policy of its own; it validates
and converts JNI inputs, calls `imu-nav-core`, and converts results back to primitive Java arrays and
status codes.

The exported functions are grouped by their Kotlin owners in
`app/src/main/kotlin/org/imunav/app/nativecore/`:

| Kotlin wrapper | Native state or operation |
|---|---|
| `NativeTrustEvaluator` | `TrustClassifier` and `JamDetector` lifecycle, AGC updates and fix verdicts |
| `NativeRouteGeometry` | Route creation, destruction and point projection |
| `NativeRouteFilter` | Low-level filter prediction, measurement updates, route installation and state reads |
| `NativeNavigationEstimator` | Trip estimator lifecycle, ticks, OBD speed and route replacement |
| `NativeNetworkTracker` | Network/cell gating, sample history and speed regression |
| `NativeSpeedFusion` | Stateless inverse-variance speed fusion |

Stateful objects live in synchronized Rust registries. Kotlin receives opaque integer handles, not
native pointers. Invalid or already-destroyed handles return an error instead of dereferencing freed
memory. Every JNI entry point also catches Rust panics so none unwind across the JNI boundary.

## Android integration

Android currently uses the native trust classifier, jamming detector, route projector, network
tracker and speed fusion in the live navigation engine. `NativeNavigationEstimator` runs beside the
established Kotlin engine and logs their difference while its tuning is validated against recorded
trips; it does not yet own the position shown to the user.

The Kotlin/JVM implementations remain in `:core` for replay, parity tests and non-Android tools.
Changes to native behavior should keep the corresponding Kotlin behavior and boundary tests aligned.

## Building and testing

Run the host-side Rust checks from the repository root:

```bash
cargo fmt --manifest-path native/Cargo.toml --all --check
cargo test --manifest-path native/Cargo.toml
cargo clippy --manifest-path native/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```

The root Gradle `check` task runs the same checks through `rustFmtCheck`, `rustTest` and
`rustClippy`.

Android builds invoke [`scripts/build-rust-android.sh`](../scripts/build-rust-android.sh). The script
uses the Android NDK to build `imu-nav-jni` for:

- `arm64-v8a`;
- `armeabi-v7a`; and
- `x86_64`.

It copies the resulting `libimu_nav_jni.so` files into
`app/build/generated/rustJniLibs/<abi>/`, which the Android Gradle plugin packages with the app. The
minimum native Android API is 26, matching the app's `minSdk`.

## Host replay comparison

The JVM replay tool can load this exact JNI implementation alongside the Kotlin engine:

```bash
./gradlew -PnativeReplay :replay:run --args="trips/ --compare-native --hide-gps-after 120 --out native-replay-out"
./gradlew :replay:test
```

Both require Rust 1.99+ and a JDK, but no Android SDK. The replay module compiles the app's pure-JVM
JNI wrappers directly, preserving their exported JNI names. Gradle tracks the host library as a
test input so Rust changes invalidate the JNI integration tests.

Comparison samples use exact trusted GPS timestamps; hidden GPS only reaches an independent
reference classifier. CSV retains off-route samples, while summary statistics exclude reference
offsets of 60 m or more. Global reference projection avoids favouring either estimator but cannot
resolve repeated-route ambiguity. These are GPS-reference errors, not independent survey accuracy.

Synthetic regression drives cover unbiased OBD, a 5% high OBD scale, and noisy 5% low OBD with
braking/stopping/restarting during a five-minute outage after two minutes of GNSS calibration.
On the ideal 5%-high case, adding scale learning reduced native blind p95 from 223 m to about 1 m.
This isolates scale drift under controlled inputs; it is not a claim of real-world metre accuracy.

No-OBD JNI regressions also cover braking, a long stop and restart during a two-minute GPS outage,
brief phone movement, missing IMU and smooth travel with cell-derived movement evidence. On this
synthetic stop/start drive, motion hints reduce native blind p95 from 630 m to 45 m; this is not a
real-drive accuracy claim. To compare recordings with hints disabled, add `--no-native-motion`
to the same `--compare-native` command and use a separate output directory.

A separate 126 km/h synthetic drive deliberately uses quiet IMU without cells, hides GPS after
60 seconds and introduces OBD at 90 seconds. Previously the false-stop model rejected every returning
OBD reading: final error at 180 seconds was 4,148 m. Testing the saved cruising model restores motion
on the first OBD reading, limiting final error to 998 m. The error accumulated before OBD returns
remains; this regression demonstrates recovery, not a solution to quiet-highway stop ambiguity.

The coarse-position JNI regression hides GPS at 60 seconds while actual speed rises from 15 to
17 m/s, then supplies 40 m-accuracy cell fixes with alternating ±20 m along-route error every five
seconds. At 300 seconds, blind native p95 is 481 m without corrections versus 43 m with them.
This is a synthetic regression, not measured road accuracy. `--no-native-network` disables native
coarse corrections for A/B replay; cell-derived motion vetoes remain controlled separately.

The optional speed experiment has a separate 600-second synthetic drive: GPS is hidden at 60 s,
actual speed rises from 15 to 17 m/s, and 40 m-accuracy noisy cells stop at 220 s. Blind native p95
is 771 m with position-only corrections versus 51 m with speed learning. With continuous cells at
that speed, p95 is 41 m versus 51 m (RMS improves from 36 m to 21 m). With later abrupt changes to
20 and then 12 m/s, the first speed-learning policy worsened p95 from 92 m to 267 m. Early prior
recovery reduces that to 90 m (RMS 53 m versus position-only 56 m), but maximum error is still higher:
127 m versus 100 m. Moving the slowdown through eight five-second batch phases gives p95 90–100 m
and maximum errors 125–153 m. These are synthetic regression results, not real-drive accuracy claims.
Run the same recording once without and once with
`--native-network-speed` using separate output directories; the default remains position-only.

Turn JNI regressions hide GPS at 60 seconds, lower actual speed from 10 to 8 m/s without OBD/cells,
and complete a left or right turn at 80 seconds. Over the 40-second blind interval, native p95 falls
from 75 m with turns disabled to 65 m with the bounded correction (maximum error 79 m to 69 m).
This synthetic gain is deliberately modest: one landmark does not fix an ongoing speed-model error.
Phone swings, tilt-heavy movement, opposite yaw, missed turns and fresh-GPS cases must not introduce
corrections. Use `--no-native-turns` with `--compare-native` for the corresponding recording A/B run.
