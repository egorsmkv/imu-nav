# Build and test

[Documentation index](../README.md)

Use this guide from a complete source checkout, with commands run at the project root.
The [glossary](glossary.md) explains the tools below.
For the three native Rust crates and host replay commands, start with the [Rust core guide](core/readme.md).

## Prepare the tools

| Work | Required tools |
| --- | --- |
| Rust host tests and simulator | Rust 1.99+ and Cargo |
| Kotlin core tests and JVM replay | JDK 17+ and the checked-in Gradle wrapper; Rust is also needed for JNI replay tests |
| Android APK, lint and device tests | JDK 17+, Android SDK platform 36, build-tools 36.0.0, an installed NDK and Rust 1.99+ with the targets below |
| Full `./gradlew check` | The Android toolchain above; it also builds and tests the local Rust cell server |

Install the SDK platform, build tools and NDK through Android Studio's SDK Manager or your SDK
command-line tools. The repository does not pin an NDK version. Set these variables to the actual
installed directories; the paths below are examples for a Linux host:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<installed-version>"
```

Replace `<installed-version>` with a directory present in your SDK. On macOS, the usual SDK
directory is `$HOME/Library/Android/sdk`. An explicit `ANDROID_NDK_HOME` selects the NDK; otherwise
the [native build script](../scripts/build-rust-android.sh) also checks `ANDROID_NDK_ROOT` and
the SDK's `ndk/` directory. Android builds need Bash and the NDK's host LLVM toolchain.

Install the targets in the Rust toolchain that will run the build:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
java -version
rustc --version
./gradlew --version
```

These checks should find the selected JDK, Rust compiler and Gradle wrapper. There is no separate
Kotlin installation step: Gradle resolves the version pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Build the app

1. Prepare the tools above.
2. Build a debug-signed APK for offline-routing and performance tests:

   ```bash
   ./gradlew :app:assemblePlayBenchmark
   ```

   Find it under `app/build/outputs/apk/play/benchmark/`. It installs beside the release app as
   `org.imunav.app.bench` and enables responsiveness diagnostics. A successful build does not
   supply regional packs; follow [getting started](getting_started.md) to import them.
3. Use `:app:assemblePlayDebug` for UI development that does not load offline routing packs.
   Debug APKs cannot load those packs because of GraphHopper record desugaring.
4. For a release build, use `:app:assemblePlayRelease` or `:app:assembleFdroidRelease`.
   Play signing needs your own ignored `keystore.properties`; without it the Play release is
   unsigned. The F-Droid release is always unsigned so F-Droid can sign it. See the
   [signing reference](reference/build_and_test.md#code-checks).

## Run checks

1. Format Kotlin and Gradle files with `./gradlew spotlessApply`.
2. Run `./gradlew check` for project tests, static analysis and Android Lint.
3. Run `./gradlew :core:test` for the quick engine tests. It excludes `CellServerApiIntegrationTest`.
   Run `./gradlew :core:serverApiTest` to build a Rust server and test the Kotlin sharing client
   against its loopback endpoint and a temporary SQLite database. This integration test is included
   in `./gradlew check`.
4. For device tests, start an emulator or connect a device and run `./gradlew :app:connectedFdroidDebugAndroidTest`.

`./gradlew check` also runs formatting, tests and pedantic Clippy for both Rust workspaces
(`native/` and `server/`). A passing `:core:test` or native test run covers only that suite;
it does not establish that Android lint, device behavior or the full check passed.

## Troubleshoot setup

| Symptom | Check and action |
| --- | --- |
| `Android NDK not found` | Check `ANDROID_NDK_HOME` points to an installed NDK directory, then rerun the build. |
| `Android linker not found` | Check that NDK contains `toolchains/llvm/prebuilt/<host>/bin/` and the API-26 Clang launchers used by the [build script](../scripts/build-rust-android.sh). |
| Cargo cannot find an Android target's standard library | Run `rustup target list --installed` for the selected toolchain and add all three targets listed above. |
| `NoClassDefFoundError: com/android/tools/r8/RecordTag` while loading a pack | Install a Benchmark APK built with R8, then retry the pack. A Debug APK cannot exercise offline routing. |

## Server and additional checks

For the production cell server, run `cargo build --locked --release --manifest-path server/Cargo.toml`.
Its release profile uses maximum optimization, fat link-time optimization and one codegen unit;
expect a slower build in exchange for more optimization across dependencies.
The [server guide](server/readme.md) explains its Rust crates, storage choices and deployment.

The `Server Docker image` CI workflow also builds `server/Dockerfile` from the `server/` context
and checks that the resulting binary starts.
Automatic CI workflows use path filters so documentation and unrelated modules do not start every
build. F-Droid release tags still build regardless of changed paths; all manual dispatches remain
available.

For release rules, see [F-Droid](fdroid.md). For measurements, see [Kotlin coverage](kotlin_coverage.md), [Rust coverage](rust_coverage.md) and [native verification](native_verification.md).
For a performance trace from an installed app, see [Android performance capture](profiling_android.md).

The [detailed build reference](reference/build_and_test.md) has signing fields, dependency checks, replay and performance commands.
