# Glossary

[Documentation index](../README.md)

Find a term, then follow its topic link for steps and detailed reference.

Jump to: [A to D](#a-to-d) · [E to K](#e-to-k) · [L to R](#l-to-r) · [S to Z](#s-to-z)

## A to D

- **Android Auto** — A car-screen view of the phone's navigation app. The trip still runs on the
  phone. [Use it](ANDROID_AUTO.md).
- **Android SDK** — Tools and Android platform files needed to build the app.
  [Build guide](BUILD_AND_TEST.md).
- **API** — The requests and replies that let the app talk to a server. The cell-sharing API
  exchanges account sessions and tower data. [Cell guide](CELL_TOWERS.md).
- **APK** — The file you install on an Android phone.
- **AVD / emulator** — A simulated Android phone on a computer, used for device tests.
  [Build guide](BUILD_AND_TEST.md).
- **Barometer** — A phone sensor that measures air pressure. Pressure changes can help match hills
  on a route. [Navigation guide](NAVIGATION.md).
- **Cell tower** — A mobile-network radio site. The app uses nearby towers and a tower database for
  a rough location when GPS is weak. [Cell guide](CELL_TOWERS.md).
- **Cell-sharing server** — A server that combines tower positions contributed by phones and sends
  shared towers back. [Cell guide](CELL_TOWERS.md).
- **CI** — Continuous integration: automated builds and tests run for code changes.
  [Build guide](BUILD_AND_TEST.md).
- **Coverage** — A measure of which source-code lines ran during tests. Coverage does not prove that
  a feature is correct. [Kotlin](KOTLIN_COVERAGE.md) · [Rust](RUST_COVERAGE.md).
- **Dead reckoning** — Estimating how far you moved without a trusted GPS position, using speed,
  sensors and the planned route. [Navigation guide](NAVIGATION.md).

## E to K

- **ELM327 / OBD-II** — A car diagnostic adapter and port. This app reads vehicle speed from a
  paired adapter; it does not write to the car. [Navigation guide](NAVIGATION.md).
- **F-Droid** — An Android app repository that builds and signs its own APK from reviewed source.
  [Publication guide](FDROID.md).
- **Flavor** — A build choice for the same app. This project has Play and F-Droid flavors with
  different release rules. [Build guide](BUILD_AND_TEST.md).
- **GOOD / SUSPECT / BAD** — The app's labels for a GPS fix. GOOD may anchor navigation; SUSPECT and
  BAD do not become trusted positions. [Navigation guide](NAVIGATION.md).
- **GPS / GNSS** — Satellite positioning. GNSS is the broader name for satellite systems; the app
  checks each fix before using it. [Navigation guide](NAVIGATION.md).
- **Gradle** — The build tool started with `./gradlew`. It builds APKs and runs Kotlin tests.
  [Build guide](BUILD_AND_TEST.md).
- **GraphHopper** — The routing library that calculates routes from an offline routing pack.
  [Routing guide](ROUTING_AND_SEARCH.md).
- **IMU** — Inertial measurement unit: phone motion sensors such as the gyroscope and accelerometer.
  [Navigation guide](NAVIGATION.md).
- **JDK** — Java Development Kit, needed to run the project build and Kotlin tools.
  [Build guide](BUILD_AND_TEST.md).
- **JNI** — The bridge that lets Kotlin call native Rust code. [Build guide](BUILD_AND_TEST.md).
- **Kani** — A tool that checks selected Rust rules for many bounded inputs. It does not prove the
  whole app safe. [Verification guide](NATIVE_VERIFICATION.md).
- **Kotlin** — The programming language used for the Android app and much of the navigation logic.
  [Build guide](BUILD_AND_TEST.md).

## L to R

- **Map pack** — Data that draws streets on the map. It does not calculate routes; that needs a
  routing pack. [Routing guide](ROUTING_AND_SEARCH.md).
- **NDK** — Android Native Development Kit, used to build the Rust library for Android.
  [Build guide](BUILD_AND_TEST.md).
- **Offline** — Working with data already on the phone, without asking an internet service.
  [Routing guide](ROUTING_AND_SEARCH.md).
- **OpenCellID** — A source of cell-tower data that can be imported or downloaded with a token.
  [Cell guide](CELL_TOWERS.md).
- **OpenStreetMap / OSM** — Community map data used to build routing and address-search packs.
  [Routing guide](ROUTING_AND_SEARCH.md).
- **p95** — A test result where 95% of measured values are at or below the reported number.
  [Trips guide](TRIPS.md).
- **Position uncertainty** — The app's estimate of how far its displayed position might be from the
  real one. It grows while the app lacks trusted location evidence.
  [Navigation guide](NAVIGATION.md).
- **PostgreSQL** — A database server that the sharing server can use instead of a local SQLite
  file. [Server guide](../server/README.md#toml-configuration-and-postgresql).
- **Proof** — A check that a stated rule holds for the inputs a verification tool explores. A
  bounded proof covers only its stated limits. [Verification guide](NATIVE_VERIFICATION.md).
- **Proof bound** — A limit on the inputs or loop length a Kani check explores. Passing within a
  bound does not cover every possible drive. [Verification guide](NATIVE_VERIFICATION.md).
- **Proxy** — A server used as an intermediate connection for the app's online requests. It is not a
  phone-wide VPN. [Setup guide](GETTING_STARTED.md).
- **Replay** — Running a saved trip through the navigation engine again to compare its estimate with
  recorded GPS. [Trips guide](TRIPS.md).
- **Route** — The planned path from a start to a destination. The app tracks progress along this
  path. [Routing guide](ROUTING_AND_SEARCH.md).
- **Routing pack** — Downloadable road-graph data that lets the phone calculate routes and often
  search addresses offline. [Routing guide](ROUTING_AND_SEARCH.md).
- **Rust** — The programming language used by the native navigation core and the sharing server.
  [Build guide](BUILD_AND_TEST.md).
- **Rust native core** — The Rust part of the navigation engine, reached from Android through JNI.
  [Navigation guide](NAVIGATION.md).

## S to Z

- **Service area** — The geographic region where satellite fixes are accepted. The app now accepts
  valid fixes worldwide; a restricted area can still be used in tests. [Limitations](STATUS_AND_LIMITATIONS.md).
- **SQLite** — A small database stored in a file. The app uses it for local data, and the sharing
  server uses it by default for accounts and towers. [Cell guide](CELL_TOWERS.md).
- **Spoofing / jamming** — Spoofing supplies a false satellite position; jamming disrupts satellite
  reception. The app checks for both. [Navigation guide](NAVIGATION.md).
- **Tag** — A named point in Git history used to mark the exact source for a release.
  [F-Droid guide](FDROID.md).
- **Token** — A short-lived secret proving a signed-in account to the sharing server. Do not paste a
  real token into tests or issue reports. [Cell guide](CELL_TOWERS.md).
