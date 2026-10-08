# IMU Nav

IMU Nav is an Android car navigator that estimates progress **along a planned route** when GPS is
jammed or spoofed. It classifies GPS fixes before using them, then combines the phone's inertial
measurement unit (IMU), network location and map knowledge when trusted GPS is unavailable.
The live app uses Kotlin and Rust through the Java Native Interface (JNI).

This is a research prototype, not a safety system or a replacement for driver attention.
Valid fixes are accepted worldwide; offline maps, routes, address search and tower positioning need
data for the current region. Read the [status and limitations](docs/status_and_limitations.md)
before relying on a feature.

The [offline routing pack CI workflow](docs/routing_and_search.md#build-an-offline-routing-pack-in-ci)
builds an importable archive for offline routes and address search. The separate
[offline map pack CI workflow](docs/routing_and_search.md#build-an-offline-display-map-in-ci)
produces the streets shown on screen.
The app also links to map and routing ZIP archives from **Settings → Community links**.

## Documentation

Start with [getting started](docs/getting_started.md). Look up unfamiliar terms in the
[glossary](docs/glossary.md). Each guide links to a detailed reference.

### Use the app

- [Getting started and network proxy](docs/getting_started.md)
- [Navigation and positioning](docs/navigation.md)
- [Offline routing and address search](docs/routing_and_search.md)
- [Landscape driving and Android Auto](docs/android_auto.md)
- [Bookmarks](docs/bookmarks.md)
- [Trip recording, history, replay and opt-in developer diagnostics](docs/trips.md)
- [Capture and share Android performance data](docs/profiling_android.md)
- [Cell towers and sharing server](docs/cell_towers.md)

### Build and verify

- [Build, testing and performance checks](docs/build_and_test.md)
- [Native Rust crates: core, JNI bridge and simulator](docs/core/readme.md)
- [Cell-sharing server: setup, data and API](docs/server/readme.md)
- [F-Droid releases](docs/fdroid.md)
- [Kotlin coverage](docs/kotlin_coverage.md)
- [Rust coverage](docs/rust_coverage.md)
- [Native verification](docs/native_verification.md)

### Project information

- [Status and limitations](docs/status_and_limitations.md)
- [Licences and provenance](docs/licences.md)
- [Glossary](docs/glossary.md)

## Quick build

From a complete source checkout, follow [build and test](docs/build_and_test.md#prepare-the-tools)
to set up JDK 17+, Android SDK platform 36, build-tools 36.0.0, the Android NDK and Rust 1.99+
with all three Android targets. Build a debug-signed APK with offline-routing support:

```bash
./gradlew :app:assemblePlayBenchmark
```

The APK is written under `app/build/outputs/apk/play/benchmark/` and installs as
`org.imunav.app.bench`. Import the region's map and routing packs through
[the getting-started guide](docs/getting_started.md). A Debug APK is useful for UI development
but cannot load offline routing packs.

For a host-only starting point, run the [Rust checks](docs/core/build-and-replay.md#building-and-testing)
with Rust 1.99+; they do not need a JDK, Android SDK or NDK. Contributors should also run
`./gradlew check` in a configured Android development environment.
