# Build and test

> Detailed reference. For easy steps, see the [guide](../build_and_test.md) or the [glossary](../glossary.md).

Runs on Android 8.0 (API 26) and newer; targets Android 16 (API 36). Build requirements: JDK 17+,
Android SDK 36, the Android NDK and Rust 1.99+ (edition 2024) with the `aarch64-linux-android`,
`armv7-linux-androideabi` and `x86_64-linux-android` targets. Set `ANDROID_NDK_HOME` when the NDK
is outside the Android SDK. Android builds compile and package the Rust estimator automatically.

For SDK/NDK selection, Rust target installation, APK output paths and setup failures, follow
the [tool preparation steps](../build_and_test.md#prepare-the-tools).

```bash
./gradlew test                # Kotlin engine, routing/search and replay tests
./gradlew :app:assemblePlayDebug    # normal development build
./gradlew :app:assembleFdroidRelease # unsigned F-Droid release build
./gradlew :app:assemblePlayBenchmark # release speed + freeze diagnostics, installs as org.imunav.app.bench
cargo test --manifest-path native/Cargo.toml # native estimator and covariance tests
cargo test --manifest-path server/Cargo.toml # persistent HTTP/WebSocket cell server tests
./gradlew :core:serverApiTest # Kotlin cell-sync client against a temporary local Rust server
```

The Kotlin server API test builds the Rust server, starts it on loopback with a temporary SQLite
database, and checks account sessions plus the app's tower upload, download, and removal calls.
It runs as part of `./gradlew check`; `:core:test` remains the fast JVM-only suite.
The Kotlin coverage CI workflow also runs this integration test on pull requests and pushes to
`main`, before collecting coverage.

**Native formal verification.** Run `python3 tools/verify_native.py` after installing pinned Kani 0.68.0.
The runner prints progress every 30 seconds and records cancellation diagnostics. CI caches the
verifier launchers and gives setup a separate budget from the 40-minute proof deadline.
Future-only speed queries return before allocating a window; bounded proofs check both query APIs
and unchanged stored observations.

The manually dispatched [Native formal verification workflow](../../.github/workflows/native-verification.yml)
checks speed-fusion guards, sample storage, network-speed windows and surviving
evidence, network reanchoring,
trust timestamp/frozen-position and receiver/jamming policies, turn correction
gates/bounds, network evidence allocation/recovery, motion/walking priorities,
timestamp/expiry decisions, OBD calibration gates
and bounds, correlated covariance families (PSD tolerance, variance floors and rejection rollback),
finite-state guards, bounded estimator rollback, and filter, trust, jamming and network
contracts; see
[proof scope, setup and counterexample reproduction](native_verification.md).
It is not automatically triggered by pull requests. Test results and a successful Kani report
are separate evidence; record which checks actually ran at the revision under review.

**Rust coverage.** Linux host coverage combines Rust tests, actual JVM JNI calls (default and
`heap-profile` builds), simulator subprocesses and server CLI/HTTP/WebSocket tests. Run
`python3 tools/rust_coverage.py` after installing the pinned coverage toolchain. CI gates
production lines per crate; see [coverage setup, thresholds and report semantics](rust_coverage.md).
Kani-only `kani_proofs.rs` files and `kani_proofs/` directories are excluded from production coverage.

**Kotlin coverage.** Run `python3 tools/kotlin_coverage.py` for host coverage of core, routing,
replay and app unit tests. CI enforces reviewed per-module baseline floors, with shared JNI
wrapper sources counted once. See [setup, reports and baseline updates](kotlin_coverage.md).

**Responsiveness.** Debug and benchmark builds enable StrictMode and a main-thread watchdog
(`MainThreadWatchdog`): any UI-thread task longer than 200 ms is logged with its stack under the
`MainThreadWatchdog` Logcat tag. Benchmark builds are R8-shrunk, so read their stacks with R8 retrace
and `app/build/outputs/mapping/playBenchmark/mapping.txt`. Text-to-speech, sensor registration, cell
scans, trip saving and history I/O run on background threads, and the map view stays loaded while
Settings or History is open, so returning to the map is instant.

The `play` and `fdroid` distribution flavors currently use the same FOSS application code and
dependencies. The flavor boundary prevents future Play-only SDKs from leaking into F-Droid, and an
automated dependency check rejects common proprietary/tracking SDK groups. F-Droid builds exclude
the optional untracked routing bundle and do not read the developer signing key. See
[`docs/fdroid.md`](fdroid.md) for release, validation and submission instructions.

Public donation destinations are configured in `links.properties` at the repository root:

```properties
monobankDonationUrl=
privatbankDonationUrl=
```

Set each value to its public donation-page URL. Only HTTPS URLs are accepted; an empty value hides
that bank's row in **Settings → Community links**. The Telegram group link in the English interface opens
<https://t.me/imu_nav_en>; Ukrainian and Russian open <https://t.me/imu_nav>.

The app uses MapLibre's OpenGL renderer for the broadest device compatibility. GPS, gyroscope,
compass and step-detector hardware are optional install-time features; missing sensors reduce
navigation accuracy or disable their corresponding corrections rather than blocking installation.

## Code checks

Contributor (and AI coding agent) guidelines — conventions, architecture rules, known pitfalls and
the definition of done — are in [`AGENTS.md`](../../AGENTS.md).

```bash
./gradlew check           # everything below plus all tests — run before sending changes
./gradlew spotlessApply   # auto-format Kotlin and Gradle files (ktlint)
```

| Tool                                                                      | Task                                              | Config                                                  |
| ------------------------------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------------- |
| **ktlint** (via Spotless) — formatting and style                          | `spotlessCheck` / `spotlessApply`                 | `.editorconfig` (IntelliJ style, 180 columns)           |
| **detekt** — complexity, exception handling, naming, bug patterns         | `detekt`                                          | `config/detekt.yml` (defaults + documented adjustments) |
| **Android Lint** — API levels, resources, translations, Compose, manifest | `:app:lintFdroidRelease` / `:app:lintPlayRelease` | `app/lint.xml`; warnings are errors                     |

Reports land in `*/build/reports/detekt/` and `app/build/reports/lint-results-<variant>.html`.
Exceptions are kept few and commented where they are configured; prefer fixing over suppressing.

Play release builds are signed from `keystore.properties` in the project root (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=blinddriver
keyPassword=...
```

Keep a backup of the keystore: Android only installs updates signed with the same key.
Without `keystore.properties`, `assemblePlayRelease` produces an unsigned APK. The
`assembleFdroidRelease` task is always unsigned so F-Droid can apply its repository signing key.

## Kotlin lifecycle and performance regression checks

Navigation start, reroute and restoration share cancellable request ownership: a stopped or superseded
request cannot replace a newer trip, and failed startup releases partially initialized components.
Location-provider status is cached for UI refresh and checked on the sensor worker before starting.

Phone and Android Auto share asynchronously loaded, serialized recent-search history; malformed rows
are skipped without discarding valid entries. Native network sample snapshots are reused between
mutations. Android Auto publishes changed display fields and defers map updates while its surface is
paused, applying current state on resume; stationary ETA is refreshed at least once per minute.

`./gradlew :core:test :app:testPlayDebugUnitTest :replay:test` covers queued logging boundaries,
startup rollback and request cancellation, history concurrency, provider caching, car publication
inputs, and real JNI sample parity. `RecentSearchCodecTest` uses Android instrumentation to verify the
platform JSON parser. Use `assemblePlayBenchmark` on a device with an Android Auto host to check
main-thread reports, visibility transitions, frame timing and JVM allocations. Native heap profiles
measure Rust allocations separately; they do not measure ART object allocation.

## Bookmark storage tests

Bookmark database instrumentation tests (requires an Android SDK/NDK and an emulator/device):

```bash
./gradlew :app:connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.bookmarks.BookmarkDatabaseTest
```

These tests use an isolated database and do not load routing packs. Use a benchmark APK for manual
bookmark-to-routing checks with a real pack, as with all GraphHopper device testing.
