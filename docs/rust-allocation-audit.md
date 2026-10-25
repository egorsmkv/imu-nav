# Rust copy and allocation audit

The separate [desktop audit](desktop-rust-allocation-audit.md) covers `desktop/app` and
`desktop/protocol`, including network snapshots, NMEA parsing, rendering and raster tiles.

Scope: repository-owned Rust in `native/` and `server/`, reviewed on 2026-10-25 with
Rust/Clippy 1.99.0 on Windows x86-64. This is a source audit plus regression tests and
Clippy checks, not an allocator trace or a throughput benchmark. Dependency internals,
Linux-only simulator/profiler bodies, Android JNI execution and PostgreSQL execution need
their own platform runs. IMU Nav remains a research prototype, not a safety system.
The review focuses on repeated navigation, routing/search and CSV/HTTP ownership boundaries;
it is not an exhaustive count of every allocation in the codebase.

Zero-copy means borrowing or transferring an existing buffer without duplicating its payload.
Zero-allocation means a particular operation makes no heap allocation requests. A small stack
copy is compatible with zero-allocation; a memory map is not a promise of allocation-free I/O.

## Removed unnecessary work

| Location | Change | Remaining ownership/copy boundary |
| --- | --- | --- |
| `server/src/csv_format.rs`, `decode_towers` | `Cow<[u8]>` borrows plain CSV instead of copying the entire upload. `read_record` reuses one `StringRecord` instead of creating one per row. | CSV still buffers/parses fields, grows the reusable record when necessary and allocates the returned tower vector. Gzip still decompresses to owned storage. |
| `server/src/model.rs`, `Radio::from_str` | ASCII case-insensitive comparisons avoid allocating an uppercase string for each valid radio field. | Invalid input is owned by the existing parse error. |
| `native/nav-jni/src/lib.rs`, `tick_values` | A fixed nine-double stack array replaces a temporary `Vec` and its growth when appending flags. | Seven doubles are copied on the stack; JNI still allocates a Java array and copies the result into it. The whole estimator tick is not allocation-free. |
| `native/nav-jni/src/network.rs`, `nativeSamples` | Flatten the borrowed tracker slice directly, eliminating the intermediate `Vec<NetworkSample>`. | One Rust `Vec<f64>` plus the Java array remain. Flattening runs under the existing registry mutex; Java allocation stays outside it. |
| `native/nav-routing/src/builder/profile.rs` | Static general/forward/backward keys replace `format!` strings for every access and direction lookup. Both modes reuse the same priority tables. | The tag map is allocated by the importer. These lookups borrow its strings. |
| `native/nav-routing/src/builder/write.rs`, `stage` | `HashMap<&str, u32>` borrows names from immutable roads; `Entry` avoids duplicate lookups on insertion. | The dictionary table and output name bytes still allocate. References cannot outlive the borrowed roads. |
| `native/nav-routing/src/builder/search.rs` | Alias joining borrows strings instead of cloning every alias first. Passing the place-name JSON by reference avoids an intermediate owned value. | The alias pointer vector, joined string and final JSON still allocate. |
| `server/` tests | Removed redundant store/policy clones found by the additional Clippy checks and adjacent equivalent struct updates. | Store clones used to transfer ownership to workers remain valid. |

No pack format, imported access policy, JNI wire layout, trust threshold or estimator equation
changes are intended. Tests cover access priority and directional speed limits, JNI acceptance
flag layout, mixed-case radios, plain/gzip CSV parity, variable row lengths, malformed rows,
valid-row limits, invalid UTF-8 and corrupt gzip checksums.

## Existing borrowing and remaining allocation sites

