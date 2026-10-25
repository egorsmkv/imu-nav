# Routing and address search

> Detailed reference. For easy steps, see the [guide](../routing_and_search.md) or the [glossary](../glossary.md).

## Offline routing

The [v2 binary format](routing-pack-format.md) shares compact geometry between profiles. Both v1 Rust packs and GraphHopper packs require rebuilding; Tantivy is unchanged.

See the [Rust migration validation report](../benchmarks/rust-routing-2026-10-25.md) for measured pack builds and validation limits.

The app uses `imu-nav-routing`, our Rust runtime, with contraction hierarchies prepared by
`faster_paths` 0.1.0. OSM import, snapping, route reconstruction, maneuver records and track
matching are implemented in Rust. GraphHopper remains only in JVM reference tests; it is not
an app or pack-builder dependency. The navigation engine still receives the existing Kotlin `Route`.

**Compatibility:** only packs declaring `"routingFormat":"rust-ch-2"` are accepted. Rebuild or
download a current pack after upgrading. Existing GraphHopper packs are rejected with a localized
message and are not rewritten. Import validation completes before replacing the installed pack.
Display-map archives are unchanged. This remains a research prototype, not a safety system.

### Build and install

The production builder requires Rust 1.99+, not Java or an Android SDK:

```bash
cargo build --locked --release --manifest-path native/Cargo.toml -p imu-nav-routing --features builder
native/target/release/build-routing --osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine --threads 2
python3 tools/verify_routing_pack.py graph-ukraine.zip
native/target/release/check-routing graph-ukraine
```

Output directories and archives must be new. `--no-search` omits address search, `--no-addresses`
omits house numbers, and `--min-network-size` controls removal of small disconnected networks
(default 200 road-junction nodes). The Kotlin `:routing:run` launcher remains a convenience for
basic builds and calls the same Rust importer through host JNI.

The CI workflow `.github/workflows/routing-pack.yml` builds this Rust binary directly, downloads
and checks the public extract, builds the pack, verifies the ZIP and runs the actual native loader.
Its existing `ch_threads` input now selects **Rayon workers within each profile** (1, 2 or 4;
default 2). Car and foot preparation run sequentially. Import geometry/transitions are staged on
disk and OSM/search-builder memory is released before contraction. Cargo dependencies and build
outputs are cached; generated packs are rebuilt. The workflow uploads the ZIP, SHA-256, source
checksum and process timing/memory artifacts; it does not publish to a server.

A pack contains `pack.json`, `car.routing`, `foot.routing`, `shared.routing`, and normally `search.tantivy/`.
The profile files contain road geometry/names, access-specific directed roads, a snap grid and
an immutable CH. They do not contain OSM PBFs, display-map tiles, GraphHopper files or downloaded DEM
tiles. Route data is memory-mapped instead of deserializing the benchmark's bincode hierarchy.
The loader checks offsets, sizes, coordinates, CSR ranges, hierarchy acyclicity and shortcut costs.
The `IMURT001` header and all numeric records are explicitly little-endian and versioned.

To bundle a pack in Play builds, copy its ZIP to `app/src/play/assets/routing/pack.zip` and its
metadata to `app/src/play/assets/routing/pack.json`. Both are gitignored. The F-Droid flavor does not
bundle these assets. Large archives increase APK size accordingly; installable archive limits remain
20,000 entries and 8 GiB extracted, with a 256 MiB free-space reserve.

### Routing policy

Car uses a directed road-state hierarchy, including restriction history for via-way relations.
Walking uses a smaller junction-node hierarchy. Both keep complete road geometry for reconstruction.
Car honors one-way rules, mode-specific access, barriers, `no_*` and `only_*` restrictions,
including via nodes/ways and motor-vehicle exceptions. Walking ignores car-only one-way/turn rules,
respects `oneway:foot`, and prefers footways and paths. Incomplete restriction relations whose roads
are absent from an extract cannot be applied; use relation-complete regional extracts.

Car turn weights remain 60 s for U-turns, 2/5 s for right turns of 25–80°/80°+, and 8/12 s for
left turns. The route objective includes distance influence (90 s/km car, 70 s/km walking) and
walking preference. Travel duration comes from road distance and modeled speed, separately from
turn penalties. Posted speed limits are carried per geometry segment; missing limits remain unknown.
The importer has an explicit versioned policy in `builder/profile.rs`; it is **not a bit-for-bit
reimplementation of GraphHopper's OSM parser**. Speeds, access interpretation and chosen alternatives
can differ from old packs. Review resulting routes.

