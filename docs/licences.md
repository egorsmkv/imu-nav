# Licences and provenance

[Documentation index](../README.md)

The project source code is MIT-licensed. Bundled map and cell data have their own terms, so check them before redistributing an APK or data pack.

The build uses Gradle 9.8.1 under Apache 2.0; it is not packaged in the APK.

## Check what you may share

1. Read the project [LICENSE](../LICENSE) for the source code.
2. Read [NOTICE.md](../NOTICE.md) for data and third-party notices.
3. Check the licence for each data pack you include. OpenStreetMap-derived routing and search data use ODbL; the built-in cell database includes OpenCellID data under CC BY-SA 4.0.
4. Use the [full library and data list](reference/licences.md) when reviewing dependencies or preparing a release.

IMU Nav's navigation code was written from scratch after a behavioural analysis of a separate navigation application's 0.4.0 release. No original source code, UI or assets are included. See the [glossary](glossary.md) for terms such as APK and routing pack.

## Browser map

The sharing server vendors MapLibre GL JS 5.20.0 under BSD-3-Clause. Its distribution licence and bundled third-party notices are in `server/static/maplibre/license.txt` and the distributed JavaScript. OpenFreeMap supplies map data with on-map attribution. The pinned assets are reproduced by `tools/vendor_trip_map.py`.

## AndroidX Graphics Path JNI (Rust port)

`native/graphics-path/` ports the AndroidX Graphics Path 1.0.1 native library to Rust
(Apache-2.0, a derivative of The Android Open Source Project's code). Its `LICENSE` is the
upstream licence; its README names the source commit and lists the changes. The prebuilt
library is removed from the AAR; the Maven Java/Kotlin API is pinned to the same version.
The crate uses `jni` 0.21 (MIT/Apache-2.0), already used by the other Rust libraries, and the
project-owned `imu-nav-types` crate (MIT). `serde_json` is a test-only dependency.
The shared types crate has no mandatory third-party dependencies; its optional Serde feature uses
the already-listed Serde dependency and is shared by the server, desktop protocol, routing and search.

## Device tests

AndroidX test libraries are confined to test APKs. Device tests exclude the Car App Testing
library’s transitive Robolectric dependencies; production APK dependencies are unchanged.
See the [full library list](reference/licences.md) for versions and licences.
Compose UI Test JUnit4 uses the existing Compose BOM and Apache 2.0 licence; it is confined to
the AndroidTest APK and does not change production dependencies.
App host unit tests use Robolectric 4.17 (MIT), AndroidX Test Core 1.6.1 and OkHttp MockWebServer 3
5.4.0 (both Apache 2.0). They run only on the build machine and are not part of any APK.

The sharing server uses `zip` 8.6 (MIT) to validate user-uploaded profiling archives. It enables only DEFLATE support; this dependency is not included in the Android app.

The desktop viewer uses eframe/egui, Walkers, image, rusqlite/SQLite and rfd under permissive
licences; its GUI dependencies are separate from the APK and relay. The Windows artifact includes
third-party notices. See the [desktop dependency reference](reference/licences.md#desktop-navigation-viewer).
Kotlin's streaming mirror, typed routing/search boundaries and synchronized preference codecs use
the existing Jackson Core 2.19.2 (Apache 2.0), declared directly by the core, routing and app modules.

Offline address search uses Tantivy 0.26.1 (MIT), JNI 0.21.1 and Serde/serde_json
(MIT/Apache-2.0). The read-only native library is included in Android APKs for
ARM64, ARMv7 and x86-64 (emulator builds). The writer JNI entry point is enabled
only for desktop pack generation. See [Tantivy search](reference/tantivy_search.md).

The same isolated routing benchmark also uses `faster_paths` 0.1.0 (MIT OR Apache-2.0)
and bincode 1.3.3 (MIT), with dependencies pinned in its own Cargo.lock.
Bincode remains experiment-only. faster_paths is now also used by the production Rust builder (see below).


The production Rust routing builder now uses `faster_paths` 0.1.0 (MIT OR Apache-2.0),
`osmpbfreader` 0.19.1 (WTFPL), quick-xml 0.38 (MIT), ZIP 4.6 (MIT), Rayon (MIT OR Apache-2.0)
and time (MIT OR Apache-2.0). These importer/preparation dependencies are host-only optional
features. Android's `imu-nav-routing` runtime uses memmap2, JNI, Serde and serde_json under
MIT/Apache-2.0 terms. It implements its own checked memory-mapped query/geometry adapter;
no Janino, HPPC, OSM PBF reader or bincode dependency is added to the APK.
