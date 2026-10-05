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

The default is all twelve scenarios, 30 simulated minutes, 10,000 polyline points, seed 1, and five
timing repetitions after a discarded warm-up. Simulated time advances without sleeping.
The output directory must be new. Interrupted captures remain available but are not marked complete.

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

The simulation exercises native trust classification/jamming, network gating and regression, speed
fusion, route projection and estimation. In app scenarios, navigation ticks and ordinary OBD inputs use 500 ms intervals; GPS
arrives once per second and cell fixes every five seconds. These are explicit synthetic workloads,
not a reproduction of every Android power profile. Straight northbound routes start inside Ukraine;
the reroute adds a small lateral deviation. Observations are generated before measurement.

| Scenario        | Behavior exercised                                                                       |
| --------------- | ---------------------------------------------------------------------------------------- |
| `driving`       | Healthy GPS, cell inputs, OBD and ordinary history maintenance                           |
| `jam`           | Bad receiver conditions, GPS exclusion and recovery after AGC hysteresis                 |
| `delayed`       | GPS observed two seconds before delivery, with intervening OBD and cell inputs           |
| `stop`          | GPS outage, confirmed stop hints and resumed motion without OBD                          |
| `reroute`       | Install a changed route halfway through a trip and discard old route history             |
| `walking`       | Pedestrian hints through a GPS outage, without car OBD                                   |
| `lifecycle`     | Eight full start/drive/drop cycles with fresh state                                      |
| `winding`       | Global coarse-fix projection onto a winding 20 km route                                  |
| `parallel`      | 40 m separated out-and-back sections with precise and ambiguous coarse fixes             |
| `crossing`      | Figure-eight intersections, with accepted and rejected coarse projections                |
| `reacquisition` | Fixes far outside a small local search window force a global scan                        |
| `sensor-burst`  | 50 Hz OBD stress stream with two-second-delayed GPS, filling the 128-frame history limit |

`advanced` selects the five new cases; `all` includes every case. The four geometry cases call the
public route APIs directly with one query per configured second. They measure route construction,
projection and ambiguity handling, not full navigation or position accuracy. Their navigation-error
fields are zero by construction. The sensor-burst case uses full navigation with an intentionally
high OBD rate; it does not represent the normal Android sensor cadence.

Motion and walking inputs represent the _outputs_ of upstream detectors; this is not raw IMU
processing. Ground truth drives sensor synthesis only; the estimator receives observations through
its public APIs. Route geometry and session state are rebuilt for every repetition. Streaming output
fingerprints include covariance, drift, projection, trust decisions and acceptance flags; no growing
per-tick output log is kept in the profiled heap. Network history bounds and route ownership cleanup
are checked during execution. Existing core tests enforce estimator history bounds and rollback.

## What the measurements mean

`run` launches four separate child processes so instrumentation does not overlap:

- **Heap:** jemalloc samples live Rust allocations at a mean interval of 64 KiB. Snapshots cover
  route construction, each quarter of navigation (including delayed fixes and rerouting), and
  teardown. Profiles include resolved Rust symbols. Sampling pauses during capture/serialization so
  symbolization caches do not contaminate later snapshots. Fixtures are generated before sampling starts. Small profiles may be empty; individual
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
invalidates earlier comparisons. Reports now use schema 2 for geometry counters and expanded scenario
coverage. Capture fresh baselines with the same harness, or use the preserved schema-1 executable
for older comparisons. A performance claim needs a measured hotspot, matching outputs,
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

## Measured optimization example (2026-10-03)

The captured baseline identified two costs:

1. Heap stacks included `VecDeque<HistoryFrame>::grow` under estimator checkpoints. Exact counters
   showed 401 MB of allocation requests in the normal drive despite a small retained heap. Each
   transactional tick cloned a deque with no spare capacity, then appended checkpoints. The core
   now reserves room for the two possible new checkpoints while copying. Rollback still uses a
   separate candidate; no trust gates, history windows or numeric operations were removed.
