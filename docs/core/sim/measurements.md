# What the measurements mean

[Simulator guide](readme.md) · [Inspect and compare](compare.md) · [Glossary](glossary.md)

A capture uses different tools for memory, CPU activity and elapsed time. Read each result on its
own terms: sampled live memory, total allocation traffic and host runtime answer different questions.

`run` launches four separate child processes so instrumentation does not overlap:

- **Heap:** jemalloc samples live Rust allocations at a mean interval of 64 KiB. Snapshots cover
  route construction, each quarter of navigation (including delayed fixes and rerouting), and
  teardown. Profiles include resolved Rust symbols. Sampling pauses during capture and serialization
  so symbolization caches do not contaminate later snapshots. Fixtures are generated before sampling
  starts. Small profiles may be empty; individual before/after sample totals vary and are not strict
  regression thresholds.
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
Unset `_RJEM_MALLOC_CONF` overrides: heap sampling must start inactive. Keep the temp directory
writable.
