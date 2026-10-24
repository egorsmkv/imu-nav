# Routing and address search

> Detailed reference. For easy steps, see the [guide](../routing_and_search.md) or the [glossary](../glossary.md).

## Offline routing

Routes are computed on the phone with **GraphHopper 11** from a _routing pack_: a road graph with
contraction hierarchies built on a computer from an OpenStreetMap extract. OSRM (online) is only a
fallback, and can be switched off in **Settings → Maps and routes → Offline routing**. Packs also provide real speed
limits (OSM `maxspeed`) for the speed sign and the dead-reckoning speed prior.

Packs contain two profiles, **car** and **foot** (walking: footways, paths, steps, pedestrian
streets, preferring pleasant ways over busy roads). Choose _Car_ or _Walk_ in the "Where to?" panel
before starting. Walking routes are offline-only (the public OSRM server drives only). Packs built
before walking support (`pack.json` without `"profiles"`) still load; _Walk_ is then disabled.

**Turn restrictions and turn penalties (car):** packs obey OSM turn restrictions (`no_left_turn`,
`only_straight_on`…), charge 60 s of route weight for a U-turn and add a penalty per turn from
`GraphSpec.CAR_TURN_PENALTIES`: 2 s for a right turn of 25–80°, 5 s for a sharper right, 8 s for a left
turn of 25–80° and 12 s for a sharper left. Left turns cross oncoming traffic, so they cost more.
Penalties only change which route is chosen; the estimated travel time stays the road speed. They
need an edge-based contraction hierarchy: the Ukraine car graph takes ~31 min instead of ~2 min on a
16-thread desktop, and the graph is ~50 % larger (car-only 712 MB instead of 469 MB; the full car + foot +
search pack for Ukraine is 1.04 GB unpacked, a 531 MB ZIP). `pack.json` says
`"turnCosts":true`. Older packs (without the field) still load and route as before, without turn costs;
**rebuild and re-import a pack** to get restriction-aware routes on the phone.

Walking routes never have turn costs.

Build one active routing pack for the region you need (needs a desktop JVM; a full Ukraine pack takes
about half an hour to an hour with turn costs and ~10 GB RAM). Use an OSM extract for another country in the same command:

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
offers _Install built-in routing pack_).

The APK grows by the zip size (~530 MB for Ukraine with car (turn costs) and
foot profiles and the search index, making the APK ~580+ MB — over Google Play's 200 MB base-APK limit, fine for
sideloading); both files are gitignored.

The F-Droid flavor deliberately never bundles this locally
generated pack: users can import or download the same freely licensed data pack in the app.

Packs can also be installed at runtime with **Import pack** (the `.zip`) or **Download** from any HTTP(S) URL — the
zip is unpacked while it streams, and interrupted downloads resume. The graph is memory-mapped, so
large regions do not need a large heap.

**CI archive:** **Actions → Offline routing pack → Run workflow** runs the routing builder on a
Geofabrik Ukraine extract by default. The workflow accepts a different one-word pack name and HTTPS
`.osm.pbf` URL, with an optional expected SHA-256. It builds car (with turn costs) and foot profiles
with `search.db` and no elevation tiles, validates the flat ZIP layout and metadata (`tools/verify_routing_pack.py`
rejects a pack without `"turnCosts":true`), extracts it, and opens the graph with `PhoneGraphHopper`.
Expect roughly an hour for Ukraine on a hosted runner because of the edge-based car hierarchy.

Pull requests affecting the builder or workflow run the same checks on a
small Monaco extract. The downloadable GitHub artifact contains `routing-<name>.zip`, its SHA-256
file and `routing-build.json` with the source URL and checksum, source commit, pack metadata, sizes
and peak build memory.

Extract the artifact first; import its inner routing ZIP in the app or upload
that ZIP to an HTTPS host for the app's Download field. The workflow does not deploy it.

Android cannot compile GraphHopper's custom models at runtime (Janino generates JVM bytecode), so
`PhoneGraphHopper` builds the same weighting from plain code; `GraphSpec` holds everything the
builder and the phone must agree on, and `OfflineGraphTest` checks both give identical routes.
For turn costs the phone builds GraphHopper's own `DefaultTurnCostProvider` (turn restrictions,
U-turn costs) with a turn-penalty function evaluated from the same `CAR_TURN_PENALTIES` table that
generates the desktop custom-model statements; a test also checks that a forbidden left turn is avoided.

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

Routing packs also contain `search.db`, an SQLite FTS4 index of settlements, streets and house
numbers built from the same OSM extract (~87 MB for Ukraine; skip with `--no-addresses` or
`--no-search`). Queries like `Хрещатик 22`, `Київ Хрещатик`, `вул. Шевченка, Львів` or `Буча`
work offline; street-type words are ignored and results near you rank first. Both Android and
desktop SQLite readers order matching settlement/street candidates geographically before applying
their limits. The shared candidate heuristic uses latitude and wrapped longitude with a latitude
scale; final street ranking uses geodesic distance. It needs no pack schema migration. With no
reference position, candidate order is deterministic by ID. Queries with many matches may cost
more than selective queries because SQLite sorts matching candidates.

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

Search requests cancel SQLite statements and Photon calls when superseded. Cancellation does not
count as an empty offline result and cannot initiate online fallback. Android Auto also binds
selectable results to the query and reference position. The 250 ms debounce is unchanged.

House numbers are looked up in one batch across all retrieved street candidates, with exact
numbers before building parts (`22`, then `22 к1`, never `220`), then distance and stable ties.
Up to two addresses per street are retained after ranking. Missing house numbers still leave
generic street results. Settlement parsing retains single-word interpretations and additionally
tries contiguous prefixes and suffixes of up to three words, leaving at least one street word.
There is no geographic cutoff on explicit settlements, fuzzy matching, or transliteration.

New packs use FTS4 UTF-8 byte prefix lengths `2,3,4,6,8`; Cyrillic characters usually occupy two
bytes. Existing tables and metadata are unchanged. Installed packs get the runtime improvements
without being rewritten; prefix acceleration requires rebuilding the pack. Both readers use the
same candidate limits and ordering. Request-local memoization has no cross-request invalidation.

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