2. CPU samples concentrated in `RouteGeometry::project_range` / `project_unambiguous`, including
   trigonometric work. Every rival segment used the same query latitude but recalculated its
   longitude scale. The scan now computes that scale once and reuses it, preserving candidate
   ordering, tie-breaking and ambiguity checks. Projection remains a linear scan.

Results below are from this Linux x86-64 host using Rust `1.100.0-nightly (f7575a9da 2026-09-24)`,
`--profile profiling`, `-C force-frame-pointers=yes`, seed 1, 1,800 seconds and 10,000 route points.
Timing is the median of five repetitions after warm-up. MB means decimal **requested allocation
bytes**, including reallocations; it is not resident or retained memory.

| Scenario                 | Requested MB before → after | Median ms before → after | Time reduction |
| ------------------------ | --------------------------: | -----------------------: | -------------: |
| Driving                  |             401.04 → 304.43 |         131.380 → 92.427 |          29.6% |
| Jamming/recovery         |             223.18 → 100.07 |         123.990 → 79.515 |          35.9% |
| Delayed GPS              |             434.55 → 346.26 |        348.419 → 225.588 |          35.3% |
| Stop/resume              |             223.17 → 100.07 |         122.307 → 78.864 |          35.5% |
| Reroute                  |             400.55 → 304.05 |        159.288 → 116.053 |          27.1% |
| Walking                  |             223.17 → 100.07 |          14.200 → 11.598 |          18.3% |
| Eight session lifecycles |           3208.29 → 2435.47 |       1036.134 → 704.398 |          32.0% |

Every deterministic outcome matched across all four passes. Before/after timing ranges did not
intersect in these runs. A separate 120-second, 100,000-point driving stress run also matched outputs:
median 157.385 → 133.523 ms (15.2% lower), requests 26.99 → 20.65 MB. These results support the two
changes on these workloads; they do not establish on-device gains or improved navigation accuracy.
Sampled live bytes varied in both directions and are not evidence of an RSS reduction.
Across eight optimized lifecycle sessions, the allocator-wide post-teardown gauge rose only 440 bytes
while retaining small phase-report records; route ownership checks passed after every teardown.
This supports bounded session storage on this workload, not a general leak-proof claim.

Local, gitignored evidence from this session:

- [Full comparison](../../captures/native-sim-comparison.md), including timing ranges and sampled heap totals.
- [Stress comparison](../../captures/native-sim-stress-comparison.md).
- `captures/native-sim-baseline-final/` and `captures/native-sim-optimized/`: complete profiles, metrics,
  matching executables and copies of core sources. Core fingerprints are `2e3f5f60c8a40377` and
  `90de6f2398a0a521`. Intermediate exploratory captures are not used for the table above.

Use the preserved executables to reproduce the workloads without changing the current checkout:

```bash
captures/native-sim-baseline-final/imu-nav-sim run --out captures/repeat-baseline
captures/native-sim-optimized/imu-nav-sim run --out captures/repeat-optimized
captures/native-sim-optimized/imu-nav-sim compare captures/repeat-baseline captures/repeat-optimized
```

Core tests additionally check exact projection equivalence across crossings, repeated vertices,
parallel returns and different latitudes, plus full estimator rollback after invalid delayed GPS.
The existing Kotlin blind-drive regression remains `p95=20 max=25 rms=10 m`.

## Additional scenarios and optimization (2026-10-03)

A second investigation added winding routes, parallel returns, figure-eight crossings, global
reacquisition and dense OBD history. CPU profiles put 93–97% of geometry-case samples under
`project_range_scaled`; `hypot` accounted for 50–62%. The dense sensor case requested 450 MB of
allocations over ten simulated minutes, although retained history is capped at 128 frames.

Two changes address those costs:

