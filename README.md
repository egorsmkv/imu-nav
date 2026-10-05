# IMU Nav (open source)

Car navigation that keeps working when GPS is **jammed or spoofed**. An open Kotlin
implementation of the approach used by the BlindDriver app: instead of trusting GPS, it
classifies every fix, and when GPS is unusable it dead-reckons **along the planned route** using
the phone's IMU, network location and map knowledge.

IMU Nav is a research prototype, not a safety system.

## Documentation

Start with [getting started](docs/GETTING_STARTED.md). Look up unfamiliar terms in the
[glossary](docs/GLOSSARY.md). Each guide links to a detailed reference.

### Use the app

- [Getting started and network proxy](docs/GETTING_STARTED.md)
- [Navigation and positioning](docs/NAVIGATION.md)
- [Offline routing and address search](docs/ROUTING_AND_SEARCH.md)
- [Landscape driving and Android Auto](docs/ANDROID_AUTO.md)
- [Bookmarks](docs/BOOKMARKS.md)
- [Trip recording, history and replay](docs/TRIPS.md)
- [Cell towers and sharing server](docs/CELL_TOWERS.md)

### Build and verify

- [Build, testing and performance checks](docs/BUILD_AND_TEST.md)
- [F-Droid releases](docs/FDROID.md)
- [Kotlin coverage](docs/KOTLIN_COVERAGE.md)
- [Rust coverage](docs/RUST_COVERAGE.md)
- [Native verification](docs/NATIVE_VERIFICATION.md)

### Project information

- [Status and limitations](docs/STATUS_AND_LIMITATIONS.md)
- [Licences and provenance](docs/LICENCES.md)
- [Glossary](docs/GLOSSARY.md)

## Quick build

Requires JDK 17+, Android SDK 36, the Android NDK and Rust with the Android targets. See
[build and test](docs/BUILD_AND_TEST.md) for setup and release commands.

```bash
./gradlew check
./gradlew :app:assemblePlayDebug
```
