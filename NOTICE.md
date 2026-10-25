# Third-party data and components

The source code of this project is MIT-licensed (see `LICENSE`). Data files and libraries it
uses or bundles keep their own licences:

| Component                                                | Where                                                         | Licence                                                                                                                                                  |
| -------------------------------------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| OpenStreetMap data (routing and search packs, map tiles) | `assets/routing/`, packs built by `:routing`                  | © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) — attribution required; derived databases must stay under ODbL |
| Cell tower locations from OpenCellID                     | `assets/cells/bundled-cells.csv.gz`                           | © OpenCellID contributors, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)                                                              |
| Mozilla Location Service cell export                     | `assets/cells/bundled-cells.csv.gz`                           | Public domain                                                                                                                                            |
| Map tiles                                                | OpenFreeMap                                                   | See openfreemap.org; data © OpenStreetMap contributors                                                                                                  |
| MapLibre Native for Android                              | map rendering                                                 | BSD 2-Clause                                                                                                                                             |
| OkHttp, Okio                                             | HTTP client                                                   | Apache License 2.0                                                                                                                                       |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines    | app                                                           | Apache License 2.0                                                                                                                                       |

`native/graphics-path/` is a Rust port of the AndroidX Graphics Path 1.0.1 native library,
copyright The Android Open Source Project, under Apache-2.0. It is a derivative work: the
upstream licence is in `native/graphics-path/LICENSE`, and its README records the source
commit and the changes (a Rust translation with bounds-checked arrays and 16 KB page layout).

The [full licence list](docs/reference/licences.md) includes versions, build tools, and online services.

The app shows these attributions in Settings → About and through the map's attribution button.
