# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. Read this before changing
code; `README.md` indexes the user and developer guides, and `docs/reference/NAVIGATION.md` explains the algorithms.

## What this is

**IMU Nav** — an Android car navigator for Ukraine that keeps working when GPS is jammed or
spoofed. It classifies every GPS fix (GOOD / SUSPECT / BAD) and, without trusted GPS, dead-reckons
along the planned route using IMU, cell towers and map knowledge. Routing and address search work
offline (GraphHopper packs). UI: Jetpack Compose + MapLibre, Ukrainian, English and Russian.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:core` | pure Kotlin/JVM | the algorithm: trust classifier, navigation engine, speed fusion, cell positioning, trip recording/replay, search logic, shared OkHttp client (`core/net/Http.kt`). **No Android imports.** |
| `:app` | Android app | Android glue and UI: `AppGraph` (manual DI, wires everything), `SensorHub`, `CellScanner`/`CellManager`, `NavService` (foreground service), `TripManager`, Compose screens in `ui/` |
| `:routing` | JVM | GraphHopper integration used by both the phone (`OfflineGraph`, `PhoneGraphHopper`) and the desktop pack builder (`BuildGraph`, `SearchIndexBuilder`) |
| `server/` | Rust app | persistent SQLite cell-sharing server (Axum HTTP/WebSocket, anti-poisoning consensus) |
| `:replay` | JVM app | CLI that replays recorded trips (`.rec.gz`) through the engine and reports errors |

Package root: `org.imunav.<module>`. Put new logic in `:core` whenever it does not need Android.

## Commands

```bash
./gradlew check                 # all tests + detekt + ktlint (Spotless) + Android Lint — must pass
./gradlew spotlessApply         # auto-format before committing
./gradlew :core:test            # fast: engine, classifier, cells, HTTP, replay tests
./gradlew :app:assembleRelease  # signed, R8-shrunk, ARM-only APK (~350 MB with bundled map)
./gradlew :app:assemblePlayBenchmark  # release build + StrictMode/MainThreadWatchdog, appId suffix .bench
./gradlew :app:lintRelease      # Android Lint only
./gradlew detekt                # static analysis only
GRAPH_DIR=/path/to/graph-ukraine ./gradlew :routing:test --tests '*PackSmokeTest*'   # needs a real pack
./gradlew :replay:run --args="trips/ --hide-gps-after 60 --out replay-out"
./gradlew :routing:run --args="--osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine"  # ~10 GB RAM
```

Requirements: JDK 17+, Android SDK platform 36. `adb` lives at `~/Library/Android/sdk/platform-tools/adb` (not on PATH).

## Definition of done

1. `./gradlew check` is green: tests, detekt (0 findings), ktlint, Android Lint (warnings are errors).
2. **Engine changes keep replay accuracy.** `ReplayTest` prints `p95=20 max=25 rms=10 m` for the blind
   drive. A refactor must leave these numbers identical; an algorithm change must not make them worse
   without a stated reason. Check with:
   `grep -h -o "p95[^<]*" core/build/test-results/test/*.xml`
3. UI or runtime changes: build the release APK and try it on the emulator/phone (see *Testing on a device*).
4. User-visible strings exist in **both** `values/strings.xml` and `values-uk/strings.xml`.
5. Update the relevant file in `docs/` when behaviour, settings, commands or dependencies change.

## Code style

- Formatting is ktlint (`intellij_idea` style, 180 columns, see `.editorconfig`) — run `spotlessApply`, don't hand-format.
- detekt rules and deliberate deviations are in `config/detekt.yml`; lint exceptions in `app/lint.xml`.
  Prefer fixing over suppressing; any `@Suppress` needs a comment saying why.
- The code is meant to be readable by junior developers:
  - descriptive names (no one-letter names except loop indices and `dt`/`s` conventions below),
  - KDoc on every class and non-trivial function, explaining *why* and the concept, not restating the code,
  - named constants instead of unexplained numbers in app code (thresholds belong in `Tuning` / `TrustConfig`),
  - imports instead of inline fully-qualified names, no `!!`.
- Idiomatic Kotlin: `data class` for values, immutable state published via `StateFlow`
  (private `_x` + public `x`), `buildList`, `sumOf`, `?.let`, `when`, KTX (`prefs.edit { }`, `db.transaction { }`).
- Domain conventions: **`s` = metres along the route** (the whole DR state); times are ms from
  `SystemClock.elapsedRealtime()` (`elapsedMs`); speeds in m/s unless the name says `Kmh`; angles in degrees,
  positive = clockwise/right.
- Log lines are short `key=value` messages via `TripLog.write` / `NavListener.onLog`
  (e.g. `turn_snap step=4 from_s=… to_s=…`) — keep existing keys stable, tools and tests grep them.

## Architecture rules

- **Threading:** `AppGraph` and all engine access run on the main thread. Disk, network and database
  work goes to `Dispatchers.IO` or the dedicated single-thread executors (`TripLog`, `TripManager`);
  results are posted back. HTTP calls are blocking — never call them on the main thread.
  System services that talk over binder count as I/O: text-to-speech (`Voice` worker thread), sensor
  and location registration (`SensorHub` control thread), cell scans, vibration (`Haptics`). Don't build
  large strings (route encoding, JSON with the route) on the main thread either — do it on the executor.
  Check with the benchmark build: nothing of ours may appear in `MainThreadWatchdog` reports.
- **Car speed and terrain:** OBD-II speed enters via `NavigationEngine.onVehicleSpeed`, barometer via
  `onPressure`; both are recorded (`TripEvent.VehicleSpeed` / `Pressure`, lines `V` / `B`) and replayed.
  Terrain matching must stay conservative: a wrong snap is worse than none. Keep the relief / RMS /
  rival-ratio gates in `ElevationMatcher` and the false-match tests in `TerrainAndVehicleSpeedTest` green.
  Elevation packs: `PackInfo.elevation` decides `hopper.setElevation(true)` on the phone — builder and
  loader must agree or GraphHopper refuses to load. `ObdLink` never writes to the car (read-only PIDs).
- **Haptics:** engine events that deserve a vibration go through `NavListener.onAlert(NavAlert)`,
  called next to the matching voice phrase; patterns live in `haptics/Haptics.kt`. UI taps use Compose
  `LocalHapticFeedback`, not the vibrator.
- **Map view:** `MapScreen` stays composed under the other screens (`AppRoot` overlays them), and
  `NavMap(active = false)` only pauses the MapView. Don't move the map inside a `when (screen)` again —
  recreating a MapView costs 200–700 ms on the main thread.
- **HTTP:** always through `org.imunav.core.net.Http` (shared OkHttp client). Do not add
  `HttpURLConnection` or new clients; use `Http.client.newBuilder()` for different timeouts.
- **Travel mode** (`TravelMode.CAR` / `FOOT`) selects the GraphHopper profile and the engine's motion
  model. Car-only corrections must stay gated by `mode == CAR` (or `Tuning.forWalking()`); walking uses
  the `Pedometer` (steps × learned stride). Both profiles live in one pack; `GraphSpec.config(profiles)`
  must match what `pack.json` says the pack contains, and `PhoneGraphHopper` mirrors each custom model in
  plain code — change both together (`OfflineGraphTest` compares phone and desktop for car and foot).
- **Positioning inputs** go through `PositioningHub` (which also records them for replay). Anything the
  engine consumes must be recorded in `TripEvent`, or replays will diverge from real drives.
- **Trust:** the fused provider and Android's cached GPS fix are never trusted for navigation
  (they inherit spoofed positions). Mock fixes and fixes outside `ServiceArea.UKRAINE_COARSE` are BAD.
- **Battery:** sensor rates, scan intervals and map FPS come from `PowerProfile` (`power/Power.kt`);
  don't hard-code new polling loops — tie them to the profile and to `AppGraph.uiVisible`.
- **Long tasks** in `CellManager` / `OfflineRouting` use their `runTask` helpers (one task at a time,
  cancellable, cleanup in `NonCancellable`).

## Known pitfalls (learned the hard way)

- **compileSdk is 36.** Several newest AndroidX libs and OkHttp 5.5+ require 37 — versions in
  `gradle/libs.versions.toml` are pinned deliberately (OkHttp is 5.4.0). Check AAR metadata before upgrading.
- **minSdk is 26 (Android 8.0).** Lint catches newer API calls; guard them with `Build.VERSION` checks
  and keep fallbacks working on the minimum version. In particular, 5G NR and asynchronous cell scans
  start on Android 10, while MCC/MNC string access starts on Android 9.
- **GraphHopper on Android:** Janino cannot generate bytecode on ART, so `PhoneGraphHopper` builds the
  weighting in plain code; `GraphSpec` must stay identical between phone and desktop builder
  (`OfflineGraphTest` checks both give the same routes). `close()` of memory-mapped graphs throws on
  Android and is wrapped in `runCatching`. Points are clamped into the pack's bounds.
- **APK licence hygiene:** `osmosis-osm-binary` (LGPL) and protobuf are excluded from the app in
  `app/build.gradle.kts` — only GraphHopper's `.osm.pbf` reader needs them (desktop only). Don't add
  copyleft dependencies to `:app`; update `docs/LICENCES.md` and `docs/reference/LICENCES.md` for any dependency change.
- **R8:** GraphHopper/Jackson/HPPC/MapLibre are kept wholesale in `app/proguard-rules.pro`
  (reflection). Broad `-dontwarn com.graphhopper.**` hides missing-class errors — verify with the
  mapping file (`app/build/outputs/mapping/release/mapping.txt`) when changing dependencies.
- **Bundled assets** (`assets/routing/pack.zip` ~300 MB, `assets/cells/bundled-cells.csv.gz`) are big;
  `pack.zip` is stored uncompressed (`noCompress += "zip"`) and gitignored.
- **Trip recordings** are gzip streams that may be truncated by a kill; always read them with
  `TripFormat.read` (salvages the tail) and `TripFormat.repair` before appending.
- **Debug builds cannot load the offline routing pack:** D8 desugars GraphHopper's Java records and the
  debug APK then fails with `NoClassDefFoundError: com/android/tools/r8/RecordTag`. Use the `benchmark`
  build type (R8, debug-signed) for anything involving offline routing or performance measurements.
- **In-app language** is applied via `AppLanguage.wrap` in `attachBaseContext` (app + activity); in
  Compose read resources with `LocalResources.current`, not `LocalContext.current.resources`.

## Testing on a device

- Emulator: `emulator-5554`, 1080×2400. Screenshots from `adb exec-out screencap -p` are often shown
  scaled — map tap coordinates accordingly. The emulator's GPS is always classified BAD (spoof-like
  signals), so use **Start here** to place a manual start; map matching needs a real drive.
- `adb emu geo fix <lon> <lat>` sets the emulator's GPS; `adb shell am force-stop org.imunav.app`
  before relaunching to get a cold start (`install -r` alone may reuse the process).
- Release builds are not debuggable: app-private files (`run-as`) are not accessible.
- Real phone: over Wi-Fi adb only when the user has enabled wireless debugging; never unlock the phone
  or change its settings yourself.

## Safety and secrets

- Never commit or print `keystore/`, `keystore.properties`, API keys, OpenCellID tokens or sync keys;
  never type tokens into the app on the user's behalf — the user enters them.
- Don't commit or push unless asked. Destructive actions (clearing the tower database, deleting
  trips, `git reset`) need explicit confirmation.
- The app is a research prototype, not a safety system — keep that wording in user-facing docs.
