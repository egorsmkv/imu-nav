# Rust test coverage

Coverage measures which Rust production lines ran during the native and server test suites. The native suite includes host JNI calls; these results do not measure Android-only code.

## Run and read the report

1. On Linux, install Python 3.10+ and the pinned Rust toolchain with LLVM tools:

   ```bash
   rustup toolchain install nightly-2026-09-25 --profile minimal --component llvm-tools-preview
   ```

2. For the native/JNI suite, also provide JDK 17 and Android SDK 36. The server-only suite does not need them.
3. Run one suite or both:

   ```bash
   python3 tools/rust_coverage.py --suite native
   python3 tools/rust_coverage.py --suite server
   # Or run both: python3 tools/rust_coverage.py
   ```

4. Open `build/rust-coverage/index.html`. Read the summary to see which module missed its minimum, then open that module's source report.

A percentage is a test signal, not proof of safe navigation. See the [detailed Rust coverage reference](reference/RUST_COVERAGE.md) and the [glossary](GLOSSARY.md).
