# Trips: history, recording, restore and replay

> Detailed reference. For easy steps, see the [guide](../TRIPS.md) or the [glossary](../GLOSSARY.md).
> Every navigation is recorded to `files/trips/trip-<time>-<uuid>.rec.gz` in app storage — all fixes, IMU
> samples, satellite/AGC status, routes and the engine's own estimates (gzip text, flushed every 2 s).
> Opening, repairing, writing, closing and discarding recordings share one worker. Rapid stop/start
> actions use separate files; older timestamp-only filenames remain readable.

- **History** (clock icon on the map) lists trips with totals; a trip shows its trusted-GPS track and
  the engine's estimate on a map, distance, duration, moving time, time/distance without GPS and the
  largest uncertainty. _Snap to roads_ map-matches the drive with GraphHopper.
- **Share a trip:** open it in History and tap the share icon. Android's share menu sends a separate
  `.zip` archive containing a plain `.rec` file with the route, positions and recorded sensor events.
  Extract the `.rec` file to use it with the replay tool. A truncated gzip tail in the app's recording
  is salvaged while building the ZIP.
  The original stays in app storage. Preparing the attachment runs in the background; missing files
  or insufficient storage produce an error without changing the trip.
- **Surviving the app being killed:** the active trip is saved every 10 s. If Android or the user kills
  the app mid-trip, the next start (within 3 h) restores the route and position — widening the
  uncertainty for the time lost — restarts the foreground service and keeps recording into the same
  file (a recording cut off by the kill is salvaged first). A `Q` event records the restored distance
  along the route after the new start and route events. Kotlin and native comparison replay reset
  transient sensor state and use that distance and the restored uncertainty. Kotlin also marks
  already passed route turns as consumed. Older recordings without `Q` retain their existing replay
  behavior. Accept the battery-optimisation exemption
  when offered (**Settings → Everyday settings → Battery**) so this is rare.
- **Replay tool:** re-runs recordings through the engine on a computer, optionally hiding GPS after
  N seconds, and compares the engine against the real (trusted GPS) track:

  ```bash
  ./gradlew :replay:run --args="path/to/trips --hide-gps-after 60,300 --out replay-out"
  ```

  `--set key=value,...` overrides `Tuning` fields and `--ukraine` enables the service-area check.
  It writes `summary.txt` (median / p95 / max error with and without GPS), `errors-*.csv` per trip and
  `compare-*.geojson` (real vs. engine tracks) for any GeoJSON viewer.

