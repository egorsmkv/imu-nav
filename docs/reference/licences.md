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
| MapLibre Native for Android (+ GeoJSON, Turf, Gestures)                                   | 13.6.1             | map rendering                                                          | BSD 2-Clause             |
| OkHttp / Okio                                                                             | 5.4.0 / 3.17.0     | all HTTP (sync, downloads, OSRM, Photon; also MapLibre's tile loading) | Apache 2.0               |
| GraphHopper core + map matching                                                           | 11.0               | offline routing, snapping trips to roads                               | Apache 2.0               |
| ↳ HPPC                                                                                    | 0.8.1              | GraphHopper's primitive collections                                    | Apache 2.0               |
| ↳ Jackson (core, databind, XML) + Woodstox 7.1                                            | 2.19.2             | GraphHopper's config / JSON                                            | Apache 2.0               |
| ↳ Stax2 API                                                                               | 4.2.2              | XML parsing (Jackson XML)                                              | BSD 2-Clause             |
| ↳ JTS Topology Suite                                                                      | 1.20.0             | geometry in GraphHopper                                                | EPL 2.0 / EDL 1.0 (dual) |
| ↳ Janino + commons-compiler                                                               | 3.1.9              | GraphHopper custom models (desktop only; the phone uses plain code)    | BSD 3-Clause             |
| ↳ osm-legal-default-speeds                                                                | 1.4                | default speed limits per road type                                     | BSD 3-Clause             |
| ↳ Apache XML Graphics Commons, Commons IO                                                 | 2.7 / 1.3.1        | GraphHopper utilities                                                  | Apache 2.0               |
| ↳ SLF4J API                                                                               | 2.0.17             | logging facade (no backend on Android)                                 | MIT                      |
| ↳ Gson, Timber, JSpecify, JetBrains annotations, Guava listenablefuture                   | —                  | pulled in by MapLibre / AndroidX                                       | Apache 2.0               |

The APK contains no copyleft code. GraphHopper's `.osm.pbf` reader needs `osmosis-osm-binary`
(LGPL 3.0) and Protocol Buffers, but only when importing OSM data, which the phone never does, so
`app/build.gradle.kts` excludes both from the app (verified: only `com.graphhopper.reader.osm.pbf.*`
uses them).

### Desktop tools only

The `:routing` pack builder, Rust `server/`, and `:replay` use the same GraphHopper stack plus
the libraries below. These are not in the APK.

| Library                      | Version          | Used for                                                      | Licence                                       |
| ---------------------------- | ---------------- | ------------------------------------------------------------- | --------------------------------------------- |
| osmosis-osm-binary           | 0.48.3           | reading `.osm.pbf` extracts                                   | LGPL 3.0 (used unmodified, as a separate jar) |
| Protocol Buffers (Java)      | 3.12.2           | `.osm.pbf` decoding                                           | BSD 3-Clause                                  |
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
| Android Gradle Plugin, Android Lint               | 9.2.0         | Apache 2.0 |
| Kotlin compiler / Gradle plugin, Compose compiler | 2.3.21        | Apache 2.0 |
| Gradle                                            | 9.6.1         | Apache 2.0 |
| detekt                                            | 2.0.0-alpha.6 | Apache 2.0 |
| Spotless                                          | 8.10.3        | Apache 2.0 |
| ktlint                                            | 1.8.0         | MIT        |
| JUnit 4                                           | 4.13.2        | EPL 1.0    |
| kotlin-test                                       | 2.3.21        | Apache 2.0 |

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
Android bookmark instrumentation tests additionally use AndroidX Test Runner 1.6.2 and AndroidX
JUnit extensions 1.2.1 (Apache 2.0); these are test-only dependencies and are not included in the app.

## Provenance

Written from scratch based on a behavioural analysis of BlindDriver 0.4.0 (algorithms,
thresholds and log vocabulary). No original source code, UI or assets are included; the only
data carried over is the coarse Ukraine border polygon (public geographic coordinates).

## Browser map

The sharing server vendors MapLibre GL JS 5.20.0 under BSD-3-Clause. Its distribution licence and bundled third-party notices are in `server/static/maplibre/license.txt` and the distributed JavaScript. OpenFreeMap supplies map data with on-map attribution. The pinned assets are reproduced by `tools/vendor_trip_map.py`.
