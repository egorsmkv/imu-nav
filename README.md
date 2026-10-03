# IMU Nav (open source)

Car navigation that keeps working when GPS is **jammed or spoofed**. An open Kotlin
implementation of the approach used by the BlindDriver app: instead of trusting GPS, it
classifies every fix, and when GPS is unusable it dead-reckons **along the planned route** using
the phone's IMU, network location and map knowledge.

## First launch

The setup checklist appears once on new and existing installations after the onboarding update.
It offers precise location, notifications (Android 13+), physical activity (Android 10+), nearby
devices for Bluetooth OBD-II (Android 12+), and a separate battery optimization exemption. Grants
enable access; they do not turn on walking mode or connect an OBD adapter. The checklist also checks
the phone's Location switch and opens system settings if it is off. Disabling Location is supported
on Android 8–10 as well as newer phones; it does not stop the app or its inertial navigation. Declined permissions can
be enabled later, including through Android Settings when the permission dialog is no longer offered.

Choose the UI and voice language directly in onboarding: phone default, Ukrainian, English or Russian.
The selection is saved and the checklist stays open when the language changes.
The **Telegram group** link opens <https://t.me/imu_nav> from onboarding as well as Settings.

The bundled routing/address-search archive is **optional**: it is not unpacked until you tap **Install
built-in routing pack**, and the work then continues in the background. The built-in cell tower database
is optional too and is not unpacked until you tap **Install built-in towers**. Skip it to learn towers from trusted GPS and use your own sharing server; the checklist
links to **Settings → Cell towers → Sharing server**. Skipping towers does not block setup completion.
Existing tower data is preserved, and database resets only reinstall the archive after explicit opt-in.
Play builds can include the routing archive; F-Droid builds
require a routing pack imported or downloaded in Settings. Missing archives or denied permissions
do not block **Continue with limited functionality**, and preparation continues in the background.
Reopen the checklist from **Settings → Set up IMU Nav**. Previously removed routing packs stay removed
until explicitly reinstalled. IMU Nav is a research prototype, not a safety system.

## Network proxy

**Settings → Network proxy** supports the phone/system default (initial setting), explicit direct
connections, an HTTP proxy with optional Basic username/password, or an unauthenticated SOCKS proxy.
Enter a hostname/IP (not a URL) and port, then tap **Apply proxy settings**. Settings persist across
restarts and take effect for new online map/style/tile requests, route/search requests, archive and
tower downloads, and cell sync. Active downloads keep their existing connection/configuration.
A custom proxy failure never silently falls back to a direct connection. This is not a VPN: Android
location providers and other apps are unaffected. Offline features do not need a proxy.
HTTP proxy credentials are stored in app-private preferences, not encrypted by the app, and never
logged or included in exports. HTTP proxy authentication is not encrypted on the proxy connection;
use only trusted proxies. SOCKS authentication is not supported.

## How it works

The whole navigation state is one number, `s` — metres travelled along the route polyline.
Constraining the car to the route removes cross-track error and heading drift entirely; the
remaining along-track drift is repeatedly corrected by landmarks.

```
 GPS / NET / FUSED ─► TrustClassifier ─► GOOD / SUSPECT / BAD ──┐
 GnssStatus + AGC ──┘  (spoof & jam)                            │
 rotation vector,  ─► heading, vertical yaw rate, gyro bias,    │
 gyro, lin. accel      MotionDetector (stop / resume)           │
 OBD-II car speed ──► replaces the speed estimate (optional)    │
 barometer ─────────► height trace for terrain matching         │
                                                                ▼
                 NavigationEngine.tick() every 500 ms: route cursor s
                 GPS usable → project fix onto route (smoothed)
                 else       → selected fallback: dead reckoning, cell towers, or hybrid
                 hybrid corrections: turn-hold + gyro confirm, turn-signature matching,
                 compass snap, stop-at-signal snap, terrain (barometer ↔ route heights),
                 network band / catch-up / pull-back
                 deviation: GPS off-route, U-turn, missed turn, network off-route
```

