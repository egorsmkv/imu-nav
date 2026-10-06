# Inspect and compare captures

[Simulator guide](readme.md) · [Measurements](measurements.md) · [Glossary](glossary.md)

Use two complete captures with matching settings to check a core change. The simulator checks that
the workload and deterministic outputs match before reporting differences in measurements.

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
