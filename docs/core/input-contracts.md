# Navigation input contracts

[Rust core guide](readme.md) · [Positioning and trust](positioning-and-trust.md)

Use this reference when supplying network observations or comparing Rust with Kotlin replay.
Android uses native trust, route projection, network tracking and speed fusion through JNI.
The pure-Kotlin implementations in `:core` support JVM replay and parity tests. The native
`NavigationEstimator` is a separate comparison path; its correction rules are described in
[cautious corrections](corrections.md).

## Units and time domains

| Value | Contract |
| --- | --- |
| `s` / `position_m` | Metres along the planned route. It is a projected coordinate, not latitude or longitude. |
| Latitude / longitude | Degrees; inclusive ranges −90…90 and −180…180. |
| Accuracy / offset | Metres. Recording requires finite values and non-negative accuracy; the recording boundary does not require a non-negative offset. |
| Speed / sigma | Metres per second. Sigma is the model's standard deviation, not a guaranteed error bound. |
| `elapsedMs` / `elapsed_ms` | Milliseconds on one monotonic time axis. App observations use `SystemClock.elapsedRealtime()`; do not mix them with wall-clock time. |
| `nowMs` / `now_ms` | Query time on the same axis as the stored samples. |
| `spanS` / `span_s` | Seconds covered by the observations surviving the regression. |

The standalone network speed estimator accepts signed timestamps as long as additions are strictly
increasing. `NetworkTracker.gate` and `record` require non-negative elapsed time. These are distinct
boundaries; acceptance by the speed estimator alone does not make an app fix valid.

## Network gating and recording

Call the physical gate before recording a fix for navigation. `gate` returns `ACCEPTED`,
`REANCHORED` or `REJECTED` in Kotlin (`Accepted`, `Reanchored`, `Rejected` in Rust).
A valid but unreachable fix may update a pending reanchor chain even when the result is rejected.
Malformed or stale input cannot update that chain or the anchor.

`record` validates the supplied sample independently; it neither runs the physical gate nor
enforces timestamp order. It ignores negative elapsed time or accuracy, non-finite route
position/accuracy/offset, and invalid coordinates. An ignored record leaves recent samples,
history, speed observations and duplicate-coordinate tracking unchanged.

For a valid record:

- The recent list keeps at most four samples, including repeated coordinates.
- An exact repeat of the previous latitude/longitude does not enter history or speed regression.
- A distinct coordinate enters history; accuracy of at most 120 m also feeds speed regression.
- `pruneHistory` is a separate call that removes history older than 30 seconds.
- `clearSamples` clears recent/history/speed observations but keeps the gate anchor and coordinate
  duplicate tracking. `reset` clears those too.

Zero accuracy and coordinates at the geographic range boundaries are accepted by `record`.
They do not bypass downstream correction or trust checks.

Sources: [Rust tracker](../../native/nav-core/src/network.rs),
[Kotlin tracker](../../core/src/main/kotlin/org/imunav/core/nav/NetworkTracker.kt),
[Rust recording tests](../../native/nav-core/src/network/tests.rs) and
[Kotlin ingress tests](../../core/src/test/kotlin/org/imunav/core/PositioningIngressTest.kt).

## Network speed regression

`add(position, accuracy, elapsedTime)` ignores non-finite position, non-finite or negative accuracy,
and duplicate/out-of-order timestamps. Rejection does not consume the timestamp: a corrected
sample can be added at the same time. At most 60 observations are retained.

`estimate(now)` tries these windows in order; `strictEstimate` / `strict_estimate` uses only the last:

| Window ending at `now` | Minimum surviving span |
| --- | --- |
| 30 seconds | 15 seconds |
| 40 seconds | 20 seconds |
| 90 seconds | 30 seconds |

Each window includes its exact expiry boundary and excludes future observations and overflowing
ages. Querying an earlier time does not consume stored observations. At least four samples must
remain after outlier removal. Weighting floors reported accuracy at 20 m; residuals beyond
`max(250 m, 3 × floored accuracy)` are removed before refitting. The surviving fit must be finite,
with positive sigma; sigma is multiplied by 1.5 and must be at most 4 m/s. A negative fitted slope
is published as zero speed. Insufficient evidence or an unusable fit returns Rust `None` or Kotlin
`null`, rather than an unfiltered fallback.