- Before computing a candidate's distance, reject it if either absolute coordinate component already
  exceeds the current best distance or ambiguity corridor. The bound includes a rounding margin;
  remaining candidates use the original distance calculation, order, tie-breaking and ambiguity
  comparisons. Global projection is still an exhaustive segment scan, with cheaper distant candidates.
- When attempting delayed GPS, reuse the owned history suffix for rejection rollback. The prefix
  stays in place; only same-time OBD checkpoints can be appended before acceptance. Those checkpoints
  cannot evict the prefix under either retention limit. The outer transactional copy remains, and
  errors still leave the original estimator untouched.

Measurements use the same host, compiler and build flags as above, with seed 1, 600 seconds,
10,000 route points and five timing repetitions. These improvements are **additional to** the earlier
optimization. Allocation MB below means requested bytes, including reallocations.

| Scenario        | Requested MB before → after | Median ms before → after | Time reduction |
| --------------- | --------------------------: | -----------------------: | -------------: |
| `driving`       |              101.22 → 82.38 |          36.332 → 30.502 |          16.0% |
| `jam`           |               33.28 → 25.41 |          31.385 → 27.041 |          13.8% |
| `delayed`       |              115.02 → 97.60 |          81.162 → 69.010 |          15.0% |
| `stop`          |               33.28 → 25.40 |          31.319 → 25.924 |          17.2% |
| `reroute`       |              100.84 → 82.07 |          50.534 → 35.992 |          28.8% |
| `walking`       |               33.28 → 25.40 |            9.191 → 7.840 |          14.7% |
| `lifecycle`     |             809.77 → 659.00 |        288.198 → 240.410 |          16.6% |
| `winding`       |                 0.08 → 0.08 |        187.042 → 115.527 |          38.2% |
| `parallel`      |                 0.08 → 0.08 |         126.936 → 81.826 |          35.5% |
| `crossing`      |                 0.08 → 0.08 |         162.233 → 80.267 |          50.5% |
| `reacquisition` |                 0.08 → 0.08 |          73.352 → 63.372 |          13.6% |
| `sensor-burst`  |             450.18 → 360.90 |         100.732 → 86.645 |          14.0% |

All deterministic outputs matched across every pass. Of 600 parallel queries, 299 remained ambiguous;
of 600 crossing queries, 201 remained ambiguous. All 600 reacquisition queries required global
fallback. The dense sensor case accepted 30,000 OBD samples and 596 delayed GPS updates on both sides.
Timing ranges did not overlap. A second run with seed 42, 123 seconds and 100,000 route points
also matched all outputs: median time fell 35.7% (winding), 30.7% (parallel), 46.8% (crossing),
11.5% (reacquisition) and 19.0% (sensor burst), again with non-overlapping timing ranges.
These are synthetic Linux host results, not Android latency or accuracy
claims. Heap samples vary and do not establish an RSS reduction.

The exhaustive reference test checks exact local/global projections and ambiguity decisions at
corridor boundaries, including duplicate vertices, crossings and polar latitudes. Estimator tests
compare complete history/state after rejected delayed GPS with same-time OBD and a full history deque.
The subprocess test also captures all twelve cases with a duration not divisible by four, checking
that each phase gets a unique snapshot.

Local evidence:

- [Twelve-scenario comparison](../../captures/native-round2-comparison.md).
- [100,000-point comparison, seed 42](../../captures/native-round2-stress-comparison.md).
- `captures/native-round2-baseline/` and `captures/native-round2-candidate/` contain profiles,
  matching executables and core source snapshots (`90de6f2398a0a521` → `65646b616576b875`).

```bash
captures/native-round2-baseline/imu-nav-sim run --duration-s 600 --out captures/round2-repeat-before
captures/native-round2-candidate/imu-nav-sim run --duration-s 600 --out captures/round2-repeat-after
native/target/profiling/imu-nav-sim compare captures/round2-repeat-before captures/round2-repeat-after
# Change both commands to the same options to explore another input distribution:
# --scenario advanced --seed 42 --duration-s 123 --stress
```

