# Rust coverage

> Detailed reference. For easy steps, see the [guide](../RUST_COVERAGE.md) or the [glossary](../GLOSSARY.md).

Run on Linux with Python 3.10+, JDK 17 and Android SDK 36 (the shared Gradle project
configures Android even when running host JNI tests). The server-only suite does
not need Java or Android. Coverage uses a pinned compiler because LLVM coverage
maps and branch instrumentation change between compiler releases.

```bash
rustup toolchain install nightly-2026-09-25 --profile minimal --component llvm-tools-preview
python3 -m unittest discover -s tools/tests -v
python3 tools/rust_coverage.py                    # both suites
python3 tools/rust_coverage.py --suite native     # core, JNI, simulator
python3 tools/rust_coverage.py --suite server     # server library and executable
```

Set `JAVA_HOME` and `ANDROID_HOME` if they are not already configured. LLVM tools
come from the pinned toolchain. `LLVM_COV` and `LLVM_PROFDATA` may point to matching
LLVM 23 tools; mismatched major versions fail validation.

Open `build/rust-coverage/index.html`. The directory also contains `summary.json`,
merged production `coverage.lcov`, and HTML/LCOV/logs for each execution domain.
Use `--output PATH` to keep different runs. Raw profiles have unique process,
binary and run identifiers, including simulator/server subprocesses; old raw
profiles are never reused. Build caches are isolated from ordinary Cargo builds.

## What is measured

| Gate                                       | Minimum production line coverage |
| ------------------------------------------ | -------------------------------: |
| `imu-nav-core`                             |                              95% |
| Core trust classifier (`trust.rs`)         |                              95% |
| `imu-nav-jni`                              |                              85% |
| `imu-nav-sim`                              |                              95% |
| `imu-nav-cell-server`, including `main.rs` |                              90% |

The denominator includes executable lines in handwritten Rust production files.
Test modules live in separate files and are excluded, as are dependencies and
build scripts. Kani-only `kani_proofs.rs` files and files inside `kani_proofs/`
directories are excluded from both LLVM reports and the production-module inventory.
Missing production modules, missing binaries, empty JVM profiles,
and LLVM mapping warnings fail the run. Gates compare exact counts, not rounded
percentages. Branch coverage appears in each domain's detailed report but is not
yet gated. These host results do not measure Android-only platform code.

Cargo JSON supplies actual executable and cdylib paths. Native Rust tests,
heap-profile Rust tests, default JVM tests, heap-profile JVM tests, and server tests
are separate coverage domains. Raw profiles are merged only within a domain;
LCOV executable/covered lines are unioned across domains. This avoids corrupting
counts when the same function has different coverage mappings in a test binary
and a cdylib. Debuginfod is disabled to prevent report generation from depending
on remote symbol servers.

JNI tests execute the real Kotlin wrappers and private native entry points in a
host JVM. Reflection in the contract tests reaches malformed arrays and stale
handles without widening app APIs. Replay regression tests run in both default
and heap-profile configurations. `-PnativeLibraryDir=/absolute/library/directory`
selects a prebuilt host library, and `-PnativeCoverage` forces test execution even
when Gradle would consider outputs current.

The simulator suite decodes captured CPU and heap profiles and checks deterministic
navigation outputs. Coverage-instrumented timings are **not performance evidence**;
use the ordinary profiling build for before/after optimization measurements.

The `Rust coverage` GitHub Actions workflow runs separate native/JNI and server
jobs on pull requests and pushes to `main`, enforces these gates, and uploads
reports and diagnostics even on failure. Ordinary `cargo test` and `./gradlew
check` remain useful independently of the coverage toolchain.
