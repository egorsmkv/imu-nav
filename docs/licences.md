# Licences and provenance

[Documentation index](../README.md)

The project source code is MIT-licensed. Bundled map and cell data have their own terms, so check them before redistributing an APK or data pack.

The build uses Gradle 9.8.1 under Apache 2.0; it is not packaged in the APK.

## Check what you may share

1. Read the project [LICENSE](../LICENSE) for the source code.
2. Read [NOTICE.md](../NOTICE.md) for data and third-party notices.
3. Check the licence for each data pack you include. OpenStreetMap-derived routing and search data use ODbL; the built-in cell database includes OpenCellID data under CC BY-SA 4.0.
4. Use the [full library and data list](reference/licences.md) when reviewing dependencies or preparing a release.

The navigation code was written from scratch after a behavioural analysis of BlindDriver 0.4.0. No original source code, UI or assets are included. See the [glossary](glossary.md) for terms such as APK and routing pack.

## Browser map

The sharing server vendors MapLibre GL JS 5.20.0 under BSD-3-Clause. Its distribution licence and bundled third-party notices are in `server/static/maplibre/license.txt` and the distributed JavaScript. OpenFreeMap supplies map data with on-map attribution. The pinned assets are reproduced by `tools/vendor_trip_map.py`.

## Device tests

AndroidX test libraries are confined to test APKs. Device tests exclude the Car App Testing
library’s transitive Robolectric dependencies; production APK dependencies are unchanged.
See the [full library list](reference/licences.md) for versions and licences.

The sharing server uses `zip` 8.6 (MIT) to validate user-uploaded profiling archives. It enables only DEFLATE support; this dependency is not included in the Android app.