## App-like hotpath pass and route-scan optimization (2026-10-04)

With seed 1, 1,800 simulated seconds, 10,000 route points and five timing repetitions, hotpath
attributed 74–88% of app-like scenario time to `RouteGeometry::project_unambiguous`. Its rival
search projected every segment after finding the best point, including segments wholly outside the
ambiguity corridor. The search now skips a segment only when both endpoints are safely beyond the
same latitude bound. Candidates near the corridor still use the original projection and comparison.

Uninstrumented before/after captures use the same Rust nightly, profiling build flags and host.
All five deterministic outcomes matched; allocation calls and requested bytes were identical.

| Scenario         | Median before → after | Time reduction |
| ---------------- | --------------------: | -------------: |
| Driving          |    71.285 → 44.230 ms |          38.0% |
| Jamming/recovery |    64.869 → 37.381 ms |          42.4% |
| Delayed GPS      |   177.638 → 98.312 ms |          44.7% |
| Stop/resume      |    62.554 → 35.543 ms |          43.2% |
| Reroute          |    80.572 → 51.857 ms |          35.6% |

The five-repetition timing ranges did not overlap. These are synthetic host results, not Android
latency or navigation-accuracy claims. The existing exhaustive projection test covers loops,
crossings, repeated vertices, extreme latitudes and corridor boundaries. Local gitignored evidence:
`captures/hotpath-app-baseline/`, `captures/hotpath-app-uninstrumented-before/`,
`captures/hotpath-app-uninstrumented-after/` and `captures/hotpath-app-comparison.md`.

Opportunities identified at this stage (the segment index below now addresses the first):

- **Spatial route indexing:** projection still takes roughly 88–96% of CPU samples in the geometry
  cases. A conservative segment index could avoid scanning the whole route. It must preserve distant
  rival detection, earliest-segment tie-breaking and the exact projection results; nearest-only
  indexing would be insufficient for the ambiguity check.
- **Reusable transaction storage:** dense input still requests 361 MB per ten simulated minutes.
  Reusing capacity for the outer history copy or replay scratch storage may reduce churn further, but
  must preserve error rollback and delayed-input ordering. The remaining copy is not a retained-memory
  leak; live-heap sampling alone cannot quantify its short-lived cost.

## Earlier rejection in the coarse-fix scan (2026-10-04)

After the prior change, hotpath still attributed 59–80% of app-like scenario time to
`project_unambiguous`. Its best-point scan now checks whether both endpoints of a segment are
safely beyond the same latitude or longitude bound before doing the full projection. The check
starts only after finding a nearby candidate (within 300 m). It is compiled only into the
coarse-fix scan; ordinary local/global projection keeps its original loop. This distinction matters:
applying the bound to all projections slowed the far-off-route reacquisition workload.

The baseline was built from the previous commit in a separate worktree. Both sides used the same
Linux host, Rust nightly, `--profile profiling`, frame-pointer flag, simulator source and inputs.
Five timing repetitions followed a warm-up. Exact outcome fingerprints and allocation counts/bytes
matched in all ten app-like and advanced cases.

| App-like scenario | Median before → after | Time reduction |
| ----------------- | --------------------: | -------------: |
| Driving           |    44.263 → 34.069 ms |          23.0% |
| Jamming/recovery  |    37.245 → 27.412 ms |          26.4% |
| Delayed GPS       |    99.303 → 67.574 ms |          32.0% |
| Stop/resume       |    36.261 → 25.466 ms |          29.8% |
| Reroute           |    52.553 → 42.459 ms |          19.2% |

All app-like timing ranges were separate. Advanced medians fell 20–35% for winding, parallel and
crossing geometry, and 19.9% for the sensor burst. Reacquisition was 2.3% slower, with overlapping
timing ranges. A regression test checks long segments that cross the query despite both endpoints
being far away. These are synthetic host results, not an Android latency or accuracy claim.
Local gitignored captures and full comparisons are in `captures/route-bounds-app-{before,final}/`,
`captures/route-bounds-advanced-{before-current,final}/` and their `*-final-comparison.md` files.

