# JNI bridge and Android integration

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

Android code cannot call a Rust function directly. The JNI crate is a small translator: it checks
Kotlin inputs, calls the platform-independent core, and returns simple results that Kotlin can read.
It also owns the lifetime of Rust objects created for a trip.

## `imu-nav-jni`

`imu-nav-jni` is the Android boundary. It does not contain navigation policy of its own; it validates
and converts JNI inputs, calls `imu-nav-core`, and converts results back to primitive Java arrays and
status codes.

The exported functions are grouped by their Kotlin owners in
`app/src/main/kotlin/org/imunav/app/nativecore/`:

| Kotlin wrapper              | Native state or operation                                                            |
| --------------------------- | ------------------------------------------------------------------------------------ |
| `NativeTrustEvaluator`      | `TrustClassifier` and `JamDetector` lifecycle, AGC updates and fix verdicts          |
| `NativeRouteGeometry`       | Route creation, destruction and point projection                                     |
| `NativeRouteFilter`         | Low-level filter prediction, measurement updates, route installation and state reads |
| `NativeNavigationEstimator` | Trip estimator lifecycle, ticks, OBD speed and route replacement                     |
| `NativeNetworkTracker`      | Network/cell gating, sample history and speed regression                             |
| `NativeSpeedFusion`         | Stateless inverse-variance speed fusion                                              |

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

## Logs

The native core emits structured tracing events but does not install a subscriber. The Android
JNI library sends warnings and errors to Logcat under `ImuNavRust` in release builds. Debug and
benchmark builds also show native debug events. To inspect them, run
`adb logcat -s ImuNavRust:V`. Messages omit GPS coordinates, route positions and credentials.
The host JNI library logs to standard error when loaded by a JVM.

The simulation program logs to standard error and keeps its normal report output on standard
output. It defaults to warnings and errors. Set `RUST_LOG=imu_nav_sim=info,imu_nav_core=debug`
to see run progress and core state events. Avoid `trace` when comparing performance runs.