Time-dependent closures are treated conservatively as closed in this static pack. Conditional
turn restrictions are applied conservatively without evaluating their time expression. Reversible
car lanes and technically difficult alpine footways are excluded. This is not a live-traffic or
vehicle-dimension-aware router. Ferries use a static speed estimate, not timetables.

Snapping searches road segments within 5 km, keeping legal directed candidates on the nearest road.
Start/end geometry is clipped at the projection, including same-road journeys; vias preserve turn
history. Guidance carries road names, turn direction, ramp/merge records and roundabout exits in the
existing OSRM-style vocabulary. Track matching uses Viterbi candidate selection with GPS-distance
emissions and routed transition lengths. Requests are bounded and cancellable; cancellation does not
trigger online fallback. Pack replacement waits for active readers. Disk work stays off the main thread.
OSRM remains an optional car-routing fallback; walking stays offline-only.

### Elevation

The Rust builder accepts **local uncompressed SRTM HGT tiles**, named for example `N50E030.hgt`:

```bash
native/target/release/build-routing --osm region.osm.pbf --out graph-region --name Region \
  --elevation srtm --elevation-cache /path/to/hgt-tiles
```

It samples long edges every 60 m and interpolates bridges/tunnels between endpoint heights.
Missing tiles and void samples fail the build. Unlike the former GraphHopper builder, this importer
does not download DEMs or support its `skadi`, `cgiar`, `gmted` and `multi` provider names. Flat packs
remain the default. A valid elevation pack carries one height per route point for terrain matching.

### Validation and measurements

The [earlier faster_paths benchmark](../benchmarks/faster-paths-2026-10-24.md) used GraphHopper-exported
road states and tower-node queries. Those numbers are historical algorithm evidence, **not measurements
of this new importer or complete Android requests**. Walking layout and runtime storage now differ.
Rust tests cover import, access separation, legal reconstructed paths, forbidden turns, via-way
history, CH/Dijkstra agreement, elevation failure and malformed data. JNI tests exercise route fields,
matching, cancellation and legacy-pack rejection. Regional and Android results for the migration are
recorded separately when measured.

## Offline display map build and distribution

**Actions → Offline map pack → Run workflow** builds a Ukraine display map from a Geofabrik OSM
extract. The workflow records the extract SHA-256, Planetiler version and JAR SHA-256, source and
output sizes, and peak build memory. You can supply an expected extract checksum to reject a changed
download.

The OpenFreeMap light and dark styles, sprites, and fonts are snapshotted before generating
tiles; Planetiler emits only the vector layers those styles use, keeps English, Ukrainian and Russian
name translations, and omits unused feature IDs. Street geometry stays at zoom 14 with gzip-compressed
MVT tiles in PMTiles v3.

If the standard hosted runner runs out of RAM or disk, the job fails and reports
capacity instead of lowering street detail.

The `map-ukraine` CI artifact contains `map-ukraine.zip`, its checksum, and `map-build.json`.
Download it within the artifact retention period and upload the ZIP to your HTTPS host yourself.
The app's **Settings → Offline map → Download** field accepts its URL; import also accepts the ZIP.

CI builds a Play benchmark APK with the identical map files under `assets/map/`. MapLibre reads its
uncompressed PMTiles APK asset through an on-device loopback range reader, so installation needs no
second copy. A user-imported map
takes precedence; removing it restores the bundled map. F-Droid builds remain unbundled.

For a local build, first generate a PMTiles archive with Planetiler and then run:

```bash
python3 tools/make_map_pack.py --prepare-resources build/map-resources
python3 tools/make_map_pack.py --print-layers --resources-dir build/map-resources
python3 tools/make_map_pack.py --tiles ukraine.pmtiles --name Ukraine --out dist/map-ukraine.zip \
  --resources-dir build/map-resources --assets-dir app/src/play/assets/map
python3 tools/verify_map_pack.py dist/map-ukraine.zip --assets-dir app/src/play/assets/map
```

The Play asset folder is gitignored. Check APK size before distribution: a map and a bundled routing
pack both add to the base package, and their combined size may exceed the limit of your app store.

## Address search

New routing packs contain `search.tantivy/`, an immutable Tantivy index of settlements,
streets and house numbers built from the same OSM extract. `--no-addresses` skips house
numbers and `--no-search` omits the index. The desktop builder uses temporary SQLite
staging data, exports it to Tantivy, then removes the staging database before packaging.

Queries like `Хрещатик 22`, `Київ Хрещатик`, `вул. Шевченка, Львів` and `Буча`
work offline. Native prefix matching selects nearest candidates before the existing limits;
shared Kotlin code applies final address ranking. The wrapped-longitude heuristic and
geodesic final ranking are unchanged, with deterministic ID ordering without a reference.

