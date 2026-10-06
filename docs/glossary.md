# Glossary

[Documentation index](../README.md)

Find a term, then follow its topic link for steps and detailed reference.
For terms specific to the Rust navigation crates, see the [newcomer core glossary](core/glossary.md).
For sharing-server terms, see the [newcomer server glossary](server/glossary.md).

Jump to: [A to D](#a-to-d) · [E to K](#e-to-k) · [L to R](#l-to-r) · [S to Z](#s-to-z)

## A to D

- **Android Auto** — A car-screen view of the phone's navigation app. The trip still runs on the
  phone. [Use it](android_auto.md).
- **Android SDK** — Tools and Android platform files needed to build the app.
  [Build guide](build_and_test.md).
- **API** — The requests and replies that let the app talk to a server. The cell-sharing API
  exchanges account sessions and tower data. [Cell guide](cell_towers.md).
- **APK** — The file you install on an Android phone.
- **AVD / emulator** — A simulated Android phone on a computer, used for device tests.
  [Build guide](build_and_test.md).
- **Barometer** — A phone sensor that measures air pressure. Pressure changes can help match hills
  on a route. [Navigation guide](navigation.md).
- **Cell tower** — A mobile-network radio site. The app uses nearby towers and a tower database for
  a rough location when GPS is weak. [Cell guide](cell_towers.md).
- **Cell-sharing server** — A server that combines tower positions contributed by phones and sends
  shared towers back. [Server guide](server/readme.md).
- **CI** — Continuous integration: automated builds and tests run for code changes.
  [Build guide](build_and_test.md).
- **Coverage** — A measure of which source-code lines ran during tests. Coverage does not prove that
  a feature is correct. [Kotlin](kotlin_coverage.md) · [Rust](rust_coverage.md).
- **Dead reckoning** — Estimating how far you moved without a trusted GPS position, using speed,
  sensors and the planned route. [Navigation guide](navigation.md).

## E to K

- **ELM327 / OBD-II** — A car diagnostic adapter and port. This app reads vehicle speed from a
  paired adapter; it does not write to the car. [Navigation guide](navigation.md).
- **F-Droid** — An Android app repository that builds and signs its own APK from reviewed source.
  [Publication guide](fdroid.md).
- **Flavor** — A build choice for the same app. This project has Play and F-Droid flavors with
  different release rules. [Build guide](build_and_test.md).
- **GOOD / SUSPECT / BAD** — The app's labels for a GPS fix. GOOD may anchor navigation; SUSPECT and
  BAD do not become trusted positions. [Navigation guide](navigation.md).
- **GPS / GNSS** — Satellite positioning. GNSS is the broader name for satellite systems; the app
  checks each fix before using it. [Navigation guide](navigation.md).
- **Gradle** — The build tool started with `./gradlew`. It builds APKs and runs Kotlin tests.
  [Build guide](build_and_test.md).
- **GraphHopper** — The routing library that calculates routes from an offline routing pack.
  [Routing guide](routing_and_search.md).
- **IMU** — Inertial measurement unit: phone motion sensors such as the gyroscope and accelerometer.
  [Navigation guide](navigation.md).
- **JDK** — Java Development Kit, needed to run the project build and Kotlin tools.
  [Build guide](build_and_test.md).
- **JNI** — The bridge that lets Kotlin call native Rust code. [Core guide](core/jni-and-android.md).
- **Kani** — A tool that checks selected Rust rules for many bounded inputs. It does not prove the
  whole app safe. [Verification guide](native_verification.md).
- **Kotlin** — The programming language used for the Android app and much of the navigation logic.
  [Build guide](build_and_test.md).

## L to R

- **Map pack** — Data that draws streets on the map. It does not calculate routes; that needs a
  routing pack. [Routing guide](routing_and_search.md).
- **NDK** — Android Native Development Kit, used to build the Rust library for Android.
  [Build guide](build_and_test.md).
- **Offline** — Working with data already on the phone, without asking an internet service.
  [Routing guide](routing_and_search.md).
- **OpenCellID** — A source of cell-tower data that can be imported or downloaded with a token.
  [Cell guide](cell_towers.md).
- **OpenStreetMap / OSM** — Community map data used to build routing and address-search packs.
  [Routing guide](routing_and_search.md).
- **p95** — A test result where 95% of measured values are at or below the reported number.
  [Trips guide](trips.md).
- **Position uncertainty** — The app's estimate of how far its displayed position might be from the
  real one. It grows while the app lacks trusted location evidence.
  [Navigation guide](navigation.md).
- **PostgreSQL** — A database server that the sharing server can use instead of a local SQLite
  file. [Server deployment](server/deploy-with-compose.md).
- **Proof** — A check that a stated rule holds for the inputs a verification tool explores. A
  bounded proof covers only its stated limits. [Verification guide](native_verification.md).
- **Proof bound** — A limit on the inputs or loop length a Kani check explores. Passing within a
  bound does not cover every possible drive. [Verification guide](native_verification.md).
- **Proxy** — A server used as an intermediate connection for the app's online requests. It is not a
  phone-wide VPN. [Setup guide](getting_started.md).
- **Replay** — Running a saved trip through the navigation engine again to compare its estimate with
  recorded GPS. [Trips guide](trips.md).
- **Route** — The planned path from a start to a destination. The app tracks progress along this
  path. [Routing guide](routing_and_search.md).
- **Routing pack** — Downloadable road-graph data that lets the phone calculate routes and often
  search addresses offline. [Routing guide](routing_and_search.md).
- **Rust** — The programming language used by the native navigation core and the sharing server.
  [Build guide](build_and_test.md).
- **Rust native core** — The Rust part of the navigation engine, reached from Android through JNI.
  [Core guide](core/readme.md).

## S to Z

- **Service area** — The geographic region where satellite fixes are accepted. The app now accepts
  valid fixes worldwide; a restricted area can still be used in tests. [Limitations](status_and_limitations.md).
- **SQLite** — A small database stored in a file. The app uses it for local data, and the sharing
  server uses it by default for accounts and towers. [Cell guide](cell_towers.md).
- **Spoofing / jamming** — Spoofing supplies a false satellite position; jamming disrupts satellite
  reception. The app checks for both. [Navigation guide](navigation.md).
- **Tag** — A named point in Git history used to mark the exact source for a release.
  [F-Droid guide](fdroid.md).
- **Token** — A short-lived secret proving a signed-in account to the sharing server. Do not paste a
  real token into tests or issue reports. [Cell guide](cell_towers.md).