| Path | Finding | Follow-up / constraint |
| --- | --- | --- |
| `native/graphics-path/src/{iterator,conic}.rs` | Path data is borrowed as slices; conic conversion uses fixed arrays. The arithmetic/iteration bodies do not explicitly allocate heap storage. | Preserve the C++ bit-for-bit fixtures and the lifetime of the Skia-owned data. This observation does not cover JNI object creation or tracing. |
| `native/nav-core/src/{lib,route}.rs` | Filter arithmetic and projection query bodies use scalar/stack values and borrow prebuilt geometry. Route construction allocates points, cumulative distances, turns and the index. | Separate setup from repeated queries when measuring. A borrowed query is not evidence that constructing its route is allocation-free. |
| `native/nav-core/src/trust.rs`, `evaluate` | Empty reason vectors use `Vec::new()` without allocating. SUSPECT/BAD verdicts push owned reasons and may allocate or grow storage. | Do not count an empty vector as an allocation or assume all classification paths allocate equally. Fixed-capacity reasons would need a reviewed bound and the existing reason order. |
| `native/nav-core/src/estimator.rs`, `copy_for_tick` | Each transactional tick allocates a new history deque and clones checkpoints. Delayed-fix replay can allocate additional temporary history. `Arc::clone` shares route storage rather than copying geometry. | High-priority profiling target. Consider reusable pending-history storage only with rollback, delayed-observation and timestamp tests; removing the transaction would change error semantics. |
| `native/nav-core/src/speed.rs`, `estimate_window` | Each attempted window collects samples into a new vector; inlier selection collects another vector. Fallback windows can repeat this work. `SpeedBatch::fit` also constructs a fresh estimator. | High-priority candidate for bounded scratch storage or iterator-based fitting. Preserve historical read-only queries, overflow checks, fit iteration order and inlier count/span gates, including Kani contracts. |
| `native/nav-core/src/{network,speed}.rs` | Bounded recent/sample vectors remove their first element, moving the remaining scalar records; pushes can grow capacity during warm-up. | A ring buffer can avoid shifts, but needs measured benefit at these small bounds (4/60 samples) and must preserve ordering and query semantics. |
| `native/nav-routing/src/{file_data,storage,shared}.rs` | Graph records are decoded directly from mmap slices, not deserialized into a country-sized object graph. Fixed-width numeric decoding still copies small byte arrays. Full validation allocates indegree/queue storage. | On 32-bit Android the map cache allocates on misses and `with_slice` uses an owned buffer for records crossing a window. Do not return a borrowed `&str` past a window-cache lock or mapping eviction. |
| `native/nav-routing/src/{shared,route}.rs` | `Shared::name` returns owned strings; maneuver name comparisons and output construction can allocate repeatedly. | Prefer name IDs for comparisons only after checking whether distinct IDs may contain equal text. Final JSON still needs owned/serialized output. |
| `native/nav-routing/src/{query,matching}.rs` | Sparse heaps, maps, candidate lists and route reconstruction allocate per request; matching creates per-observation costs and parent arrays. | Sparse storage avoids region-sized scratch but is not zero-allocation. Profile reuse before adding pools with cancellation/concurrency complexity. |
| `native/nav-search/src/lib.rs` | Tantivy reads index-backed data, while query terms, candidate heaps, de-duplication tables and owned JSON results allocate. | Returned rows outlive temporary document reads; removing ownership requires a deliberate API/lifetime design. |
| `server/src/csv_format.rs` | Gzip imports retain the decoded body (bounded at 128 MiB plus the existing sentinel byte) before parsing. CSV export formats numbers into strings. | Streaming decompression could reduce peak memory, but must still reject late gzip errors and preserve decoded-size/valid-row limits. Reusable serialization buffers require profiling and exact output tests. |
| `server/src/api.rs`, `ChannelWriter` | Bounded download chunks transfer their `Vec<u8>` into `Bytes` without a payload clone; each replacement chunk allocates. `write` copies serialized bytes into the chunk. | The two-worker/four-chunk backpressure limits matter more than labeling the whole response zero-copy. Never reuse a buffer while the async consumer owns it. |
| `server/src/{websocket,account_sync,trips}.rs` | JSON serialization/deserialization creates owned documents and strings. Store/`Arc` clones support blocking-worker or async ownership. | Borrowing across `spawn_blocking`/async tasks is constrained by lifetimes. Avoid holding locks across network I/O merely to eliminate a clone. |

## Repeat the checks

Run from the repository root. The additional Clippy lints also run through Gradle `check`.
They catch selected patterns; a clean run does not prove the absence of allocations.

```bash
cargo fmt --manifest-path native/Cargo.toml --all --check
cargo fmt --manifest-path server/Cargo.toml --all --check
cargo clippy --manifest-path native/Cargo.toml --workspace --all-targets --locked --features imu-nav-routing/builder -- -W clippy::pedantic -W clippy::redundant_clone -W clippy::needless_collect -W clippy::unnecessary_to_owned -D warnings
cargo clippy --manifest-path server/Cargo.toml --all-targets --all-features --locked -- -W clippy::pedantic -W clippy::redundant_clone -W clippy::needless_collect -W clippy::unnecessary_to_owned -D warnings
cargo test --manifest-path native/Cargo.toml --workspace --all-targets --locked --features imu-nav-routing/builder
cargo test --manifest-path server/Cargo.toml --all-targets --all-features --locked
```

On Windows, native Clippy was additionally run with
`--features imu-nav-routing/builder,imu-nav-core/profiling,imu-nav-sim/profiling`.
`nav-sim` deliberately compiles only a stub on this platform. Native `--all-features` enables
the Linux-only JNI `heap-profile` and intentionally fails on Windows; do not remove its guard.

For actual allocation counts on Linux, use the existing
[simulator allocation pass](core/sim/run.md#targeted-runs). Counts distinguish allocation
requests from retained heap; [JNI heap profiling](core/profiling.md) and
[server profiling](server/profiling.md) cover different processes and measurement boundaries.
Keep tracing, compiler, workload and warm-up identical between baseline and candidate.
This audit reports no measured speedup or global zero-allocation guarantee.

## Verification of this change

- Native formatting and Clippy passed for all workspace targets, including routing builder and
  the Windows-compatible profiling feature selection above. Server formatting and Clippy passed
  with all targets/features. All additional clone/collection/ownership lints passed with
  `-D warnings`.
- Native host tests passed (207 tests across the core, JNI, routing, search and Graphics Path
  suites). Server host tests passed with profiling enabled; PostgreSQL tests returned early
  because `TEST_POSTGRES_URL` was absent. Unix-only CLI tests and Linux simulator tests are not
  exercised on Windows.
- `gradlew.bat spotlessApply check :app:assemblePlayBenchmark` passed. This includes Android
  Lint, detekt, Kotlin/JNI replay tests and the R8 benchmark APK. Both network snapshot JNI tests
  passed; the blind-drive replay result remains `p95=20 max=25 rms=10 m`.
- The rebuilt APK installed and cold-launched on the API 30 x86-64 emulator. The process remained
  alive with no fatal/native-library load errors. Two startup watchdog samples (208 ms and
  203 ms) showed Android resource lookup and MapLibre renderer initialization, respectively;
  the changed Rust functions did not appear in those stacks. There was no baseline timing
  comparison, route-driving exercise or on-device allocation trace. The emulator started for
  this smoke check was shut down afterward.
