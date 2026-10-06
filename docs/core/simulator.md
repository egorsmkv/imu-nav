# Native simulator

[Rust core guide](README.md) · [Newcomer glossary](glossary.md)

`imu-nav-sim` is a Linux desktop program that sends made-up but repeatable trips through
`imu-nav-core`. It exercises GPS outages, stops, reroutes and tricky route shapes without a phone,
JNI or a recorded drive. Run the same inputs before and after a code change to check that the
answers stay the same while measuring memory or CPU use. Synthetic results show behavior under
those inputs; they do not establish road accuracy or Android performance.

## Synthetic native workload profiling

[`imu-nav-sim`](../../native/nav-sim/README.md) exercises the native app lifecycle without Android or trip recordings,
with separate heap, allocation-counting, CPU and timing passes. It preserves baseline executables and
checks identical navigation outputs before comparing performance. See its README for capture, inspection
and before/after commands. These synthetic host measurements do not establish Android performance.

The first [measured optimization example](../../native/nav-sim/README.md#measured-optimization-example-2026-10-03)
reduced transactional history reallocations and reused the query latitude scale during route scans,
with identical simulated outputs and unchanged rollback/ambiguity gates. The linked report separates
allocation churn, sampled live heap and CPU/timing measurements.

## Try a repeatable run

From the repository root, with Rust 1.99+ and a C/C++ build toolchain on Linux:

```bash
RUSTFLAGS="-C force-frame-pointers=yes" cargo build --manifest-path native/Cargo.toml -p imu-nav-sim --profile profiling
native/target/profiling/imu-nav-sim run --scenario delayed --duration-s 120 --route-points 1000 --out captures/delayed
```

The output directory must be new. The [full simulator guide](../../native/nav-sim/README.md)
explains the scenarios, four separate measurement passes and before/after comparisons. Its inputs
represent the results of upstream motion detectors rather than raw phone sensor streams.
