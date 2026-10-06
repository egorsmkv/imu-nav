# Native simulator crate

[Rust core guide](../readme.md) · [Simulator glossary](glossary.md)

`imu-nav-sim` is a Linux command-line program for running repeatable, made-up navigation trips.
It is the binary crate in the [`native/nav-sim`](../../../native/nav-sim/) Cargo package. The
simulator calls the `imu-nav-core` library crate through its public Rust APIs. It does not use the
`imu-nav-jni` bridge, Android, recorded trips, an emulator or a network service.

The simulator creates the same inputs from the same settings and seed. This helps developers
exercise GPS loss, delayed observations, stops, reroutes and difficult route shapes, then check
whether a core change preserves the outputs. It also measures where a Linux host process spends
time and allocates memory. Synthetic results do not establish road accuracy or Android performance.
IMU Nav remains a research prototype, not a safety system.

## Read by task

1. [Build and run](run.md) starts a complete capture or a smaller targeted workload.
2. [Scenarios and inputs](scenarios.md) explains what each workload exercises and its limits.
3. [Measurements](measurements.md) explains the separate heap, allocation, CPU and timing passes.
4. [Inspect and compare](compare.md) shows how to read profiles and compare two runs.
5. [Checks](checks.md) lists crate tests and formatting commands.
6. [Glossary](glossary.md) defines the Rust and profiling terms used in these guides.

Run commands assume the repository root as the current directory. Keep the same simulator code,
compiler, host and input settings when comparing a baseline with a candidate.
