# Simulator glossary

[Simulator guide](README.md) · [Rust core glossary](../glossary.md)

- **Cargo workspace** — Related Rust packages built together. `native/` contains the core, JNI
  bridge and simulator packages.
- **Package** — A directory with a `Cargo.toml` manifest. `native/nav-sim` is the simulator package.
- **Crate** — One Rust compilation unit. This package builds the `imu-nav-sim` binary crate, which
  calls the `imu-nav-core` library crate. A binary crate makes an executable; a library crate makes
  code other crates can use.
- **Host** — The Linux computer running the simulator. Results describe that computer and workload,
  not Android hardware.
- **Harness** — The simulator code that generates inputs, calls core APIs and records results.
- **Scenario / workload** — A defined set of generated route and sensor-like inputs. Each scenario
  exercises particular core behavior. [Scenario list](scenarios.md).
- **Synthetic** — Generated for a controlled test rather than captured from a real trip.
- **Deterministic / seed** — The same settings and seed generate the same inputs and expected
  output fingerprints. A seed is the number used to start this generation.
- **Baseline / candidate** — Two complete captures compared before and after a core change. Their
  inputs, harness and environment must match. [Comparison guide](compare.md).
- **Pass** — One kind of measurement run. Heap, allocation counting, CPU sampling and timing run
  in separate processes so their instruments do not overlap. [Measurement guide](measurements.md).
- **Live heap** — Rust allocations still held at a snapshot. Sampling estimates this quantity; it
  does not count every allocation or measure all process memory.
- **jemalloc / pprof** — Tools used to capture sampled live allocations and call-stack profiles.
  `go tool pprof` can inspect saved profiles. [Comparison guide](compare.md).
- **Allocation churn / requested bytes** — Allocation and reallocation traffic during a workload.
  Bytes requested can greatly exceed the memory still held afterward.
- **RSS** — Resident set size, or physical memory held by a process. The simulator's allocator
  gauges and sampled heap are not RSS.
- **CPU sample / flamegraph** — A periodic observation of the running call stack; a flamegraph
  groups stacks to show where samples were spent. Samples are not exact per-function timings.
- **Hotpath pass** — Optional timing for selected core functions. It is separate from the four
  standard comparison passes. [Run guide](run.md#optional-hotpath-timing).
- **Frame pointer** — Call-stack information retained in the compiled program so profiler and
  allocator traces can identify functions in optimized Rust code.
- **Warm-up / median** — The timing pass discards an initial run, then reports the middle value
  from its repetitions, along with the minimum and maximum.
- **Fingerprint** — A compact record of scenario outputs used to check that two runs behaved the
  same without retaining a growing log of every simulated tick.
- **AGC hysteresis** — A receiver-signal rule that requires conditions to improve long enough before
  GPS is trusted again after suspected jamming. [GPS trust guide](../positioning-and-trust.md).
