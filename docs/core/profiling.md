# Profile JNI memory use

[Rust core guide](readme.md) · [Newcomer glossary](glossary.md)

This guide measures live Rust heap allocations in the JNI library during a recorded trip on Linux.
It helps find memory retained by route geometry, estimator history or native handles. It does not
measure JVM objects or Android memory use. For synthetic CPU, allocation and timing comparisons,
see the [native simulator](sim/readme.md).

## Native heap profiling (Linux host replay)

An optional `imu-nav-jni/heap-profile` Cargo feature installs
[`tikv-jemallocator`](https://crates.io/crates/tikv-jemallocator) 0.7 with profiling and uses
[`jemalloc_pprof`](https://github.com/polarsignals/rust-jemalloc-pprof) 0.9 to export gzipped pprof.
The allocator covers Rust allocations in the JNI library and linked `imu-nav-core`, including
route geometry, estimator history and handle registries. It does **not** measure JVM objects,
Android allocations, total RSS or the separate cell server. The algorithm crate remains dependency-free.

```bash
./gradlew -PnativeHeapProfile :replay:run --args="trips/ --compare-native --native-heap-profile heap-profiles --out replay-out"
go tool pprof -http=127.0.0.1:8080 heap-profiles/heap-1-*.pb.gz
```

Use a fresh output directory for each invocation. Sampling begins when the library loads, averaging
one sample per 512 KiB allocated. The replay writes a baseline, a snapshot at the first event and
then at most once per 60 seconds of recorded time, plus an `after-close` snapshot after every run.

Live snapshots reveal retained route/history allocations; after-close snapshots help identify
allocations that remain after releasing navigation handles. Small heaps may have few or no samples;
these are sampled **live bytes**, not cumulative allocation traffic or an exact peak-memory report.
Profiling slows replay and dump generation allocates memory of its own.

The feature is Linux-only (upstream pprof support); explicitly enabling it on Android or another OS
fails compilation. Normal host builds and all Android builds keep the system allocator and carry
no profiling dependencies. Allocator symbols stay prefixed so loading JNI does not replace the JVM's
allocator. Initial-exec TLS is disabled so jemalloc can be safely loaded as a JNI shared library.
No HTTP listener is started and no profile is uploaded.

The Gradle flag enables the Cargo feature and builds the unstripped debug host library. Keep that
exact `native/target/debug/libimu_nav_jni.so` for symbolization, with `addr2line` or `llvm-addr2line`
on PATH. The process needs a writable temporary directory (`TMPDIR` may override it). Do not set
`_RJEM_MALLOC_CONF=prof:false` or `prof_active:false`; capture reports an error if profiling is disabled.
Passing the CLI flag to a normal build also fails with a clear error before replay begins.

```bash
cargo test --manifest-path native/Cargo.toml --features heap-profile
cargo clippy --manifest-path native/Cargo.toml --all-targets --features heap-profile -- -W clippy::pedantic -D warnings
./gradlew -PnativeHeapProfile :replay:test
```