| Component | File | What it does |
|---|---|---|
| Trust classifier | `core/.../gnss/TrustClassifier.kt` | Hard checks (BAD): mock, outside service area, altitude, >150 km/h, accuracy, clock skew, impossible jump, duplicate time, frozen coordinates, hard jamming, no satellites. Soft checks (SUSPECT): accuracy jump, speed ≠ displacement, disagreement with network fix, weak jamming, few sats, low C/N0, **flat C/N0 across satellites** (spoofer signature), GPS bearing vs. compass. |
| Jam detector | `TrustClassifier.kt` (`JamDetector`) | AGC hysteresis: enter < −12 dB, leave after 15 s > −8 dB. Re-injects A-GPS data on recovery. |
| Motion detector | `core/.../imu/MotionDetector.kt` | Stop = quiet accelerometer (mean & σ) or quiet gyro for 1.5 s; resume after 0.7 s of vibration; speed ramps 0.15→1 over 8 s after a stop. Integrates vertical yaw rate for turns. |
| Speed | `core/.../speed/Speed.kt` | Inverse-variance fusion of last GPS speed (σ grows with age), route prior (speed limit × learned driver ratio, or router's modelled speed), and **network speed** from a weighted linear regression of route-projected cell/Wi-Fi fixes. Speed caps near traffic signals / speed bumps. |
| Engine | `core/.../nav/NavigationEngine.kt` | Main loop, turn hold, corrections, deviation offers, uncertainty (`25 + 0.08·distance`, or `0.02·distance` with OBD-II speed, capped 350/600 m), voice announcements. |
| Native navigation core | `native/nav-core/`, `native/nav-jni/` | Rust GPS trust firewall, AGC jamming hysteresis, route projection, network/cell reachability gating, weighted speed regression and inverse-variance speed fusion, plus `[s, speed]` covariance with Joseph updates, innovation gating and a separate systematic-drift safety allowance. Android uses these native decisions through opaque JNI handles; the estimator runs in comparison mode while replay data validates its tuning. JVM replay keeps Kotlin implementations for parity testing. |
| Car speed (OBD-II) | `core/.../obd/Elm327.kt`, `app/.../obd/ObdLink.kt` | Reads vehicle speed (PID `010D`) 5× per second from a paired Bluetooth ELM327 adapter. While fresh it replaces the speed guess; a GPS/OBD scale factor is learned while GPS is trusted. |
| Terrain matching | `core/.../nav/ElevationMatcher.kt` | Keeps the last 1.5 km of (odometer, barometric height) and slides it along the route's elevation profile, trying odometer scales 0.75–1.35. Only a clear fit counts: ≥ 4 m relief, ≤ 2.5 m RMS misfit, and every other position ≥ 1.8× worse. It snaps the marker there (never across an unconfirmed turn) or confirms it, and shrinks the uncertainty. |
| Network gate | `core/.../nav/NetworkTracker.kt` | Feasibility gate for network fixes (reachable at 150 km/h) with re-anchoring. |
| Android glue | `app/` | `SensorHub` (LocationManager, GnssStatus, GnssMeasurements/AGC, sensors), `OsrmRouter`, foreground `NavService`, TTS, Compose + MapLibre UI. |

| Offline cell positioning | `core/.../cells/Cells.kt`, `app/.../cells/` | Scans visible cells (LTE/GSM/UMTS/NR) every 5 s, looks them up in an on-device SQLite tower database and computes a weighted-centroid fix (serving cell, signal strength and cell size as weights; outlier towers dropped; LTE timing advance bounds single-cell accuracy). Works without Google services or internet, and is immune to GNSS jamming. Fixes enter the engine as `CELL` and stand in for network location. |

### Offline routing
Routes are computed on the phone with **GraphHopper 11** from a *routing pack*: a road graph with
contraction hierarchies built on a computer from an OpenStreetMap extract. OSRM (online) is only a
fallback, and can be switched off in **Settings → Maps and route planning → Offline routing**. Packs also provide real speed
limits (OSM `maxspeed`) for the speed sign and the dead-reckoning speed prior.

Packs contain two profiles, **car** and **foot** (walking: footways, paths, steps, pedestrian
streets, preferring pleasant ways over busy roads). Choose *Car* or *Walk* in the "Where to?" panel
before starting. Walking routes are offline-only (the public OSRM server drives only). Packs built
before walking support (`pack.json` without `"profiles"`) still load; *Walk* is then disabled.

Build a pack (needs a desktop JVM; the full Ukraine takes a few minutes and ~10 GB RAM):
```bash
curl -LO https://download.geofabrik.de/europe/ukraine-latest.osm.pbf
./gradlew :routing:run --args="--osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine"
# → graph-ukraine/ and graph-ukraine.zip
```
**Road heights (for terrain matching):** add `--elevation skadi` to store a height for every road
point from a digital elevation model (AWS Terrain Tiles; `srtm`, `srtmgl1`, `cgiar` and `gmted` also work).
Tiles are downloaded once into `--elevation-cache <dir>` (default `elevation-cache/`; about 150 tiles, several GB
unpacked, for Ukraine). Bridges and tunnels get heights interpolated between their ends. `pack.json` then says
`"elevation":true`; older packs and online routes simply have no height profile, and terrain matching stays off.
```bash
./gradlew :routing:run --args="--osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine --elevation skadi --elevation-cache ~/dem-cache"
```

**Bundling a pack in a Play APK:** copy the zip and its metadata into the Play-only assets before building —
```bash
mkdir -p app/src/play/assets/routing
cp graph-ukraine.zip app/src/play/assets/routing/pack.zip
cp graph-ukraine/pack.json app/src/play/assets/routing/pack.json
```
On first start the app unpacks it in the background (~20 s for Ukraine) and uses it. It is only
reinstalled when an update ships a newer pack, and stays removed if the user removes it (Settings
offers *Install built-in routing pack*). The APK grows by the zip size (~400+ MB for Ukraine with car and
foot profiles and the search index, making the APK ~450+ MB — over Google Play's 200 MB base-APK limit, fine for
sideloading); both files are gitignored. The F-Droid flavor deliberately never bundles this locally
generated pack: users can import or download the same freely licensed data pack in the app.

Packs can also be installed at runtime with **Import pack** (the `.zip`) or **Download** from any HTTP(S) URL — the
zip is unpacked while it streams, and interrupted downloads resume. The graph is memory-mapped, so
large regions do not need a large heap.

Android cannot compile GraphHopper's custom models at runtime (Janino generates JVM bytecode), so
`PhoneGraphHopper` builds the same weighting from plain code; `GraphSpec` holds everything the
builder and the phone must agree on, and `OfflineGraphTest` checks both give identical routes.

### Address search
Routing packs also contain `search.db`, an SQLite FTS4 index of settlements, streets and house
numbers built from the same OSM extract (~87 MB for Ukraine; skip with `--no-addresses` or
`--no-search`). Queries like `Хрещатик 22`, `Київ Хрещатик`, `вул. Шевченка, Львів` or `Буча`
work offline in a few milliseconds; street-type words are ignored and results near you rank first.
The route panel uses the same text search for both the starting address and the destination; the
current trusted position remains the default start until the user chooses another one.
When the offline index finds nothing and online use is allowed (**Settings → Maps and route planning → Offline routing →
Allow online routing and search**), a [Photon](https://github.com/komoot/photon) geocoder is asked.
The public server `photon.komoot.io` is the default; **Settings → Maps and route planning → Address search** accepts your own
server instead (a host such as `http://192.168.1.10:2322` or the full `…/api` URL), with a *Test*
button that runs a sample query. Self-hosting keeps search text off third-party servers.

### Landscape driving and Android Auto

In a window at least 600 dp wide and wider than it is tall, driving controls use a left pane
capped at 320 dp and 40% of the width. Search and bookmarks open alongside the existing map;
its camera padding follows the measured panel. Shorter, narrow windows have expandable route
controls. Stop and Reroute remain accessible. Bookmark row menus contain endpoint selection,
rename and delete; compact route editors put bookmark saves under **More options**.

Both Play and F-Droid builds include projected **Android Auto** navigation using AndroidX Car
App 1.7.0 (host Car App API 7 or newer). This is not a standalone Android Automotive app.
Finish setup and install offline packs on the phone, then use the car screen to search addresses,
choose recent places or bookmarks, review a route and press **Start**. Use **Edit route** to choose
an explicit origin, or **Current position** for an automatic origin. Saved routes retain fixed
starts. Walking routes remain phone-only. Incoming `geo:` navigation intents open a preview or
search; they never start driving automatically. Stop the current trip before changing endpoints.

The phone and car share one engine, sensor subscription set, foreground service and recording.
The car shows maneuvers, remaining distance/time, arrival and positioning uncertainty, and offers
Stop, Reroute, pan, zoom and recenter. Disconnecting releases the car map while navigation continues
on the phone; reconnecting attaches to that trip. MapLibre draws through a virtual display directly
onto the host surface, sharing map styles and layers with the phone. Host visible/stable areas
control camera padding, and power profiles cap rendering rates. Android restrictions or missing
permissions produce a phone-setup action instead of starting an unprotected trip.

Android Auto host auto-drive mode simulates a reviewed route in the car session only. It is labelled
**DEMO**, does not inject fixes or write trips/learning data, and ends on Stop or session teardown.
A real trip started on the phone supersedes the demo. Release builds validate hosts; debug and
benchmark builds permit development hosts. IMU Nav remains a research prototype, not a safety system.

For device validation, use a benchmark build (debug GraphHopper cannot load routing packs) with
Android Auto's Desktop Head Unit. Exercise phone-visible/hidden, connect/disconnect/reconnect,
GPS loss/manual origin, saved fixed-origin routes, reroute/arrival, surface resize/day-night changes,
missing location permission, and host auto-drive mode. Rotate the phone and test split-screen,
large fonts and the keyboard while search/bookmarks are open. Car model instrumentation tests:

```bash
./gradlew :app:connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.car.CarGuidanceTest
```

### Bookmarks

Tap the bookmark button on the map for **Places** and **Routes**. Save a search result with its
bookmark button, or use **Save start**, **Save destination** and **Save route** in the route editor.
Long-pressing the map selects a destination that can then be saved. Give each bookmark a name;
the library supports filtering, renaming and confirmed deletion. Saved places also appear above
recent searches and can fill either route endpoint without an address-search pack or network.

A saved route remembers the endpoints and car/walking mode, then calculates a fresh preview when
opened. **My location** remains automatic and uses the permitted position available at that time;
a manually selected start stays fixed. Without a usable position, select a manual start or wait for
positioning. Opening a bookmark never starts navigation. Stop an active trip before applying a
bookmark; browsing and management remain available during navigation.

Bookmarks persist locally in a separate SQLite database and survive routing-pack replacement.
Saved routes contain their own endpoint copies, so renaming or deleting a place does not change
any route. There is no synchronization, file import/export or backup; existing Android backup
exclusions apply. This remains a research prototype, not a safety system.

### Trips: history, recording, restore and replay
Every navigation is recorded to `files/trips/trip-<time>-<uuid>.rec.gz` in app storage — all fixes, IMU
samples, satellite/AGC status, routes and the engine's own estimates (gzip text, flushed every 2 s).
Opening, repairing, writing, closing and discarding recordings share one worker. Rapid stop/start
actions use separate files; older timestamp-only filenames remain readable.

- **History** (clock icon on the map) lists trips with totals; a trip shows its trusted-GPS track and
  the engine's estimate on a map, distance, duration, moving time, time/distance without GPS and the
  largest uncertainty. *Snap to roads* map-matches the drive with GraphHopper.
- **Share a trip:** open it in History and tap the share icon. Android's share menu sends a separate
  `.rec.gz` copy containing the route, positions and recorded sensor events, usable by the replay tool.
  The original stays in app storage. Preparing the attachment runs in the background; missing files
  or insufficient storage produce an error without changing the trip.
- **Surviving the app being killed:** the active trip is saved every 10 s. If Android or the user kills
  the app mid-trip, the next start (within 3 h) restores the route and position — widening the
  uncertainty for the time lost — restarts the foreground service and keeps recording into the same
  file (a recording cut off by the kill is salvaged first). A `Q` event records the restored distance
  along the route after the new start and route events. Kotlin and native comparison replay reset
  transient sensor state and use that distance and the restored uncertainty. Kotlin also marks
  already passed route turns as consumed. Older recordings without `Q` retain their existing replay
  behavior. Accept the battery-optimisation exemption
  when offered (**Settings → Everyday settings → Battery**) so this is rare.
- **Replay tool:** re-runs recordings through the engine on a computer, optionally hiding GPS after
  N seconds, and compares the engine against the real (trusted GPS) track:
  ```bash
  ./gradlew :replay:run --args="path/to/trips --hide-gps-after 60,300 --out replay-out"
  ```
  `--set key=value,...` overrides `Tuning` fields and `--ukraine` enables the service-area check.
  It writes `summary.txt` (median / p95 / max error with and without GPS), `errors-*.csv` per trip and
  `compare-*.geojson` (real vs. engine tracks) for any GeoJSON viewer.
- **Native estimator comparison:** build the host JNI library and score the Rust estimator and
  Kotlin engine at the same trusted GPS timestamps (no Android SDK needed):
  ```bash
  ./gradlew -PnativeReplay :replay:run --args="path/to/trips --compare-native --hide-gps-after 30,120,300 --out native-replay-out"
  ```
  Each value starts a separate run that hides GPS after that many seconds until the trip ends.
  Reference GPS is classified separately and never enters either navigation estimator or its
  gyro-bias learner during the hidden interval. Reports include paired errors and native safety-radius
  coverage in `summary.txt` and `native-errors-*.csv`. Reference GPS is not survey ground truth;
  route projections can be ambiguous on repeated sections. This stricter comparison has different
  sampling/hidden-GPS semantics from the legacy replay, so their error figures are not interchangeable.
  On Linux, add `-PnativeHeapProfile` and `--native-heap-profile heap-profiles` to capture sampled Rust heap
  profiles during `--compare-native` replay; see [native profiling](native/README.md#native-heap-profiling-linux-host-replay).
  For deterministic app-like native workloads without recordings, use the [simulation and profiling crate](native/nav-sim/README.md).
  Its twelve scenarios cover GPS loss/delay, walking, session lifecycles, dense OBD inputs, curved routes,
  crossings, parallel returns and global reacquisition; `--scenario advanced` selects the five focused stress cases.
  Rust 1.99+ is required; `:replay:test` also builds the host library and runs synthetic JNI comparisons.
  Native car dead reckoning now uses the shared IMU stop/resume detector when fresh GPS/OBD speed
  and confident cell movement do not contradict it. Add `--no-native-motion` to disable these hints
  for an A/B comparison. Hints expire with the IMU samples; they are speed-model assumptions, not
  position anchors. Quiet highway travel without independent movement evidence remains ambiguous.
  After a false stop, returning GOOD GPS or OBD speed can also be checked against the saved cruising
  speed, so valid highway-speed readings are not locked out by the near-zero stop model. Both models
  retain innovation gates; recovering speed does not erase accumulated position error.
  **Settings → Everyday settings → Navigation without GPS → Navigation estimator** now offers
  **Kotlin (default)** and **Native Kalman (experimental)**. Select before starting navigation;
  changes are locked during a trip. Native mode owns position, speed, uncertainty and the
  state used by guidance for both driving and walking. Walking uses step cadence × learned stride;
  only fresh GOOD GPS whose position and speed were accepted can calibrate that stride. Fresh GPS
  speed takes priority. Without a step sensor, fresh IMU permits an uncertain typical-pace model;
  without either input or fresh GPS, native walking holds instead of assuming continued movement.
  Step/IMU expiry also bounds prediction across long tick gaps. OBD, car turn matching and coarse
  cell-position/speed corrections remain car-only. Existing step events (`P`) feed walking replay;
  `--no-native-motion` disables car stop/resume hints, not walking step evidence.
  Native mode replaces the Kotlin fallback method
  and its terrain/compass/signal snaps; shared guidance and GPS/network deviation checks remain.
  This is a route-constrained linear Kalman filter, not a full inertial EKF. Better real-drive accuracy
  is not yet established; the app remains a research prototype, not a safety system.
  The choice is persisted with the active trip and recorded as a `K` event. During asynchronous
  native restoration, navigation holds position and suppresses guidance until the estimator is ready.
  Legacy Kotlin-only replay rejects native-selected trips instead of silently testing the wrong
  algorithm; use the paired `--compare-native` command above to evaluate their raw inputs (this is
  an A/B experiment, not exact reproduction of live guidance). Cell-speed learning remains off.
  With Kotlin selected, the native estimator still runs only as a shadow comparison.
  A separate **Inertial ESKF shadow (experimental)** switch in the same settings section is off by
  default and locked during navigation/planning. It runs alongside either position owner, **only for
  mounted-phone driving**; it never changes the marker, route, speed display or guidance. Walking is
  deliberately excluded. The implementation is platform-independent Kotlin in `core/.../imu/eskf/`,
  not a replacement for the Rust route Kalman filter. It estimates 3-D position, velocity, quaternion
  attitude, accelerometer bias and gyro bias with a 15-dimensional error covariance. Prediction uses
  gravity-inclusive body acceleration and body angular rate, with Joseph measurement updates and
  right-multiplicative attitude-error injection/reset
  ([mathematical conventions](https://arxiv.org/abs/1711.02508)).

  The experiment requires fresh GOOD GPS (including speed), rotation vector, accelerometer and gyro
  to initialize; a manual start cannot initialize it. The Android rotation vector supplies the initial
  attitude only, corrected from magnetic to true north using declination anchored once per capture
  session to fresh GOOD GPS. Further orientation observations are not fused as independent measurements.
  Raw sensors use each power profile's existing rate; extra accelerometer capture increases battery
  and recording size. The new `U` event preserves sensor nanoseconds, callback arrival milliseconds,
  full quaternion and unrounded sensor values through `PositioningHub`. Existing `I` recordings cannot
  reconstruct these inputs. `eskf_shadow` log lines report status, accepted GPS, rejected inputs,
  resets and diagnostic sigma. Process restoration starts a fresh shadow requiring a new GPS anchor.

  Inputs are reordered within 250 ms; later GPS is rejected rather than applied at the wrong time.
  Missing/stale sensors or gaps over 250 ms invalidate the state and require reinitialization. The
  first version uses fixed local ENU gravity, approximate flat-earth coordinates and provisional
  noise densities. It has no Earth-rate/lever-arm/scale model, vehicle-frame constraints, OBD updates,
  terrain/route snaps or false-stationary zero-velocity updates. Course accuracy is not yet recorded,
  so horizontal GPS velocity uses a conservative noise floor. Phone movement, magnetic disturbances,
  long outages and unobservable biases can still cause severe drift. Sigma is **not a safety radius**.
  Synthetic tests validate mechanics, not improved real-drive accuracy: this remains a research
  prototype, not a safety system.

  For a new raw-sensor recording, run:

  ```bash
  ./gradlew -PnativeReplay :replay:run --args="trip.rec.gz --compare-eskf --hide-gps-after 30,60,120 --out eskf-report"
  ```

  This reports **horizontal** errors for Kotlin, native route Kalman and ESKF on identical available
  reference timestamps, plus missing pairs, rejected inputs and resets. The ESKF branch preserves
  arrival order and the live reorder window; hidden GPS cannot initialize it or train its biases.
  Scoring may align a preceding inertial estimate by at most 100 ms (the saver sensor period) of its own velocity; no future
  estimate is used. Startup/dropout/final-window gaps are reported, not scored as zero error.
  Reference GPS is not survey ground truth, and the baselines remain an A/B replay rather than exact
  live guidance reproduction. `--compare-eskf` cannot be combined with native-only comparison flags.

  The native route estimator also applies small cell/network position corrections after three distinct, consistent fixes
  spanning at least ten seconds. Cached fixes, ambiguous route matches, stale or very coarse fixes,
  and large discrepancies are excluded. Corrections preserve a coarse uncertainty floor and do not
  reset drift or change speed. Add `--no-native-network` to isolate their effect in paired replay.
  An opt-in `--native-network-speed` experiment reserves separate cell fixes for conservative speed
  regression. It reduces drift after cell coverage disappears in a synthetic steady-speed drive.
  When consecutive cell intervals contradict the learned speed and favour its earlier moving-speed
  prior, it retracts that learning and temporarily restores full-rate position corrections. This reduces
  abrupt-change lag, but peak error and small speed changes remain limitations requiring real-drive tests.
  Changes away from the saved prior can also trigger cautious relearning from a coherent new window;
  mixed windows are discarded in favour of position corrections. Sparse cell updates and slowly changing
  tower bias still cause large errors in synthetic tests, so this is not a general accuracy improvement.
  It is **off by default**, including the app comparison path; neither experiment controls live navigation.
  Completed IMU rotations can also give a bounded correction at an isolated, distinctive route
  turn. The native matcher rejects ambiguous matches and limits each correction to 30 m, retaining
  uncertainty and drift. `--no-native-turns` disables this independently for A/B replay. Smooth
  phone rotation while driving can still mimic a turn; this remains experimental, comparison-only.

### Cell tower database

Cell positioning preserves negative dBm signal readings so stronger cells receive more weight;
Android's unavailable signal values are treated as missing.
Cells with missing, future or more than 10-second-old modem timestamps are excluded from positioning
and tower learning. Multi-SIM scans keep the newest measurement for each cell. Fix timestamps reflect
the oldest contributing measurement, and repeated cached measurements do not create new fixes or
usage-history entries. Scan frequency still follows the selected power profile.

Tower locations come from four sources, each in its own table and looked up in this order:

| Source | How it gets there |
|---|---|
| **Sync server** | Merged data downloaded from a cell-sharing server you configure (see below). |
| **OpenCellID** | *Download OpenCellID* with your own API token, or *Import file* (`.csv` / `.csv.gz`). CC BY-SA 4.0. |
| **Learned** | While GPS is GOOD (≤ 30 m), every visible cell's position is refined from the fix. |
| **Mozilla** | *Download Mozilla data* streams the Mozilla Location Service final export (1.5 GB, public domain, March 2024) from archive.org, keeping only your region's MCCs; resumes after network drops, nothing large is stored. |

| **Built-in** | Shipped inside the APK (`app/src/main/assets/cells/bundled-cells.csv.gz`, ~530k Ukrainian towers compiled from OpenCellID + Mozilla). Optional: choose **Install built-in towers** in onboarding (or reopen **Settings → Set up IMU Nav**) to import it (~20 s). After opting in, changed archives are imported on app updates. Lowest priority, so anything downloaded later wins. |

All imports are filtered to the configured country codes (default `255`, Ukraine).

**Usage history:** **Settings → Cell towers → Cell tower usage history** shows the latest 20 tower
contributions and can share the complete history as a UTF-8 CSV file through Android's share sheet.
Recording starts with this version and runs whenever cell scanning produces a fix, including outside
active trips. Each scan records only towers actually used after outlier filtering, not unknown or
disabled cells. The CSV includes app-session id, Unix/elapsed timestamps in ms, radio, MCC/MNC,
area/cell id, signal, serving flag, timing advance, tower geometry and the estimated fix/accuracy.
Coordinates can reflect a cell-id or site match rather than an exact tower match. History persists
across app restarts and positioning-database resets; old trips cannot be reconstructed retroactively.
It stays on the device unless you explicitly share it. The file reveals approximate locations and
times: share only with trusted recipients.

#### Updating the built-in database
1. On a phone with the data you want (after *Download Mozilla data* / *Download OpenCellID* / syncs), open **Cells → Export database**.
   It writes every tower once — choosing the entry lookups would use — to `Android/data/org.imunav.app/files/cells-export.csv.gz`.
2. Copy it into the project and rebuild:
   ```bash
   adb pull /sdcard/Android/data/org.imunav.app/files/cells-export.csv.gz app/src/main/assets/cells/bundled-cells.csv.gz
   ./gradlew :app:assembleRelease
   ```
3. Installed apps re-import it once after updating (detected by the file's SHA-256).

The compiled file contains OpenCellID data and is therefore distributed under CC BY-SA 4.0 (see `assets/cells/LICENSE.txt`).

### Cell-sharing server
Phones upload towers they learned from trusted GPS — tower positions only, never the device track —
and download everyone's merged data. Protocol (gzip CSV in OpenCellID columns):

- `POST /v1/cells` — upload; `Authorization: Bearer <key>` if the server has an API key
- `GET /v1/cells.csv.gz?mcc=255&since=<epoch seconds>` — incremental download
- `GET /v1/towers` plus `PUT` / `DELETE /v1/towers/{radio}/{mcc}/{mnc}/{area}/{cid}` — JSON management API
- `GET /v1/events` — WebSocket stream of tower upserts and deletions for realtime management tools
- `GET /admin` — read-only, server-rendered management dashboard and tower details
- `GET /health`

The Rust reference server in `server/` persists per-device contributions and materialized consensus
in SQLite (WAL mode), so state survives restarts and reads continue during uploads. It is built to
resist **poisoning** (a phone or a script uploading fake tower positions):

- Every upload carries an `X-Device-Id`; contributions are stored per device (at most 50 samples each).
- A tower's position is a **one-device-one-vote weighted median**, so one device cannot outvote others
  by uploading many samples; positions far from the consensus (MAD-based) are dropped as outliers.
- A new tower is published only after `--min-devices` (default 2) independent devices agree, or if it
  came from the seed import (which counts as a strong vote).
- Rows outside the service area, jumps > 5 km from the consensus, and absurd ranges are rejected.
- Rate limits per device and per IP, and a cap on new device ids per IP per day.

```bash
cargo build --release --manifest-path server/Cargo.toml
server/target/release/imu-nav-cell-server --port 8080 --data cells.sqlite3 [--api-key KEY] \
    [--min-devices 2] [--max-samples 50] [--area ukraine|any] [--trust-proxy]
# optionally seed it once from an export, e.g. OpenCellID/Mozilla filtered to Ukraine:
server/target/release/imu-nav-cell-server --data cells.sqlite3 --import 255.csv.gz --mcc 255
```

Put it behind a TLS reverse proxy for use outside your own network. The app warns when an API key
would travel over plain `http://`. The old Kotlin server's internal contribution gzip is not a
SQLite migration source; re-import the original seed export when moving to this server. See
[`server/README.md`](server/README.md) for the complete HTTP, management, and WebSocket API.
In the app: **Cells → Sharing server**, enter the URL (and key), then *Sync now* or enable automatic sync (every 6 h and after trips).

Main navigation thresholds live in `core/.../Tuning.kt` (defaults = factory preset) and `TrustConfig`.
The separate inertial experiment keeps its provisional noise densities in `InertialTuning`.

### Turn hold — the key trick
When the dead-reckoned marker reaches the next real turn (≥ 35°) it is parked 5 m before it.
It is released when the **gyro confirms the turn** (same direction, ≥ 60 % of the angle) → snap
to turn + 20 m; when two good network fixes are clearly past the turn; after a timeout; or — if
you drive on without turning — a "missed turn" reroute countdown starts. Independently, any clear
gyro turn is matched against route turns 400 m behind … 300 m ahead (turn-signature map matching).

## Build

Runs on Android 8.0 (API 26) and newer; targets Android 16 (API 36). Build requirements: JDK 17+,
Android SDK 36, the Android NDK and Rust 1.85+ (edition 2024) with the `aarch64-linux-android`,
`armv7-linux-androideabi` and `x86_64-linux-android` targets. Set `ANDROID_NDK_HOME` when the NDK
is outside the Android SDK. Android builds compile and package the Rust estimator automatically.

```bash
./gradlew test                # Kotlin engine, routing/search and replay tests
./gradlew :app:assemblePlayDebug    # normal development build
./gradlew :app:assembleFdroidRelease # unsigned F-Droid release build
./gradlew :app:assemblePlayBenchmark # release speed + freeze diagnostics, installs as org.imunav.app.bench
cargo test --manifest-path native/Cargo.toml # native estimator and covariance tests
cargo test --manifest-path server/Cargo.toml # persistent HTTP/WebSocket cell server tests
```

**Native formal verification.** Run `python3 tools/verify_native.py` after installing pinned Kani 0.68.0.
The PR check verifies finite-state guards, bounded estimator rollback, and filter, trust, jamming
and network contracts; see
[proof scope, setup and counterexample reproduction](docs/NATIVE_VERIFICATION.md).

**Rust coverage.** Linux host coverage combines Rust tests, actual JVM JNI calls (default and
`heap-profile` builds), simulator subprocesses and server CLI/HTTP/WebSocket tests. Run
`python3 tools/rust_coverage.py` after installing the pinned coverage toolchain. CI gates
production lines per crate; see [coverage setup, thresholds and report semantics](docs/RUST_COVERAGE.md).

**Kotlin coverage.** Run `python3 tools/kotlin_coverage.py` for host coverage of core, routing,
replay and app unit tests. CI enforces reviewed per-module baseline floors, with shared JNI
wrapper sources counted once. See [setup, reports and baseline updates](docs/KOTLIN_COVERAGE.md).

**Responsiveness.** Debug and benchmark builds enable StrictMode and a main-thread watchdog
(`MainThreadWatchdog`): any UI-thread task longer than 200 ms is logged with its stack under the
`MainThreadWatchdog` Logcat tag. Benchmark builds are R8-shrunk, so read their stacks with R8 retrace
and `app/build/outputs/mapping/playBenchmark/mapping.txt`. Text-to-speech, sensor registration, cell
scans, trip saving and history I/O run on background threads, and the map view stays loaded while
Settings or History is open, so returning to the map is instant.

The `play` and `fdroid` distribution flavors currently use the same FOSS application code and
dependencies. The flavor boundary prevents future Play-only SDKs from leaking into F-Droid, and an
automated dependency check rejects common proprietary/tracking SDK groups. F-Droid builds exclude
the optional untracked routing bundle and do not read the developer signing key. See
[`docs/FDROID.md`](docs/FDROID.md) for release, validation and submission instructions.

Public donation destinations are configured in `links.properties` at the repository root:

```properties
monobankDonationUrl=
privatbankDonationUrl=
```

Set each value to its public donation-page URL. Only HTTPS URLs are accepted; an empty value hides
that bank's row in **Settings → About**. The Telegram group link in the same section always opens
<https://t.me/imu_nav>.

The app uses MapLibre's OpenGL renderer for the broadest device compatibility. GPS, gyroscope,
compass and step-detector hardware are optional install-time features; missing sensors reduce
navigation accuracy or disable their corresponding corrections rather than blocking installation.

### Code checks

Contributor (and AI coding agent) guidelines — conventions, architecture rules, known pitfalls and
the definition of done — are in [`AGENTS.md`](AGENTS.md).

```bash
./gradlew check           # everything below plus all tests — run before sending changes
./gradlew spotlessApply   # auto-format Kotlin and Gradle files (ktlint)
```

| Tool | Task | Config |
|---|---|---|
| **ktlint** (via Spotless) — formatting and style | `spotlessCheck` / `spotlessApply` | `.editorconfig` (IntelliJ style, 180 columns) |
| **detekt** — complexity, exception handling, naming, bug patterns | `detekt` | `config/detekt.yml` (defaults + documented adjustments) |
| **Android Lint** — API levels, resources, translations, Compose, manifest | `:app:lintFdroidRelease` / `:app:lintPlayRelease` | `app/lint.xml`; warnings are errors |

Reports land in `*/build/reports/detekt/` and `app/build/reports/lint-results-<variant>.html`.
Exceptions are kept few and commented where they are configured; prefer fixing over suppressing.

Play release builds are signed from `keystore.properties` in the project root (gitignored):

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=blinddriver
keyPassword=...
```

Keep a backup of the keystore: Android only installs updates signed with the same key.
Without `keystore.properties`, `assemblePlayRelease` produces an unsigned APK. The
`assembleFdroidRelease` task is always unsigned so F-Droid can apply its repository signing key.

### Car speed and hills
Two optional inputs make dead reckoning much more accurate (**Settings → Everyday settings → Car speed and hills**):

- **OBD-II adapter.** A cheap Bluetooth ELM327 dongle in the car's diagnostic socket (under the dashboard,
  every petrol car since ~2001 and diesel since ~2004). Pair it in the phone's Bluetooth settings, switch
  on *Car speed from OBD-II adapter*, pick it and tap *Test connection* (ignition on). The app then connects
  when a car trip starts, reads the speed 5× a second and reconnects by itself; the status pill shows
  *Estimated + car speed*. Only the standard speed request is sent: nothing is written to the car. Classic
  Bluetooth adapters work; BLE-only ones do not. Android 12+ asks for the *Nearby devices* permission.
- **Barometer + road heights.** Phones with a barometer measure height changes to well under a metre.
  With a routing pack built with `--elevation`, the engine compares them with the route's hills and
  corrects the position every few seconds on hilly roads (flat roads give no correction by design).

Both are recorded in trip recordings (`V` and `B` lines), so the replay tool reproduces them.

### Walking without GPS
On foot the engine replaces the car speed model with the phone's **step detector**: speed = steps
per second × stride, and the stride (0.72 m to start) is learned while GPS is trusted. Car-only
corrections are off (turn hold, gyro turn matching, compass snap, U-turn and missed-turn detection,
traffic-signal and speed-bump rules — they assume a phone fixed in a car holder), off-route and
arrival distances are tighter (`Tuning.forWalking()`), and maneuvers are announced at 150 / 50 / 15 m.
Cell-tower and network corrections apply when the Hybrid fallback is selected. Step counting needs the *Physical activity*
permission (asked when choosing *Walk*); without it or without a step sensor, a 1.3 m/s pace is
assumed while the phone is moving. Walk recordings contain the steps, so the replay tool works for them too.

Usage: search for the **From** and **To** addresses in the route panel, or long-press the map to
choose a destination. The proposed route is drawn on the map as soon as its start and destination
are known; review it, then tap **Start**. The current trusted position is used when **From** is not
changed. Planning uses GOOD GPS up to 5 seconds old, otherwise network/cell location up to 30 seconds
old; expired or future-dated fixes cannot supply an automatic start. A manual start takes precedence.
If there is no fresh automatic position, search for the starting address or pan the
crosshair onto your position and tap **Start here** first. Tap the status pill for
positioning diagnostics (satellites, spoofing reasons, cells, *Simulate GPS loss*, trip log); the gear
opens **Settings**. Its four expandable groups keep common controls separate from maps, cell-tower
data and advanced tools. **Everyday settings → Navigation without GPS** selects dead reckoning only, cell-tower
positions only (held between scans), or the recommended hybrid that dead-reckons continuously and
uses cell/network fixes to constrain drift. Trusted GPS remains preferred in all three modes.
Each group starts with a short explanation; the map group also distinguishes routing packs (which
calculate routes) from map packs (which draw streets). Pack imports can be cancelled during extraction
and validation. Once replacement starts, it finishes or rolls back to the previous pack, and reports
the actual result. A new import waits until cleanup finishes. Routing, map matching and address
search retain their pack resources until each operation completes, before replacement can close them.
The map opens at the phone's last GPS position (spoofed or out-of-area fixes are ignored) or, if set in
**Settings → Everyday settings → Map start**, at a fixed place (typed coordinates, your position or the map centre).
**Settings → Everyday settings → Navigation without GPS → Haptic feedback** disables both navigation
vibrations and app tap feedback. The preference is saved, applies immediately and cancels active
navigation vibrations; it remains visible even on devices without a vibrator. When enabled, tap
feedback still follows Android's touch-feedback setting. Vibration service calls run off the UI thread.
The interface and voice follow the phone's language (Ukrainian, English or Russian) unless changed in
**Settings → Everyday settings → Language**, and the phone's light/dark theme. Spoken directions can be disabled under
**Settings → Everyday settings → Navigation without GPS**. The phone vibrates as well, with a different
pattern for an upcoming turn (one buzz), the turn itself (two), leaving the route (three short), GPS lost
(long + short), GPS back, a new route and arrival, so alerts can be told apart without looking. Turn that off with
**Vibrate on turns and alerts** in the same group. Start, Stop, the Car/Walk selector and choosing a destination
also give a short tap of feedback, following the phone's touch-feedback setting. Map and settings layouts adapt to portrait and landscape.
Map panels scroll when text does not fit, preserving space for zoom controls; long position labels
shorten to keep History and Settings accessible, including with enlarged system text. In short windows,
map warnings and controls scroll separately from the route panel; the compass stays clear of the zoom buttons.
Action buttons, travel modes, legends and trip statistics wrap to the next line when needed. Settings,
address search and the trip log keep content above the keyboard, and the setup and log control panels
limit their height so the main content remains reachable. System font scaling is preserved. Text trip logs are written to
`files/logs/`, trip recordings to `files/trips/` in app storage. Log writes and trip boundaries share one worker, so queued messages stay with their original trip.
Log files have unique suffixes and buffered writes flush within five seconds (or at rotation/trip end); an abrupt process kill can lose the last buffered lines. The in-app trip-log viewer shows timestamps, highlights problems, and can search,
filter, follow, copy or share the latest diagnostic events as a text file.

### Kotlin lifecycle and performance regression checks

Navigation start, reroute and restoration share cancellable request ownership: a stopped or superseded
request cannot replace a newer trip, and failed startup releases partially initialized components.
Location-provider status is cached for UI refresh and checked on the sensor worker before starting.
Phone and Android Auto share asynchronously loaded, serialized recent-search history; malformed rows
are skipped without discarding valid entries. Native network sample snapshots are reused between
mutations. Android Auto publishes changed display fields and defers map updates while its surface is
paused, applying current state on resume; stationary ETA is refreshed at least once per minute.

`./gradlew :core:test :app:testPlayDebugUnitTest :replay:test` covers queued logging boundaries,
startup rollback and request cancellation, history concurrency, provider caching, car publication
inputs, and real JNI sample parity. `RecentSearchCodecTest` uses Android instrumentation to verify the
platform JSON parser. Use `assemblePlayBenchmark` on a device with an Android Auto host to check
main-thread reports, visibility transitions, frame timing and JVM allocations. Native heap profiles
measure Rust allocations separately; they do not measure ART object allocation.

### Bookmark storage tests

Bookmark database instrumentation tests (requires an Android SDK/NDK and an emulator/device):

```bash
./gradlew :app:connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.imunav.app.bookmarks.BookmarkDatabaseTest
```

These tests use an isolated database and do not load routing packs. Use a benchmark APK for manual
bookmark-to-routing checks with a real pack, as with all GraphHopper device testing.

## Status and limitations

- Walking mode is new and tuned only on simulations; record a few walks (with GPS) and check them
  with the replay tool before relying on it.

- Offline routing packs carry no traffic-signal data yet, so the stop-at-signal snap and signal
  speed plan stay inactive. The OSRM fallback uses the public demo server — self-host it for real use.
- The service area defaults to a coarse Ukraine outline (`ServiceArea.UKRAINE_COARSE`); fixes
  outside it are rejected as spoofed. Change it in `AppGraph` to use the app elsewhere.
- Free-drive (no route) dead reckoning and speed cameras are not implemented yet;
  `Tuning` already carries the camera parameters.
- The engine differs from the analysed app in a few places: the gyro bias estimate is applied to
  turn integration, and projections compute exact arc-length.
- Not road-tested. Treat as a research prototype, never as a safety system.

## Licence

Source code: MIT (see `LICENSE`). Bundled data keeps its own licences — OpenStreetMap-derived
routing/search packs are ODbL, the cell database is CC BY-SA 4.0; see `NOTICE.md`.

### Libraries and licences

Every library below was read from the resolved dependency graph (`./gradlew :app:dependencies`
etc.) and its licence from the published POM. All are compatible with this project's MIT licence.

**Shipped in the app (APK)**

| Library | Version | Used for | Licence |
|---|---|---|---|
| Kotlin standard library | 2.3.21 | language runtime | Apache 2.0 |
| IMU Nav native estimator | 0.1.0 | route-state estimation and covariance math | MIT |
| Rust `jni` crate | 0.21.1 | checked JNI access for the native estimator | MIT / Apache 2.0 |
| kotlinx.coroutines | 1.11.0 | background work, flows | Apache 2.0 |
| AndroidX Car App (`app`, `app-projected`) | 1.7.0 | Android Auto templates, navigation and surface lifecycle | Apache 2.0 |
| AndroidX Core KTX, Activity Compose, Lifecycle (runtime-compose, service) | 1.18 / 1.13 / 2.10 | Android integration | Apache 2.0 |
| Jetpack Compose (BOM 2026.06.01: UI 1.11, Material 3 1.4) + Material Icons Extended 1.7.8 | — | user interface | Apache 2.0 |
| MapLibre Native for Android (+ GeoJSON, Turf, Gestures) | 13.6.1 | map rendering | BSD 2-Clause |
| OkHttp / Okio | 5.4.0 / 3.17.0 | all HTTP (sync, downloads, OSRM, Photon; also MapLibre's tile loading) | Apache 2.0 |
| GraphHopper core + map matching | 11.0 | offline routing, snapping trips to roads | Apache 2.0 |
| ↳ HPPC | 0.8.1 | GraphHopper's primitive collections | Apache 2.0 |
| ↳ Jackson (core, databind, XML) + Woodstox 7.1 | 2.19.2 | GraphHopper's config / JSON | Apache 2.0 |
| ↳ Stax2 API | 4.2.2 | XML parsing (Jackson XML) | BSD 2-Clause |
| ↳ JTS Topology Suite | 1.20.0 | geometry in GraphHopper | EPL 2.0 / EDL 1.0 (dual) |
| ↳ Janino + commons-compiler | 3.1.9 | GraphHopper custom models (desktop only; the phone uses plain code) | BSD 3-Clause |
| ↳ osm-legal-default-speeds | 1.4 | default speed limits per road type | BSD 3-Clause |
| ↳ Apache XML Graphics Commons, Commons IO | 2.7 / 1.3.1 | GraphHopper utilities | Apache 2.0 |
| ↳ SLF4J API | 2.0.17 | logging facade (no backend on Android) | MIT |
| ↳ Gson, Timber, JSpecify, JetBrains annotations, Guava listenablefuture | — | pulled in by MapLibre / AndroidX | Apache 2.0 |

The APK contains no copyleft code. GraphHopper's `.osm.pbf` reader needs `osmosis-osm-binary`
(LGPL 3.0) and Protocol Buffers, but only when importing OSM data, which the phone never does, so
`app/build.gradle.kts` excludes both from the app (verified: only `com.graphhopper.reader.osm.pbf.*`
uses them).

**Desktop tools only** (`:routing` pack builder, Rust `server/`, `:replay`; not in the APK): the same
GraphHopper stack plus

| Library | Version | Used for | Licence |
|---|---|---|---|
| osmosis-osm-binary | 0.48.3 | reading `.osm.pbf` extracts | LGPL 3.0 (used unmodified, as a separate jar) |
| Protocol Buffers (Java) | 3.12.2 | `.osm.pbf` decoding | BSD 3-Clause |
| SQLite JDBC | 3.53.4.0 | writing the search index | Apache 2.0 |
| Axum + Tokio | 0.8 / 1.x | cell server HTTP/WebSocket runtime | MIT |
| tikv-jemallocator / jemalloc | 0.7 / 5.3.1 | optional Linux native replay heap allocator | MIT / Apache 2.0; jemalloc BSD 2-Clause |
| pprof-rs | 0.15 | native simulation CPU profiles and flamegraphs (desktop only) | Apache 2.0 |
| jemalloc_pprof | 0.9 | optional Linux native heap export to pprof | Apache 2.0 |
| Rusqlite + SQLite | 0.37 / bundled | persistent cell server database | MIT / public domain |

The sharing server also uses Serde, CSV, Flate2, Clap and Tracing (MIT or MIT/Apache 2.0).
The desktop simulation uses Clap, Serde/serde_json, Anyhow, Flate2 and tikv-jemalloc-ctl
(MIT or MIT/Apache 2.0); these dependencies are not packaged in the APK.

**Build and development tools** (not distributed)

| Tool | Version | Licence |
|---|---|---|
| Android Gradle Plugin, Android Lint | 9.2.0 | Apache 2.0 |
| Kotlin compiler / Gradle plugin, Compose compiler | 2.3.21 | Apache 2.0 |
| Gradle | 9.6.1 | Apache 2.0 |
| detekt | 2.0.0-alpha.6 | Apache 2.0 |
| Spotless | 8.10.3 | Apache 2.0 |
| ktlint | 1.8.0 | MIT |
| JUnit 4 | 4.13.2 | EPL 1.0 |
| kotlin-test | 2.3.21 | Apache 2.0 |

**Data and online services**

| Source | Used for | Terms |
|---|---|---|
| OpenStreetMap | routing / search packs, map tiles | © OpenStreetMap contributors, ODbL 1.0 |
| OpenFreeMap | vector map tiles and styles | free public service; data © OpenStreetMap |
| OpenCellID | tower positions (download with your own token; part of the built-in database) | CC BY-SA 4.0 |
| Mozilla Location Service final export | tower positions (built-in database, optional download) | public domain (CC0) |
| OSRM demo server | online routing fallback (only if allowed in Settings) | free for light use; self-host for real use |
| Photon (komoot) | online address search fallback (only if allowed) | free public service; data © OpenStreetMap |


Android car instrumentation tests also use AndroidX Car App Testing 1.7.0 (Apache 2.0).
Android bookmark instrumentation tests additionally use AndroidX Test Runner 1.6.2 and AndroidX
JUnit extensions 1.2.1 (Apache 2.0); these are test-only dependencies and are not included in the app.

## Provenance

Written from scratch based on a behavioural analysis of BlindDriver 0.4.0 (algorithms,
thresholds and log vocabulary). No original source code, UI or assets are included; the only
data carried over is the coarse Ukraine border polygon (public geographic coordinates).