## Speed fusion

Fusion ignores negative/non-finite GPS or route-prior speed. A network estimate needs finite,
non-negative speed, sigma and span before its sigma is floored at 0.3 m/s. A negative GPS age is
treated as zero. GPS sigma starts at 1.5 m/s and grows by 0.08 m/s per second of age; the route
prior uses 6 m/s. Available sources are combined with inverse-variance weights, and usable output
speed is capped at 150 km/h.

Rust returns a `SpeedEstimate`: `samples` counts contributing sources, and `span_s` copies the
valid network estimate's span (zero if absent). Kotlin and JNI return only the speed scalar.

No usable source or non-finite weighted arithmetic returns Rust `None`. Kotlin `SpeedFusion` and
the JNI speed wrapper return `0.0` in that case. This sentinel is also a possible real speed;
the scalar return alone cannot distinguish absent evidence from a stationary vehicle.

Sources: [Rust speed math](../../native/nav-core/src/speed.rs),
[Kotlin speed math](../../core/src/main/kotlin/org/imunav/core/speed/Speed.kt) and
[Kotlin robustness tests](../../core/src/test/kotlin/org/imunav/core/SpeedRobustnessTest.kt).

### Rust example

This host example uses `imu-nav-core` with its default features. Four samples span 30 seconds at
10 m/s. The earlier query has no eligible observations; the later query still sees all four.

In a host Cargo binary project, add this dependency to `Cargo.toml`, replacing `/path/to/imu-nav`
with your checkout's absolute path. Save the Rust code as `src/main.rs` and run `cargo run`.

```toml
[dependencies]
imu-nav-core = { path = "/path/to/imu-nav/native/nav-core" }
```

```rust
use imu_nav_core::speed::{NetworkSpeedEstimator, fuse_speed};

fn main() {
    let mut estimator = NetworkSpeedEstimator::default();
    for elapsed_ms in [5_000_i64, 15_000, 25_000, 35_000] {
        let position_m = 10.0 * (elapsed_ms as f64 / 1_000.0);
        estimator.add(position_m, 20.0, elapsed_ms);
    }
    assert!(estimator.estimate(4_000).is_none());
    let estimate = estimator.strict_estimate(35_000).expect("four valid samples");
    assert!((estimate.speed_mps - 10.0).abs() < 1e-9);
    assert_eq!(estimate.samples, 4);
    assert!(fuse_speed(None, 0, None, None).is_none());
}
```

### Kotlin example

This equivalent JVM example uses the `:core` module, with no Android imports. Save it as
`SpeedExample.kt` in a JVM project that depends on `:core` and run its `main` function.

```kotlin
import org.imunav.core.speed.NetSpeedEstimator
import org.imunav.core.speed.SpeedFusion
import kotlin.math.abs

fun main() {
    val estimator = NetSpeedEstimator()
    for (elapsedMs in listOf(5_000L, 15_000L, 25_000L, 35_000L)) {
        val positionM = 10.0 * elapsedMs / 1_000.0
        estimator.add(positionM, 20.0, elapsedMs)
    }
    check(estimator.estimate(4_000L) == null)
    val estimate = checkNotNull(estimator.strictEstimate(35_000L))
    check(abs(estimate.speedMps - 10.0) < 1e-9)
    check(estimate.samples == 4)
    check(SpeedFusion.fuse(null, 0L, null, null) == 0.0)
}
```

Both examples finish without output when their checks pass. Compile them against the source
checkout; they do not require a phone, trip recording or production service.

## Kotlin correction boundaries

The live Kotlin engine checks network deviation evidence before calling the tracker gate, so
[`NetworkDeviationDetector`](../../core/src/main/kotlin/org/imunav/core/nav/NetworkDeviationDetector.kt)
performs its own numeric, coordinate, accuracy and offset validation. Invalid evidence cannot count
toward a reroute confirmation.

For marker-ahead correction in
[`NetworkCorrection`](../../core/src/main/kotlin/org/imunav/core/nav/NetworkCorrection.kt), zero-accuracy
observations define the mean of their predicted positions; other observations do not contribute
to that mean. With positive accuracies, the existing inverse-variance mean is used. A non-finite
mean leaves progress unchanged, and an accepted correction remains capped at 300 m.
These rules do not describe the comparison estimator's separate, more restrictive coarse input.
