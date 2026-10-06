# Build, test and compare recorded trips

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

Run these commands from the repository root. The host checks exercise Rust without Android. Replay then feeds the same recorded trip to the Kotlin engine and the Rust estimator, making their differences visible. The numerical examples below are controlled regressions, not road-accuracy claims.

## Building and testing

Run the host-side Rust checks from the repository root:

```bash
cargo fmt --manifest-path native/Cargo.toml --all --check
cargo test --manifest-path native/Cargo.toml
cargo clippy --manifest-path native/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
```

The root Gradle `check` task runs the same checks through `rustFmtCheck`, `rustTest` and
`rustClippy`.

Android builds invoke [`scripts/build-rust-android.sh`](../../scripts/build-rust-android.sh). The script
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
is 771 m with position-only corrections versus 51 m with speed learning.

With continuous cells at
that speed, p95 is 41 m versus 51 m (RMS improves from 36 m to 21 m). With later abrupt changes to
20 and then 12 m/s, the first speed-learning policy worsened p95 from 92 m to 267 m.

Early prior
recovery reduces that to 90 m (RMS 53 m versus position-only 56 m), but maximum error is still higher:
127 m versus 100 m. Moving the slowdown through eight five-second batch phases gives p95 90–100 m
and maximum errors 125–153 m.

These are synthetic regression results, not real-drive accuracy claims.
Run the same recording once without and once with
`--native-network-speed` using separate output directories; the default remains position-only.

Additional 600-second synthetic manoeuvre regressions hide GPS at 60 s, change motion at 250 s and
use 40 m-accuracy cells with deterministic noise. The acceleration case rises from 17 to 25 m/s over
10 s, away from the saved 15 m/s prior; gradual braking falls from 17 to 9 m/s over 80 s.

Traffic adds
recorded-style IMU stops/restarts; irregular cells alternate 5/10/5/15 s intervals. Separate error cases
hold speed constant while cell bias ramps to 120 m or one tower fix jumps by 150 m.

| Scenario                           | Prior speed experiment p95 / max (m) | Updated experiment p95 / max (m) | Position-only p95 / max (m) |
| ---------------------------------- | ------------------------------------ | -------------------------------- | --------------------------- |
| Acceleration away from saved prior | 213 / 242                            | 172 / 213                        | 221 / 235                   |
| Gradual braking                    | 199 / 230                            | 172 / 202                        | 113 / 115                   |
| Repeated stops/restarts            | 110 / 168                            | 110 / 168                        | 82 / 118                    |
| Acceleration with irregular cells  | 1880 / 2042                          | 1394 / 1502                      | 2827 / 3101                 |
| Changing cell bias                 | 140 / 146                            | 140 / 146                        | 89 / 91                     |
| Isolated tower jump                | 50 / 63                              | 50 / 63                          | 43 / 62                     |

For acceleration, position error settles below 100 m at 125 s after the change versus 165 s before;
gradual braking now settles at 255 s. Recovery requires at least 30 s below that threshold through
the recording's end; a temporary crossing does not count.

The irregular and changing-bias cases do
not recover by this definition. These tests expose remaining failures, not acceptable navigation
error bounds or real-drive guarantees. The original cell-dropout p95 remains 51 m. The experiment
still loses to position-only correction on several scenarios and remains **off by default**.

Turn JNI regressions hide GPS at 60 seconds, lower actual speed from 10 to 8 m/s without OBD/cells,
and complete a left or right turn at 80 seconds. Over the 40-second blind interval, native p95 falls
from 75 m with turns disabled to 65 m with the bounded correction (maximum error 79 m to 69 m).

This synthetic gain is deliberately modest: one landmark does not fix an ongoing speed-model error.
Phone swings, tilt-heavy movement, opposite yaw, missed turns and fresh-GPS cases must not introduce
corrections. Use `--no-native-turns` with `--compare-native` for the corresponding recording A/B run.

