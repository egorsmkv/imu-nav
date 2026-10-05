# Kotlin test coverage

Coverage shows which Kotlin source lines ran during the test suites. A green line was exercised by a test; it does not mean every behaviour on that line is correct.

## Run and read the report

1. On Linux, install Python 3.10+, JDK 17, Android SDK platform 36 and build-tools 36.0.0, and a Rust toolchain supported by `native/Cargo.toml`.
2. From the project root, run:

   ```bash
   python3 -m unittest discover -s tools/tests -v
   python3 tools/kotlin_coverage.py
   ```

3. Open `build/kotlin-coverage/index.html`. Check the per-module gates and follow the links to source lines and detailed reports.
4. If a gate fails, add meaningful tests or review the changed code. Update the saved baseline only after reviewing a full measurement.

The CI job runs the Kotlin/server API test before coverage. This coverage report uses host JVM tests; it does not measure screens on an emulator. See the [detailed coverage reference](reference/KOTLIN_COVERAGE.md) and the [glossary](GLOSSARY.md).
