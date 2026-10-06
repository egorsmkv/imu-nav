# Check the simulator crate

[Simulator guide](README.md) · [Build and run](run.md)

Run these from the repository root. The test command targets the simulator package; the clippy and
format commands cover the whole `native/` workspace.

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
