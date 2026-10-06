# Third-party data and components

The source code of this project is MIT-licensed (see `LICENSE`). Data files and libraries it
uses or bundles keep their own licences:

| Component                                                | Where                                                         | Licence                                                                                                                                                  |
| -------------------------------------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| OpenStreetMap data (routing and search packs, map tiles) | `assets/routing/`, packs built by `:routing`                  | © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) — attribution required; derived databases must stay under ODbL |
| Cell tower locations from OpenCellID                     | `assets/cells/bundled-cells.csv.gz`                           | © OpenCellID contributors, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)                                                              |
| Mozilla Location Service cell export                     | `assets/cells/bundled-cells.csv.gz`                           | Public domain                                                                                                                                            |
| Map tiles                                                | OpenFreeMap                                                   | See openfreemap.org; data © OpenStreetMap contributors                                                                                                  |
| GraphHopper (+ HPPC, Jackson, JTS, Janino…)              | routing library                                               | Apache License 2.0 (JTS: EPL 2.0 / EDL 1.0; Janino, Protobuf: BSD 3-Clause)                                                                              |
| osmosis-osm-binary                                       | OSM file reading in the desktop pack builder (not in the APK) | LGPL 3.0                                                                                                                                                 |
| MapLibre Native for Android                              | map rendering                                                 | BSD 2-Clause                                                                                                                                             |
| OkHttp, Okio                                             | HTTP client                                                   | Apache License 2.0                                                                                                                                       |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines    | app                                                           | Apache License 2.0                                                                                                                                       |

The [full licence list](docs/reference/licences.md) includes versions, build tools, and online services.

The app shows these attributions in Settings → About and through the map's attribution button.
