# Route projection optimization — 2026-10-09

Global route searches chose a seed block with `min_by`, recalculating both the
incumbent and candidate bounds on each comparison. Mapping each block to its
bound before reduction computes the bound once per block. The iterator carries
the winning `(block, bound)` on the stack without collecting an intermediate
container. Projection arithmetic, `total_cmp`, first-equal ordering, rejection
corridors and public/JNI interfaces retain their existing behavior.

## Measurements

Baseline: `01bf65c3a603737a81d173a2e6f930fc1bb77e02`. Candidate: the accompanying
`nav-core/src/route.rs` change. Linux x86-64, AMD EPYC 9V74 virtual machine,
Rust 1.99.0 (`b940084d7`), release profile, one codegen unit, ThinLTO, stripped
symbols, default CPU target, no profiling feature or custom Rust flags. Both
variants used the platform default allocator and identical dependency versions.

Each sample measures 7,200 queries over a prebuilt route. Fixture creation,
route construction, warm-up, printing and teardown are excluded. Query output
is consumed through a checksum and `black_box`; checksum work is included.
For each route/scenario/variant, five fresh-process batches each contain one
warm-up and seven samples (35 measured samples). Process order alternates by
batch, and both variants are pinned to CPU 0. No builds run during measurement.
The fixtures follow `nav-sim`'s geometry scenarios with seed 1; reacquisition
measures the fallback projection without `nav-sim`'s extra local-only query.

Times below are milliseconds per 7,200-query sample, shown as median [min–max].
The last column is the reduction in the overall median, not an application-wide
speedup. [Raw samples](route-projection-2026-10-09.csv) include batch, variant,
CPU, workload, nanoseconds and the exact output checksum.

| Points | Scenario | Before, ms | After, ms | Median reduction |
| ---: | --- | ---: | ---: | ---: |
| 10,000 | Winding | 25.856 [23.265–29.102] | 22.622 [20.939–28.268] | 12.5% |
| 10,000 | Parallel | 18.345 [16.951–31.661] | 15.690 [14.486–19.253] | 14.5% |
| 10,000 | Crossing | 22.940 [20.427–29.392] | 19.878 [18.688–30.051] | 13.3% |
| 10,000 | Reacquisition | 47.019 [43.299–54.225] | 45.376 [41.587–54.751] | 3.5%, inconclusive |
| 100,000 | Winding | 211.378 [184.584–305.370] | 181.847 [166.281–240.955] | 14.0% |
| 100,000 | Parallel | 152.314 [140.724–201.302] | 135.164 [118.708–155.010] | 11.3% |
| 100,000 | Crossing | 178.379 [165.906–213.116] | 157.807 [147.721–218.738] | 11.5% |
| 100,000 | Reacquisition | 453.079 [410.227–507.312] | 420.437 [383.526–514.293] | 7.2%, inconclusive |

Winding, parallel and crossing improved in every paired batch median.
Reacquisition regressed in one paired batch at each route size, so its reduction
is not considered reliable. All 560 measured checksums agree between variants
for their respective inputs. This is supplementary evidence; differential unit
tests compare projections directly against exhaustive search.

Allocation calls/bytes, peak memory and individual-query tail latency were not
measured. The optimization introduces no buffer or cache. These measurements
cover the Rust geometry kernel and do not establish Android/AArch64, JNI,
estimator tick, battery or full-session performance.

## Reproduce

The dependency-free [example](../nav-core/examples/route_projection_bench.rs)
provides the measured geometry loop. Copy the same example to a separate
checkout of the baseline. Build each checkout with a distinct target directory;
preserve both binaries before timing. From each checkout's `native` directory:

```sh
cargo +1.99.0 build --locked --release -p imu-nav-core --example route_projection_bench
taskset -c 0 target/release/examples/route_projection_bench winding 100000 7200
```

Use an available CPU consistently. Repeat for `winding`, `parallel`, `crossing`
and `reacquisition`, with 10,000 and 100,000 points. Alternate baseline/candidate
order over five batches. Each invocation emits seven CSV rows after its warm-up:
`scenario,points,queries,elapsed_ns,checksum`. Compare checksums before comparing
medians. CLI validation and CSV formatting run outside the measured loop.

For broader application scenarios and allocation/CPU/heap profiling, use
`imu-nav-sim`. In this environment its baseline build failed to link against
`jemalloc` with empty archive members; rebuilding the dependency and then the
release target did not produce a usable simulator. The focused example enabled
isolated timing without changing the production allocator or dependencies.

## Verification

- Baseline: 157 core tests and 3 JNI tests passed in an isolated target directory.
- Candidate: 158 core tests and 3 JNI tests passed, including equal-bound blocks,
  repeated route occurrences, a partial final block and both indexed-search
  specializations. Existing exhaustive geometry and ambiguity checks passed.
- Core tests also passed with the `profiling` feature.
- `cargo fmt --check` passed.
- `cargo clippy --locked -p imu-nav-core -p imu-nav-jni --all-targets --features imu-nav-core/profiling -- -D warnings` passed.
- The release example ran successfully and matched the measured checksum;
  invalid scenario input returned an error.
- Before/after core assembly was emitted with `cargo rustc --locked -p imu-nav-core --release --lib -- --emit=asm,llvm-ir`.
  The old seed loop loads/recalculates the winning block's bound alongside each
  new block; the new loop carries its bound and computes only the new one. This
  supports the mechanism; runtime measurements establish the observed gains.

Full workspace simulator tests, Gradle checks, Kani proofs and device benchmarks
were not completed. The local repository snapshot contained the native workspace;
Gradle/Android build inputs were unavailable.

The Rust 1.99 [`Iterator::min_by` contract](https://doc.rust-lang.org/1.99.0/std/iter/trait.Iterator.html#method.min_by)
returns the first equally minimum element, preserving seed-block ties.
