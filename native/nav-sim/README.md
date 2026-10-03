# Native simulation laboratory

`imu-nav-sim` is a Linux-only host executable for profiling `imu-nav-core` without Android, JNI,
recordings, an emulator or network services. It generates deterministic synthetic inputs, checks
that each scenario exercises its intended paths, and captures evidence for behavior-preserving
optimizations. IMU Nav remains a research prototype, not a safety system.

## Build and capture

Use Rust 1.99+ and a C/C++ build toolchain. The custom Cargo profile keeps optimized code and debug
symbols; forced frame pointers preserve allocator backtraces through optimized Rust code. Do not use the stripped Android release profile for these measurements.

```bash
RUSTFLAGS="-C force-frame-pointers=yes" cargo build --manifest-path native/Cargo.toml -p imu-nav-sim --profile profiling
native/target/profiling/imu-nav-sim run --out captures/native-baseline
```

The default is all seven scenarios, 30 simulated minutes, 10,000 polyline points, seed 1, and five
timing repetitions after a discarded warm-up. Simulated time advances without sleeping.
The output directory must be new. Interrupted captures remain available but are not marked complete.

```bash
# Smaller targeted run (duration is at least 120 s so jamming hysteresis can clear).
native/target/profiling/imu-nav-sim run --scenario delayed --duration-s 120 --route-points 1000 --out captures/delayed
# Large route preset: at least 100,000 points, unchanged sensor rates.
native/target/profiling/imu-nav-sim run --stress --out captures/native-stress
# An individual pass for investigation; compare requires a complete four-pass run.
native/target/profiling/imu-nav-sim run --pass alloc --out captures/allocation-only
```

The simulation exercises native trust classification/jamming, network gating and regression, speed
fusion, route projection and estimation. Navigation ticks and OBD inputs use 500 ms intervals; GPS
arrives once per second and cell fixes every five seconds. These are explicit synthetic workloads,
not a reproduction of every Android power profile. Straight northbound routes start inside Ukraine;
the reroute adds a small lateral deviation. Observations are generated before measurement.

| Scenario | Behavior exercised |
|---|---|
| `driving` | Healthy GPS, cell inputs, OBD and ordinary history maintenance |
| `jam` | Bad receiver conditions, GPS exclusion and recovery after AGC hysteresis |
| `delayed` | GPS observed two seconds before delivery, with intervening OBD and cell inputs |
| `stop` | GPS outage, confirmed stop hints and resumed motion without OBD |
| `reroute` | Install a changed route halfway through a trip and discard old route history |
| `walking` | Pedestrian hints through a GPS outage, without car OBD |
| `lifecycle` | Eight full start/drive/drop cycles with fresh state |

Motion and walking inputs represent the *outputs* of upstream detectors; this is not raw IMU
processing. Ground truth drives sensor synthesis only; the estimator receives observations through
its public APIs. Route geometry and session state are rebuilt for every repetition. Streaming output
fingerprints include covariance, drift, projection, trust decisions and acceptance flags; no growing
per-tick output log is kept in the profiled heap. Network history bounds and route ownership cleanup
are checked during execution. Existing core tests enforce estimator history bounds and rollback.

## What the measurements mean

`run` launches four separate child processes so instrumentation does not overlap:

- **Heap:** jemalloc samples live Rust allocations at a mean interval of 64 KiB. Snapshots cover
  route construction, each quarter of navigation (including delayed fixes and rerouting), and
  teardown. Fixtures are generated before sampling starts. Small profiles may be empty; individual
  before/after sample totals are stochastic and are not strict regression thresholds.
- **Alloc:** a thread-local counting allocator records successful allocation/reallocation calls and
  requested bytes inside workload regions. A reallocation contributes its entire new size, not just
  growth. Fixture generation, simulated JNI buffer copying, snapshot output and report serialization
  are excluded. Counters expose short-lived allocation churn that live-heap profiles cannot reveal.
- **CPU:** `pprof-rs` samples at 99 Hz for at least one second per scenario by repeating identical
  drives. `iterations.txt` records how many drives ran. Profiles include session setup, teardown and
  surrounding harness work, but exclude fixture generation and profile serialization. Compare
  normalized sample percentages, not raw sample counts from different iteration totals.
- **Timing:** five repetitions after warm-up, without heap/CPU sampling or allocation counting.
  Phase times include workload dispatch and streaming diagnostics, exclude profile serialization,
  and report median plus min/max. These are host workload timings, not on-device frame latency.

The wrapper allocator still performs a disabled counter check outside the allocation pass. All
passes use the same jemalloc allocator. Allocator-wide byte gauges in `report.json` include fixtures,
report structures, profiler state and allocator accounting/cache effects; they are not process RSS,
precise peak memory or per-object leak proofs. Do not compare CPU and heap pass timing numbers.
Unset `_RJEM_MALLOC_CONF` overrides: heap sampling must start inactive. Keep the temp directory writable.

## Inspect and compare

A complete run preserves its optimized executable, machine-readable metrics, core/harness source
fingerprints, compiler/host identity, gzipped heap profiles, CPU profiles and CPU SVG flamegraphs.
The `captures/` directory is gitignored. Each snapshot is local; nothing is uploaded or served.

```bash
go tool pprof -top captures/native-baseline/imu-nav-sim captures/native-baseline/heap/driving/session-0-steady.pb.gz
go tool pprof -top captures/native-baseline/cpu/driving/cpu.pb
# Optional interactive viewer bound to localhost:
go tool pprof -http=127.0.0.1:8080 captures/native-baseline/cpu/driving/cpu.pb

# After a core change, rebuild and rerun identical options into a fresh directory:
RUSTFLAGS="-C force-frame-pointers=yes" cargo build --manifest-path native/Cargo.toml -p imu-nav-sim --profile profiling
native/target/profiling/imu-nav-sim run --out captures/native-candidate
native/target/profiling/imu-nav-sim compare captures/native-baseline captures/native-candidate --out captures/comparison.md
```

Comparison rejects different input configurations, compilers, hosts, optimization levels or harness
source fingerprints, and any changed deterministic outcome. Profile output is decoded to compare
sampled live bytes separately from allocation churn. CPU percentages are inspected in pprof or the
SVG files. Preserve the baseline executable: rebuilding overwrites the Cargo output, and heap
symbolization needs the matching binary plus `addr2line` or `llvm-addr2line` on PATH.

Reproduce either workload with its preserved executable and the same CLI options. To add harness
instrumentation, capture both sides again with that instrumentation; changing the workload itself
invalidates earlier comparisons. A performance claim needs a measured hotspot, matching outputs,
and repeatable timing/allocation evidence. Reduced churn need not reduce retained heap or RSS.

## Checks

```bash
cargo test --manifest-path native/Cargo.toml -p imu-nav-sim
cargo clippy --manifest-path native/Cargo.toml --all-targets -- -W clippy::pedantic -D warnings
cargo fmt --manifest-path native/Cargo.toml --all --check
```

Tests cover deterministic scenarios, branch coverage, input ordering, counting boundaries, invalid
CLI settings, overwrite refusal, subprocess capture, heap/CPU protobuf decoding and comparison.
The simulator is a workspace member, so existing Rust Gradle checks include it. Linux integration
tests use the in-process CPU sampler and do not require perf privileges. Android builds still select
only `imu-nav-jni`; neither this executable nor its profiling dependencies enter the APK.