The app prefers Tantivy. Existing packs containing only `search.db` remain readable through
the legacy SQLite reader; they are not rewritten on the phone. Rebuild/import a pack to use
Tantivy. New metadata declares `searchFormat: "tantivy-1"`; missing, unsupported or corrupt
native indexes are rejected before replacing a working pack. Older app versions need an
upgrade to search these new packs. See [Tantivy search](tantivy_search.md) for the format,
build workflow and validation, and the [benchmark report](tantivy_search_experiment.md)
for the measured large-index gains and small-pack tradeoffs.

Phone search batches are bound to their query and reference position. Editing either removes
stale results from selection, including the keyboard Search action, during debounce and lookup.

The route panel uses the same text search for both the starting address and the destination; the
current trusted position remains the default start until the user chooses another one.
When the offline index finds nothing and online use is allowed (**Settings → Maps and routes → Offline routing →
Allow online routing and search**), a [Photon](https://github.com/komoot/photon) geocoder is asked.

The public server `photon.komoot.io` is the default; **Settings → Maps and routes → Address search** accepts your own
server instead (a host such as `http://192.168.1.10:2322` or the full `…/api` URL), with a _Test_
button that runs a sample query. Self-hosting keeps search text off third-party servers.

The bundled PMTiles loopback range server uses four workers and at most four queued connections.
Additional connections close immediately. Request headers are limited to 8 KiB and 64 lines,
with a five-second deadline from acceptance that also closes blocked response writes. Responses
stream through a fixed 64 KiB asset buffer. Closing the server releases sockets and workers.

Search requests cancel native retrieval, legacy SQLite statements and Photon calls when superseded. Cancellation does not
count as an empty offline result and cannot initiate online fallback. Android Auto also binds
selectable results to the query and reference position. The 250 ms debounce is unchanged.

House numbers are looked up in one batch across all retrieved street candidates, with exact
numbers before building parts (`22`, then `22 к1`, never `220`), then distance and stable ties.
A trailing spaced letter is retained as part of the house number (`4 в`, `181 а`,
`12/2 а`, or `4 в к1`), before removing street/place abbreviations. The indexed spelling
is preserved: `4 в` is not automatically treated as `4в`. This works on existing SQLite
and Tantivy packs without rebuilding them. See the
[Ukraine suffix regression report](../benchmarks/ukraine-search-house-suffix-2026-10-24.md).
Up to two addresses per street are retained after ranking. Missing house numbers still leave
generic street results. Settlement parsing retains single-word interpretations and additionally
tries contiguous prefixes and suffixes of up to three words, leaving at least one street word.
There is no geographic cutoff on explicit settlements, fuzzy matching, or transliteration.

Tantivy and the legacy SQLite reader use the same candidate limits and ordering.
Request-local memoization has no cross-request invalidation.

### Historical SQLite optimization measurements

The measurements below describe the SQLite optimization preceding Tantivy adoption and
remain relevant to legacy packs. That builder used FTS4 UTF-8 byte prefix lengths
`2,3,4,6,8`; Cyrillic characters usually occupy two bytes. See the
[Tantivy comparison](tantivy_search_experiment.md) for the subsequent backend comparison.

Run the synthetic offline benchmark with:

```bash
SEARCH_BENCHMARK=1 ./gradlew :routing:test --tests '*SearchBenchmarkTest*' --rerun-tasks
```

It creates 10,000 synthetic streets, settlements and addresses, compares old/new FTS schemas,
and prints database size, first open plus lookup time, and warm p50/p95 from 50 samples after
10 warmups per query. The first-open measurement does not flush the OS page cache. Times exclude
debounce and network work. Synthetic timings do not establish performance on regional packs or phones.

Measurements on 2026-10-24 used JDK 21 and SQLite JDBC 3.53.4.0. The baseline search
object came from `e5d75df`, compiled alongside the same JDBC reader and benchmark harness.
The final paired run stopped the emulator and ran the benchmark alone in separate JVMs.
These host timings exclude debounce and network time; they are not phone timing claims.

The 10,000-row synthetic corpus measured milliseconds as follows:

| Query | Baseline, old index p50 / p95 | Updated, old index p50 / p95 | Updated, prefix index p50 / p95 |
| --- | --- | --- | --- |
| `ma` | 11.899 / 12.260 | 12.178 / 13.351 | 12.075 / 12.844 |
| `го` | 12.077 / 12.467 | 11.998 / 12.944 | 12.244 / 12.967 |
| `Main 22` | 12.423 / 12.861 | 12.858 / 13.627 | 12.132 / 12.780 |
| `New Town Main 22` | 21.211 / 21.805 | 23.719 / 25.163 | 22.856 / 23.479 |
| `Main New Town 22` | 22.032 / 22.596 | 24.039 / 25.145 | 23.707 / 24.574 |
| `Main 99` | 11.997 / 12.509 | 12.226 / 13.063 | 11.974 / 12.652 |
| `absent` | 0.175 / 0.193 | 0.176 / 0.182 | 0.145 / 0.150 |

The older Monaco pack (built 2026-10-05, 10 places, 313 streets, 431 addresses) was measured
on disposable copies. The prefix variant rebuilds only FTS, preserving every row ID and record.
Set `SEARCH_PACK_DB=/path/to/monaco/search.db` alongside `SEARCH_BENCHMARK=1` to include
this regional workload; the hard-coded public queries target Monaco.

| Query | Baseline, old index p50 / p95 | Updated, old index p50 / p95 | Updated, prefix index p50 / p95 |
| --- | --- | --- | --- |
| `av` | 0.601 / 0.778 | 0.901 / 1.035 | 0.368 / 0.394 |
| `ru` | 0.628 / 0.731 | 0.643 / 1.025 | 0.463 / 0.498 |
| `Monaco` | 0.311 / 0.389 | 0.309 / 0.382 | 0.216 / 0.232 |
| `Rue Bel Respiro 1` | 0.555 / 0.635 | 0.636 / 0.792 | 0.594 / 0.622 |
| `Rue Bel Respiro 9999` | 0.484 / 0.594 | 0.561 / 0.634 | 0.568 / 0.627 |
| `La Condamine Rue Imberty 6` | 0.542 / 0.590 | 0.678 / 0.732 | 0.618 / 0.638 |
| `го` | 0.198 / 0.220 | 0.213 / 0.239 | 0.181 / 0.193 |
| `absent` | 0.169 / 0.179 | 0.184 / 0.190 | 0.160 / 0.174 |

First open plus the first query (not OS-cold; includes JVM/library warmup) measured:

| Corpus | Baseline old / prefix index | Updated old / prefix index |
| --- | --- | --- |
| synthetic | 13.87 / 43.89 ms | 13.47 / 28.23 ms |
| Monaco | 123.33 / 2.27 ms | 128.87 / 0.79 ms |

Prefix indexes grew the synthetic database from 2,162,688 to 2,846,720 bytes (+31.6%), and
Monaco from 143,360 to 163,840 bytes (+14.3%). The first-open measurements are particularly
sensitive to warmup, so they do not establish a prefix-index startup speedup.

Ordinary synthetic house queries use three database calls instead of seven: one batched address
lookup replaces five individual calls. Synthetic multiword queries use nine instead of seven
calls, returning addresses that the baseline misses. Request-local reuse of proven matching
nearest candidates avoids two additional settlement scans; unknown aliases still query SQLite.
Monaco's tested house queries already have one candidate street and remain at six total calls.

**The blanket no-latency-regression acceptance target is not met.** Repeated runs show small
regional address-query increases (roughly 0.08–0.14 ms p50 on the old Monaco index in the final
pair) and roughly 2–2.5 ms additional p50 for synthetic multiword interpretations. Prefixes help
some queries but do not eliminate every increase. Do not claim an across-the-board speedup;
these measurements expose the cost of broader matching as well as index growth.

Validation: full `./gradlew check`, both release/benchmark APK builds, old/new synthetic-index
regressions, native Android SQLite cancellation, delayed Photon cancellation, and the Monaco
phone-loader smoke test passed. The final R8 benchmark APK imported the older Monaco pack on
the emulator, found and selected `Rue Bel Respiro, 1`, retained a generic street for missing house
`9999`, and kept stale results from being selected during edits and the keyboard Search action.
Replay accuracy remained `p95=20 max=25 rms=10 m`. No Android Auto head-unit test was available;
its selection ownership checks were compiled and statically checked. Large-country pack timing, concurrent pack replacement during device search,
and on-device SQLite cancellation timing remain unmeasured.

The [historical Tantivy comparison](tantivy_search_experiment.md) records the measurements
preceding adoption. Tantivy now handles newly built packs; the SQLite measurements above
remain relevant only to legacy packs.

The isolated [faster_paths CH benchmark](../benchmarks/faster-paths-2026-10-24.md) compares
the historical parallel Rust CH experiment against GraphHopper; the current runtime now uses the Rust pack format described above.