- **Native estimator comparison:** build the host JNI library and score the Rust estimator and
  Kotlin engine at the same trusted GPS timestamps (no Android SDK needed):

  ```bash
  ./gradlew -PnativeReplay :replay:run --args="path/to/trips --compare-native --hide-gps-after 30,120,300 --out native-replay-out"
  ```

  Each value starts a separate run that hides GPS after that many seconds until the trip ends.
  Reference GPS is classified separately and never enters either navigation estimator or its
  gyro-bias learner during the hidden interval. Reports include paired errors and native safety-radius
  coverage in `summary.txt` and `native-errors-*.csv`. Reference GPS is not survey ground truth;
  route projections can be ambiguous on repeated sections. This stricter comparison has different
  sampling/hidden-GPS semantics from the legacy replay, so their error figures are not interchangeable.
  On Linux, add `-PnativeHeapProfile` and `--native-heap-profile heap-profiles` to capture sampled Rust heap
  profiles during `--compare-native` replay; see [native profiling](../core/profiling.md#native-heap-profiling-linux-host-replay).
  For deterministic app-like native workloads without recordings, use the [simulation and profiling crate](../core/sim/README.md).
  Its twelve scenarios cover GPS loss/delay, walking, session lifecycles, dense OBD inputs, curved routes,
  crossings, parallel returns and global reacquisition; `--scenario advanced` selects the five focused stress cases.
  Rust 1.99+ is required; `:replay:test` also builds the host library and runs synthetic JNI comparisons.
  Native car dead reckoning now uses the shared IMU stop/resume detector when fresh GPS/OBD speed
  and confident cell movement do not contradict it. Add `--no-native-motion` to disable these hints
  for an A/B comparison. Hints expire with the IMU samples; they are speed-model assumptions, not
  position anchors. Quiet highway travel without independent movement evidence remains ambiguous.
  After a false stop, returning GOOD GPS or OBD speed can also be checked against the saved cruising
  speed, so valid highway-speed readings are not locked out by the near-zero stop model. Both models
  retain innovation gates; recovering speed does not erase accumulated position error.
  **Settings → Everyday settings → Navigation without GPS → Position estimator** offers
  **Classic navigation (default)** and **Kalman filter**. Classic is selected when no estimator
  preference has been saved; an existing explicit choice is preserved. Select before starting navigation;
  changes are locked during a trip. Native mode owns position, speed, uncertainty and the
  state used by guidance for both driving and walking. Walking uses step cadence × learned stride;
  only fresh GOOD GPS whose position and speed were accepted can calibrate that stride. Fresh GPS
  speed takes priority. Without a step sensor, fresh IMU permits an uncertain typical-pace model;
  without either input or fresh GPS, native walking holds instead of assuming continued movement.
  Step/IMU expiry also bounds prediction across long tick gaps. OBD, car turn matching and coarse
  cell-position/speed corrections remain car-only. Existing step events (`P`) feed walking replay;
  `--no-native-motion` disables car stop/resume hints, not walking step evidence.
  Kalman mode replaces Classic navigation's fallback method
  and its terrain/compass/signal snaps; shared guidance and GPS/network deviation checks remain.
  This is a route-constrained linear Kalman filter, not a full inertial EKF. Better real-drive accuracy
  is not yet established; the app remains a research prototype, not a safety system.
  The choice is persisted with the active trip and recorded as a `K` event. During asynchronous
  native restoration, navigation holds position and suppresses guidance until the estimator is ready.
  Legacy Kotlin-only replay rejects native-selected trips instead of silently testing the wrong
  algorithm; use the paired `--compare-native` command above to evaluate their raw inputs (this is
  an A/B experiment, not exact reproduction of live guidance). Cell-speed learning remains off.
  With Classic navigation selected, the native estimator still runs only as a shadow comparison.
  A separate **Inertial ESKF shadow (experimental)** switch in the same settings section is off by
  default and locked during navigation/planning. It runs alongside either position owner, **only for
  mounted-phone driving**; it never changes the marker, route, speed display or guidance. Walking is
  deliberately excluded. The implementation is platform-independent Kotlin in `core/.../imu/eskf/`,
  not a replacement for the Rust route Kalman filter. It estimates 3-D position, velocity, quaternion
  attitude, accelerometer bias and gyro bias with a 15-dimensional error covariance. Prediction uses
  gravity-inclusive body acceleration and body angular rate, with Joseph measurement updates and
  right-multiplicative attitude-error injection/reset
  ([mathematical conventions](https://arxiv.org/abs/1711.02508)).

  The experiment requires fresh GOOD GPS (including speed), rotation vector, accelerometer and gyro
  to initialize; a manual start cannot initialize it. The Android rotation vector supplies the initial
  attitude only, corrected from magnetic to true north using declination anchored once per capture
  session to fresh GOOD GPS. Further orientation observations are not fused as independent measurements.
  Raw sensors use each power profile's existing rate; extra accelerometer capture increases battery
  and recording size. The new `U` event preserves sensor nanoseconds, callback arrival milliseconds,
  full quaternion and unrounded sensor values through `PositioningHub`. Existing `I` recordings cannot
  reconstruct these inputs. `eskf_shadow` log lines report status, accepted GPS, rejected inputs,
  resets and diagnostic sigma. Process restoration starts a fresh shadow requiring a new GPS anchor.

  Inputs are reordered within 250 ms; later GPS is rejected rather than applied at the wrong time.
  Missing/stale sensors or gaps over 250 ms invalidate the state and require reinitialization. The
  first version uses fixed local ENU gravity, approximate flat-earth coordinates and provisional
  noise densities. It has no Earth-rate/lever-arm/scale model, vehicle-frame constraints, OBD updates,
  terrain/route snaps or false-stationary zero-velocity updates. Course accuracy is not yet recorded,
  so horizontal GPS velocity uses a conservative noise floor. Phone movement, magnetic disturbances,
  long outages and unobservable biases can still cause severe drift. Sigma is **not a safety radius**.
  Synthetic tests validate mechanics, not improved real-drive accuracy: this remains a research
  prototype, not a safety system.

  For a new raw-sensor recording, run:

  ```bash
  ./gradlew -PnativeReplay :replay:run --args="trip.rec.gz --compare-eskf --hide-gps-after 30,60,120 --out eskf-report"
  ```

  This reports **horizontal** errors for Kotlin, native route Kalman and ESKF on identical available
  reference timestamps, plus missing pairs, rejected inputs and resets. The ESKF branch preserves
  arrival order and the live reorder window; hidden GPS cannot initialize it or train its biases.
  Scoring may align a preceding inertial estimate by at most 100 ms (the saver sensor period) of its own velocity; no future
  estimate is used. Startup/dropout/final-window gaps are reported, not scored as zero error.
  Reference GPS is not survey ground truth, and the baselines remain an A/B replay rather than exact
  live guidance reproduction. `--compare-eskf` cannot be combined with native-only comparison flags.

  The native route estimator also applies small cell/network position corrections after three distinct, consistent fixes
  spanning at least ten seconds. Cached fixes, ambiguous route matches, stale or very coarse fixes,
  and large discrepancies are excluded. Corrections preserve a coarse uncertainty floor and do not
  reset drift or change speed. Add `--no-native-network` to isolate their effect in paired replay.
  An opt-in `--native-network-speed` experiment reserves separate cell fixes for conservative speed
  regression. It reduces drift after cell coverage disappears in a synthetic steady-speed drive.
  When consecutive cell intervals contradict the learned speed and favour its earlier moving-speed
  prior, it retracts that learning and temporarily restores full-rate position corrections. This reduces
  abrupt-change lag, but peak error and small speed changes remain limitations requiring real-drive tests.
  Changes away from the saved prior can also trigger cautious relearning from a coherent new window;
  mixed windows are discarded in favour of position corrections. Sparse cell updates and slowly changing
  tower bias still cause large errors in synthetic tests, so this is not a general accuracy improvement.
  It is **off by default**, including the app comparison path; neither experiment controls live navigation.
  Completed IMU rotations can also give a bounded correction at an isolated, distinctive route
  turn. The native matcher rejects ambiguous matches and limits each correction to 30 m, retaining
  uncertainty and drift. `--no-native-turns` disables this independently for A/B replay. Smooth
  phone rotation while driving can still mimic a turn; this remains experimental, comparison-only.
