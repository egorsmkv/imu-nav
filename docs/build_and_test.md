# Build and test

[Documentation index](../README.md)

Use this guide from a complete source checkout, with commands run at the project root.
The [glossary](glossary.md) explains the tools below.
For the three native Rust crates and host replay commands, start with the [Rust core guide](core/readme.md).

## Prepare the tools

Gradle configures the Android app even for JVM-only `:core`, `:routing` and `:replay` tasks.
Install SDK platform 36, build-tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1 before
running Gradle; AndroidX Graphics Path's native build model needs the pinned NDK during
project configuration. The host algorithms and host JNI still execute without Android.

| Work | Required tools |
| --- | --- |
| Rust host tests and simulator | Rust 1.99+ and Cargo |
| Kotlin core tests and JVM replay | JDK 17+ and the checked-in Gradle wrapper; Rust is also needed for JNI replay tests |
| Android APK, lint and device tests | JDK 17+, Android SDK platform 36, build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1 and Rust 1.99+ with the targets below |
| Full `./gradlew check` | The Android toolchain above; it also builds and tests the local Rust cell server |

Install the SDK platform, build tools, NDK and CMake through Android Studio's SDK Manager or your SDK
command-line tools. Gradle pins NDK 28.2.13676358, whose linker defaults to 16 KB alignment.
The Rust JNI build script explicitly sets both maximum and common page sizes to 16 KB,
including for direct Cargo builds, so Rust compiler defaults cannot misalign the RELRO end.
CMake rebuilds the vendored AndroidX Graphics Path 1.0.1 JNI; its original AAR has a
misaligned RELRO end. The Java dependency is pinned to match the unchanged native source.
Set these variables to the actual
installed directories; the paths below are examples for a Linux host:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
```

Install that NDK version in your SDK. On macOS, the usual SDK directory is
`$HOME/Library/Android/sdk`. An explicit `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` can override
the Rust script's selection; every resulting APK still needs the page-alignment gate below.
Direct script invocation also checks the SDK's `ndk/` directory.
Gradle passes the SDK location selected by Android Studio (including `local.properties`) to the script.
Linux and macOS use [Bash](../scripts/build-rust-android.sh); Windows uses
[Windows PowerShell](../scripts/build-rust-android.ps1) and the NDK's Windows LLVM toolchain.
Windows builds do not require Bash or WSL. Install **NDK (Side by side)** from Android Studio's
SDK Manager, then install the Rust targets below. Restart Android Studio after installing Rust
so it inherits Cargo's updated `PATH`. Use `gradlew.bat` for Gradle commands on Windows.

Install the targets in the Rust toolchain that will run the build:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
java -version
rustc --version
./gradlew --version
```

These checks should find the selected JDK, Rust compiler and Gradle wrapper. The wrapper pins
Gradle 9.8.1 and verifies its downloaded distribution against the official SHA-256 checksum.
There is no separate Kotlin installation step: Gradle resolves the version pinned in
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

CI benchmark APKs use a separately configured private signing identity so successive artifacts
can replace an existing installation. Artifact names distinguish **signed** APKs from
**unsigned, non-installable** validation builds. See [benchmark signing and updates](benchmark_signing.md)
for the one-time CI setup, migration from older random-key artifacts, and installer diagnostics.

## Run checks

1. Format Kotlin and Gradle files with `./gradlew spotlessApply`.
2. Run `./gradlew check` for project tests, static analysis and Android Lint.
3. Run `./gradlew :core:test` for the quick engine tests. It excludes `CellServerApiIntegrationTest`.
   Run `./gradlew :core:serverApiTest` to build a Rust server and test the Kotlin sharing client
   against its loopback endpoint and a temporary SQLite database. This integration test is included
   in `./gradlew check`.
4. For device tests, start an emulator or connect a device and run `./gradlew :app:connectedFdroidDebugAndroidTest`.
   The [system GPS tests](mock_location_testing.md) change mock AppOps and require a disposable emulator;
   their CI matrix covers API 28, 31, 35 and 36 separately from host coverage.

`./gradlew check` also runs formatting, tests and pedantic Clippy for both Rust workspaces
(`native/` and `server/`). A passing `:core:test` or native test run covers only that suite;
it does not establish that Android lint, device behavior or the full check passed.

## Verify 16 KB compatibility

Build an APK, then check **all** packaged 64-bit libraries, including MapLibre and other prebuilt
dependencies, and the final ZIP alignment:

```bash
python3 tools/verify_android_pages.py \
  --zipalign "$ANDROID_HOME/build-tools/36.0.0/zipalign" \
  --report build/android-pages.json app/build/outputs/apk/fdroid/release/*.apk
```

The gate checks ELF `LOAD` alignment/congruence and GNU RELRO end alignment, then runs
`zipalign -v -c -P 16 4`. Missing libraries/tools and failed checks fail the command; the JSON
report retains evidence. F-Droid CI applies this to release and benchmark APKs. Android
instrumentation CI also runs on an API 35 `google_apis_ps16k` x86_64 emulator and requires
`adb shell getconf PAGE_SIZE` to return `16384` before tests begin. Older images without
`getconf` use the base `KernelPageSize` in `/proc/self/smaps` instead. The emulator gets 4 GB
of RAM so the larger-page Google APIs image can keep instrumentation alive. Alignment alone does not
establish runtime compatibility. See the official [Android page-size guide](https://developer.android.com/guide/practices/page-sizes)
and the [emulator runner targets](https://github.com/ReactiveCircus/android-emulator-runner#configurations).

## Troubleshoot setup

| Symptom | Check and action |
| --- | --- |
| `Android NDK not found` | Check `ANDROID_NDK_HOME` points to an installed NDK directory, then rerun the build. |
| `CreateProcess error=193` in `:app:buildRustAndroid` on Windows | Update to the Windows PowerShell build script, resync Gradle and install NDK (Side by side) in SDK Manager. The Unix `.sh` script cannot be launched directly by Windows. |
| `Android linker not found` | Check that NDK contains `toolchains/llvm/prebuilt/<host>/bin/` and the API-26 Clang launchers (`*-clang.cmd` on Windows) used by the build scripts. |
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