## Indexed coarse-fix projection (2026-10-04)

The next uninstrumented baseline still spent most app-like time in global coarse-fix projection.
`RouteGeometry` now builds 32-segment geographic envelopes with the route, off the main thread.
A query seeds its nearest search from the closest envelope, then visits envelopes in original route
order. It skips an envelope only when its latitude or longitude bound proves every enclosed segment
too far away. The rival search uses the same conservative bound before examining individual
segments. In this step, projection arithmetic, earliest-segment ties, distant-rival detection and
local/global reacquisition remained unchanged. A long-route test compares exact projections and ambiguity decisions
against the exhaustive algorithm across winding and parallel routes.

Both sides used the same Linux host, Rust nightly, frame-pointer flag, simulator inputs and five
timing repetitions. All ten deterministic outcomes matched. At 10,000 points, the index adds one
allocation and about 15 KB of requested route storage per construction (two on reroute).

| Scenario           | Median before → after | Time reduction |
| ------------------ | --------------------: | -------------: |
| Driving            |    33.905 → 17.841 ms |          47.4% |
| Jamming/recovery   |    26.755 → 11.611 ms |          56.6% |
| Delayed GPS        |    66.669 → 22.550 ms |          66.2% |
| Stop/resume        |    25.123 → 11.496 ms |          54.2% |
| Reroute            |    42.990 → 21.692 ms |          49.5% |
| Winding geometry   |     59.809 → 6.055 ms |          89.9% |
| Parallel geometry  |     31.905 → 4.882 ms |          84.7% |
| Crossing geometry  |     30.036 → 5.784 ms |          80.7% |
| Reacquisition      |    66.207 → 63.619 ms |           3.9% |
| Dense sensor burst |    47.494 → 33.263 ms |          30.0% |

With the 100,000-point stress preset, a separate 600-second driving timing pass had the same
output fingerprint and a 150.171 → 96.497 ms median (35.7% reduction). Its five-run ranges did
not overlap. The stress evidence is in `captures/block-index-stress-before/` and
`captures/block-index-stress-after-reviewed/`.

Timing ranges did not overlap. These are synthetic host results, not Android latency or navigation
accuracy claims. Local gitignored evidence is in `captures/block-index-{app,advanced}-before/`,
`captures/block-index-{app,advanced}-after-reviewed/` and the matching
`captures/block-index-*-reviewed-comparison.md` reports.

## Indexed global reacquisition (2026-10-04)

After coarse-fix indexing, the hotpath pass attributed 93.5% of the off-route reacquisition case
to `RouteGeometry::project`. Its global fallback still scanned every segment. That fallback now
uses the same block index, with an exact segment projection for surviving blocks in route order.
The local search and its comparison with the global result are unchanged. Finite queries outside
geographic latitude/longitude bounds keep the prior exhaustive path because the indexed longitude
bound assumes a valid latitude. A test compares global fallback against an exhaustive projection
across a long winding route, including those outside-world inputs.

Uninstrumented before/after captures used the same Linux host, Rust nightly, simulator inputs and
five timing repetitions. All ten deterministic outputs and allocation counts/bytes matched.
The focused reacquisition median fell from 63.619 to 7.688 ms (87.9%), with non-overlapping ranges;
other advanced cases changed by at most 3.2%. App-like medians changed by -2.8% to +1.2%.
At 100,000 points, a separate 600-second timing pass kept the exact
output fingerprint and fell from 631.110 to 79.787 ms (87.4%). These are synthetic host results,
not Android latency or navigation accuracy claims. Local gitignored evidence is in
`captures/round3-global-{app,advanced}-after/`, the matching `*-comparison.md` files and
`captures/round3-global-stress-{before,after}/`.
