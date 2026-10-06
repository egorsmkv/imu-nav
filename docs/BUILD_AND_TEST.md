# Build and test

[Documentation index](../README.md)

Use this guide from the project root. The app needs JDK 17 or newer, Android SDK platform 36 and build-tools 36.0.0, the Android NDK, and Rust 1.99 or newer with the Android targets. The [glossary](GLOSSARY.md) explains these tools.

## Build the app

1. Install the tools above. Point `ANDROID_HOME` at your SDK if Gradle cannot find it.
2. Build a development APK:

   ```bash
   ./gradlew :app:assemblePlayDebug
   ```

3. For a release build, use `:app:assemblePlayRelease` or `:app:assembleFdroidRelease`. Play signing needs your own ignored `keystore.properties`; the F-Droid release APK is unsigned so F-Droid can sign it.
4. For offline-routing or performance tests on a device, use `:app:assemblePlayBenchmark`. Debug APKs cannot load routing packs.

## Run checks

1. Format Kotlin and Gradle files with `./gradlew spotlessApply`.
2. Run `./gradlew check` for project tests, static analysis and Android Lint.
3. Run `./gradlew :core:test` for the quick engine tests. Run `./gradlew :core:serverApiTest` to test the Kotlin sharing client against a temporary local Rust server.
4. For device tests, start an emulator or connect a device and run `./gradlew :app:connectedFdroidDebugAndroidTest`.

For the production cell server, run `cargo build --locked --release --manifest-path server/Cargo.toml`.
Its release profile uses maximum optimization, fat link-time optimization and one codegen unit;
expect a slower build in exchange for more optimization across dependencies.
The `Server Docker image` CI workflow also builds `server/Dockerfile` from the `server/` context
and checks that the resulting binary starts.
Automatic CI workflows use path filters so documentation and unrelated modules do not start every
build. F-Droid release tags still build regardless of changed paths; all manual dispatches remain
available.

For release rules, see [F-Droid](FDROID.md). For measurements, see [Kotlin coverage](KOTLIN_COVERAGE.md), [Rust coverage](RUST_COVERAGE.md) and [native verification](NATIVE_VERIFICATION.md).

The [detailed build reference](reference/BUILD_AND_TEST.md) has signing fields, dependency checks, replay and performance commands.
