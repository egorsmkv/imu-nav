# Licence

> Detailed reference. For easy steps, see the [guide](../licences.md) or the [glossary](../glossary.md).

Source code: MIT (see `LICENSE`). Bundled data keeps its own licences — OpenStreetMap-derived
routing/search packs are ODbL, the cell database is CC BY-SA 4.0; see `NOTICE.md`.

## Libraries and licences

Every library below was read from the resolved dependency graph (`./gradlew :app:dependencies`
etc.) and its licence from the published POM. All are compatible with this project's MIT licence.

### Shipped in the app (APK)

| Library                                                                                   | Version            | Used for                                                               | Licence                  |
| ----------------------------------------------------------------------------------------- | ------------------ | ---------------------------------------------------------------------- | ------------------------ |
| Kotlin standard library                                                                   | 2.3.21             | language runtime                                                       | Apache 2.0               |
| IMU Nav native estimator                                                                  | 0.1.0              | route-state estimation and covariance math                             | MIT                      |
| Rust `jni` crate                                                                          | 0.21.1             | checked JNI access for the native estimator                            | MIT / Apache 2.0         |
| Rust `tracing` / `tracing-subscriber`                                                    | 0.1 / 0.3          | native diagnostics and Android Logcat formatting                       | MIT                     |
| kotlinx.coroutines                                                                        | 1.11.0             | background work, flows                                                 | Apache 2.0               |
| AndroidX Car App (`app`, `app-projected`)                                                 | 1.7.0              | Android Auto templates, navigation and surface lifecycle               | Apache 2.0               |
| AndroidX Core KTX, Activity Compose, Lifecycle (runtime-compose, service)                 | 1.18 / 1.13 / 2.10 | Android integration                                                    | Apache 2.0               |
| Jetpack Compose (BOM 2026.06.01: UI 1.11, Material 3 1.4) + Material Icons Extended 1.7.8 | —                  | user interface                                                         | Apache 2.0               |
| AndroidX Graphics Path (Kotlin API; JNI ported to Rust for 16 KB pages) | 1.0.1 | Compose path queries; provenance and licence in `native/graphics-path/` | Apache 2.0 |
| MapLibre Native for Android (+ GeoJSON, Turf, Gestures)                                   | 13.6.1             | map rendering                                                          | BSD 2-Clause             |
| OkHttp / Okio                                                                             | 5.4.0 / 3.17.0     | all HTTP (sync, downloads, OSRM, Photon; also MapLibre's tile loading) | Apache 2.0               |
| Jackson Core | 2.19.2 | streaming JSON for native adapters and recording | Apache 2.0 |
| ↳ Gson, Timber, JSpecify, JetBrains annotations, Guava listenablefuture                   | —                  | pulled in by MapLibre / AndroidX                                       | Apache 2.0               |

GraphHopper and its Java dependencies (HPPC, JTS, Janino, osmosis-osm-binary, Protocol Buffers…)
have been removed from the project, including its former JVM reference tests.

### Desktop tools only

The `:routing` pack builder, Rust `server/`, and `:replay` use the libraries below.
These are not in the APK.

| Library                      | Version          | Used for                                                      | Licence                                       |
| ---------------------------- | ---------------- | ------------------------------------------------------------- | --------------------------------------------- |
| SQLite JDBC                  | 3.53.4.0         | writing the search index                                      | Apache 2.0                                    |
| Axum + Tokio                 | 0.8 / 1.x        | cell server HTTP/WebSocket runtime                            | MIT                                           |
| Tokio Util, Tempfile         | 0.7 / 3.x        | streamed administrator exports and temporary imports          | MIT                                           |
| tikv-jemallocator / jemalloc | 0.7 / 5.3.1      | optional Linux native replay heap allocator                   | MIT / Apache 2.0; jemalloc BSD 2-Clause       |
| pprof-rs                     | 0.15             | native simulation CPU profiles and flamegraphs (desktop only) | Apache 2.0                                    |
| hotpath-rs                   | 0.28.4           | optional native-core and cell-server function timing          | MIT                                           |
| jemalloc_pprof               | 0.9              | optional Linux native heap export to pprof                    | Apache 2.0                                    |
| Rusqlite + SQLite            | 0.40 / bundled   | persistent cell server database                               | MIT / public domain                           |
| PostgreSQL, r2d2 and native TLS | 0.19 / 0.8 / 0.2 | optional pooled PostgreSQL cell server database             | MIT or MIT / Apache 2.0                       |
| TOML                         | 0.9              | server configuration file                                     | MIT / Apache 2.0                              |
| Argon2, Rand, SHA-2          | 0.5 / 0.9 / 0.10 | password hashes and opaque account tokens                     | MIT or MIT/Apache 2.0                         |
| rpassword                    | 7.5              | hidden administrator password prompt on the cell server       | Apache 2.0                                    |
| Lettre                       | 0.11             | password-reset and email-verification messages over SMTP      | MIT                                           |
| time                         | 0.3              | UTC timestamps in the cell server account panel               | MIT / Apache 2.0                              |

The sharing server also uses Serde, CSV, Flate2, Clap and Tracing (MIT or MIT/Apache 2.0).
The desktop simulation uses Clap, Serde/serde_json, Anyhow, Flate2 and tikv-jemalloc-ctl
(MIT or MIT/Apache 2.0); these dependencies are not packaged in the APK.

### Build and development tools

These tools are not distributed with the app.

| Tool                                              | Version       | Licence    |
| ------------------------------------------------- | ------------- | ---------- |
| Android Gradle Plugin, Android Lint               | 9.4.1         | Apache 2.0 |
| Kotlin compiler / Gradle plugin, Compose compiler | 2.3.21        | Apache 2.0 |
| Gradle                                            | 9.8.1         | Apache 2.0 |
| detekt                                            | 2.0.0-alpha.6 | Apache 2.0 |
| Spotless                                          | 8.10.3        | Apache 2.0 |
| ktlint                                            | 1.8.0         | MIT        |
| JUnit 4                                           | 4.13.2        | EPL 1.0    |
| kotlin-test                                       | 2.3.21        | Apache 2.0 |
| Robolectric (app host tests)                      | 4.17          | MIT        |
| AndroidX Test Core (app host tests)               | 1.6.1         | Apache 2.0 |
| OkHttp MockWebServer 3 (app host tests)           | 5.4.0         | Apache 2.0 |

### Data and online services

| Source                                | Used for                                                                      | Terms                                      |
| ------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------ |
| OpenStreetMap                         | routing / search packs, map tiles                                             | © OpenStreetMap contributors, ODbL 1.0    |
| OpenFreeMap                           | vector map tiles and styles                                                   | free public service; data © OpenStreetMap |
| OpenCellID                            | tower positions (download with your own token; part of the built-in database) | CC BY-SA 4.0                               |
| Mozilla Location Service final export | tower positions (built-in database, optional download)                        | public domain (CC0)                        |
| OSRM demo server                      | online routing fallback (only if allowed in Settings)                         | free for light use; self-host for real use |
| Photon (komoot)                       | online address search fallback (only if allowed)                              | free public service; data © OpenStreetMap |

Android car instrumentation tests also use AndroidX Car App Testing 1.7.0 (Apache 2.0).
Its transitive Robolectric dependencies are excluded from device tests, which run on Android itself.
Android bookmark instrumentation tests additionally use AndroidX Test Runner 1.6.2 and AndroidX
JUnit extensions 1.2.1 (Apache 2.0); these are test-only dependencies and are not included in the app.
Search restoration tests use Compose UI Test JUnit4 from BOM 2026.06.01 (Apache 2.0), also
confined to the test APK.

## Provenance

IMU Nav was written from scratch based on a behavioural analysis of a separate navigation
application's 0.4.0 release (algorithms, thresholds and log vocabulary). No original source code,
UI or assets are included; the only data carried over is the coarse Ukraine border polygon
(public geographic coordinates).

## Browser map

The sharing server vendors MapLibre GL JS 5.20.0 under BSD-3-Clause. Its distribution licence and bundled third-party notices are in `server/static/maplibre/license.txt` and the distributed JavaScript. OpenFreeMap supplies map data with on-map attribution. The pinned assets are reproduced by `tools/vendor_trip_map.py`.

The sharing server uses `zip` 8.6 (MIT) to validate user-uploaded profiling archives. It enables only DEFLATE support; this dependency is not included in the Android app.

## Desktop navigation viewer

The independent desktop workspace uses eframe/egui 0.36.2 (MIT/Apache 2.0), Walkers 0.60.0 (MIT),
rusqlite 0.40 (MIT, bundled SQLite public domain), image 0.25.10 (MIT/Apache 2.0), rfd 0.17
(MIT), serde/serde_json (MIT/Apache 2.0), time (MIT/Apache 2.0) and thiserror (MIT/Apache 2.0).
Its exact resolved dependencies are pinned in `desktop/Cargo.lock`. The portable artifact includes
dependency licence files collected from Cargo metadata by `desktop/tools/notices.py`. These GUI
dependencies are not included in the Android APK or relay; the shared protocol crate contains only
Serde, time and thiserror dependencies.

The desktop HTTPS tile worker directly uses reqwest 0.13.5 (MIT/Apache 2.0), sha2 0.10
(MIT/Apache 2.0), and httpdate 1 (MIT/Apache 2.0) for bounded fetching and HTTP caching. These
versions are already part of the desktop's resolved dependency graph.

The Kotlin core, routing and app modules declare Jackson Core 2.19.2 (Apache 2.0) directly for
streaming mirror JSON and typed routing, search, metadata, export and synchronized preference codecs.
These direct declarations use the existing resolved version; they do not upgrade the APK dependency.
Online desktop maps use OpenStreetMap attribution and its public raster tile policy; offline map
owners supply attribution through MBTiles metadata.

## Native address retrieval

`native/nav-search` is the production Tantivy reader, packaged separately from
`nav-jni`. Direct dependencies are Tantivy 0.26.1 (MIT), JNI 0.21.1
(MIT/Apache-2.0), Serde and serde_json 1 (MIT/Apache-2.0); tempfile 3
(MIT/Apache-2.0) is test-only. Resolved versions are pinned in `native/Cargo.lock`.
The `builder` feature exposes the writer JNI entry point only in desktop builds.
The upstream `tantivy-jni` wrapper is neither copied nor linked. Kotlin transport
uses the existing Jackson Core 2.19.2 (Apache-2.0). SQLite JDBC remains a desktop
staging/build dependency and is not packaged in Android APKs; legacy pack reading
uses the Android platform SQLite implementation.

The former `tools/cch-prototype` routing experiment (CCH and faster_paths compared with
GraphHopper) has been removed; its results remain in `docs/benchmarks`.
faster_paths is used by the production Rust builder (see below).


The production Rust routing builder now uses `faster_paths` 0.1.0 (MIT OR Apache-2.0),
`osmpbfreader` 0.19.1 (WTFPL), quick-xml 0.38 (MIT), ZIP 4.6 (MIT), Rayon (MIT OR Apache-2.0)
and time (MIT OR Apache-2.0). These importer/preparation dependencies are host-only optional
features. Android's `imu-nav-routing` runtime uses memmap2, JNI, Serde and serde_json under
MIT/Apache-2.0 terms. It implements its own checked memory-mapped query/geometry adapter;
no Janino, HPPC, OSM PBF reader or bincode dependency is added to the APK.
