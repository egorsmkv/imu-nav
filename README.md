# IMU Nav

Worldwide car navigation that keeps working when GPS is **jammed or spoofed**. An open Kotlin
implementation of the approach used by the BlindDriver app: instead of trusting GPS, it
classifies every fix, and when GPS is unusable it dead-reckons **along the planned route** using
the phone's IMU, network location and map knowledge. Offline maps, routes and cell data use regional packs.

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

Requires JDK 17+, Android SDK 36, the Android NDK and Rust with the Android targets. See
[build and test](docs/build_and_test.md) for setup and release commands.

```bash
./gradlew check
./gradlew :app:assemblePlayDebug
```
