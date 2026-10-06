# Build and run the simulator

[Simulator guide](README.md) · [Scenario list](scenarios.md) · [Glossary](glossary.md)

Run these commands from the repository root on Linux. The simulator is a desktop executable;
Android and JNI are not needed. A complete run writes separate measurement results and a copy of
the executable into a fresh capture directory.

Use Rust 1.99+ and a C/C++ build toolchain. The custom Cargo profile keeps optimized code and debug
symbols. Forced frame pointers preserve allocator backtraces through optimized Rust code. The
stripped Android release profile does not contain the symbols needed for these measurements.

Run messages go to standard error and are filtered by `RUST_LOG`; the default is `warn`.
For example, `RUST_LOG=imu_nav_sim=info,imu_nav_core=debug` shows run progress and native
state changes. Keep tracing at the default level for before/after performance captures.

```bash
RUSTFLAGS="-C force-frame-pointers=yes" cargo build --manifest-path native/Cargo.toml -p imu-nav-sim --profile profiling
native/target/profiling/imu-nav-sim run --out captures/native-baseline
```

The default is all twelve scenarios, 30 simulated minutes, 10,000 polyline points, seed 1, and five
timing repetitions after a discarded warm-up. Simulated time advances without sleeping.
The output directory must be new. Interrupted captures remain available but are not marked complete.

## Optional hotpath timing

The `app-like` group runs ordinary driving, jamming, delayed GPS, stop/resume and rerouting with
the normal simulated input rates. A separate `hotpath` pass instruments selected core functions and
writes one JSON timing report per scenario. It does not overlap the four comparison passes:

```bash
RUSTFLAGS="-C force-frame-pointers=yes" cargo build --manifest-path native/Cargo.toml -p imu-nav-sim --profile profiling --features profiling
HOTPATH_METRICS_SERVER_OFF=true native/target/profiling/imu-nav-sim run --scenario app-like --pass hotpath --out captures/hotpath-app
# Rebuild without --features profiling before the four-pass baseline/candidate comparison.
```

The reports are under `captures/hotpath-app/<scenario>/hotpath.json`. This feature uses hotpath
function timing only; the simulator's existing allocator owns heap and allocation measurements.
Normal core and Android builds do not include hotpath. `HOTPATH_METRICS_SERVER_OFF=true` disables
the optional local metrics listener; no report is uploaded.

## Targeted runs

```bash
# Smaller targeted run (duration is at least 120 s so jamming hysteresis can clear).
native/target/profiling/imu-nav-sim run --scenario delayed --duration-s 120 --route-points 1000 --out captures/delayed
# Curves, parallel returns, crossings, global reacquisition and dense sensor history.
native/target/profiling/imu-nav-sim run --scenario advanced --duration-s 600 --out captures/advanced
# Large route preset: at least 100,000 points, unchanged sensor rates.
native/target/profiling/imu-nav-sim run --stress --out captures/native-stress
# An individual pass for investigation; compare requires a complete four-pass run.
native/target/profiling/imu-nav-sim run --pass alloc --out captures/allocation-only
```
