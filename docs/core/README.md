# Rust navigation core

[Documentation index](../../README.md) · [Newcomer glossary](glossary.md)

The `native/` workspace contains the Rust code used by Android navigation and by repeatable host
experiments. A **crate** is one Rust package. This workspace has three crates with separate jobs:

| Crate | In plain English | Where it runs |
| --- | --- | --- |
| [`imu-nav-core`](../../native/nav-core/) | Calculates route progress, checks GPS trust and combines uncertain position and speed evidence. It has no Android dependency. | Android through JNI, host tests and simulation |
| [`imu-nav-jni`](../../native/nav-jni/) | Translates Kotlin calls into Rust calls, manages native objects and returns results Kotlin can read. It builds `libimu_nav_jni.so`. | Android and host JVM replay |
| [`imu-nav-sim`](../../native/nav-sim/) | Runs controlled, repeatable trips to compare changes in output, memory use and speed. | Desktop host |

On Android, Kotlin supplies sensor and location observations to `imu-nav-jni`, which calls
`imu-nav-core`. The simulator calls the same core without a phone. The native trust classifier,
jamming detector, route projector, network tracker and speed fusion take part in live navigation.
The native `NavigationEstimator` currently runs beside the Kotlin engine for comparison; it does
not choose the position shown to the user. IMU Nav remains a research prototype, not a safety system.

## Read by topic

1. [Route geometry, GPS trust and speed](positioning-and-trust.md) explains how observations become usable evidence.
2. [Route filter and navigation estimator](filter-and-estimator.md) explains the position and speed state.
3. [Cautious corrections](corrections.md) covers cell positions, the optional cell-speed experiment and turns.
4. [JNI bridge and Android integration](jni-and-android.md) explains the Kotlin boundary and native logs.
5. [Build, test and compare recorded trips](build-and-replay.md) has commands and regression examples.
6. [Profile JNI memory use](profiling.md) covers host replay heap snapshots.
7. [Native simulator](simulator.md) explains controlled workloads and links to full capture commands.
8. [Bounded verification](verification.md) explains the Kani proof suite and its limits.

Start with the [newcomer glossary](glossary.md) for terms such as *route coordinate*, *uncertainty*,
*innovation gate* and *JNI handle*. The [project glossary](../GLOSSARY.md) covers the whole app.
