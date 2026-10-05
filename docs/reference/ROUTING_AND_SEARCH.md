# Routing and address search

> Detailed reference. For easy steps, see the [guide](../ROUTING_AND_SEARCH.md) or the [glossary](../GLOSSARY.md).

## Offline routing

Routes are computed on the phone with **GraphHopper 11** from a _routing pack_: a road graph with
contraction hierarchies built on a computer from an OpenStreetMap extract. OSRM (online) is only a
fallback, and can be switched off in **Settings → Maps and route planning → Offline routing**. Packs also provide real speed
limits (OSM `maxspeed`) for the speed sign and the dead-reckoning speed prior.

Packs contain two profiles, **car** and **foot** (walking: footways, paths, steps, pedestrian
streets, preferring pleasant ways over busy roads). Choose _Car_ or _Walk_ in the "Where to?" panel
before starting. Walking routes are offline-only (the public OSRM server drives only). Packs built
before walking support (`pack.json` without `"profiles"`) still load; _Walk_ is then disabled.

Build one active routing pack for the region you need (needs a desktop JVM; a full Ukraine pack takes a few minutes and ~10 GB RAM). Use an OSM extract for another country in the same command:

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
offers _Install built-in routing pack_). The APK grows by the zip size (~400+ MB for Ukraine with car and
foot profiles and the search index, making the APK ~450+ MB — over Google Play's 200 MB base-APK limit, fine for
sideloading); both files are gitignored. The F-Droid flavor deliberately never bundles this locally
generated pack: users can import or download the same freely licensed data pack in the app.

Packs can also be installed at runtime with **Import pack** (the `.zip`) or **Download** from any HTTP(S) URL — the
zip is unpacked while it streams, and interrupted downloads resume. The graph is memory-mapped, so
large regions do not need a large heap.

**CI archive:** **Actions → Offline routing pack → Run workflow** runs the routing builder on a
Geofabrik Ukraine extract by default. The workflow accepts a different one-word pack name and HTTPS
`.osm.pbf` URL, with an optional expected SHA-256. It builds car and foot profiles with `search.db`
and no elevation tiles, validates the flat ZIP layout and metadata, extracts it, and opens the graph
with `PhoneGraphHopper`. Pull requests affecting the builder or workflow run the same checks on a
small Monaco extract. The downloadable GitHub artifact contains `routing-<name>.zip`, its SHA-256
file and `routing-build.json` with the source URL and checksum, source commit, pack metadata, sizes
and peak build memory. Extract the artifact first; import its inner routing ZIP in the app or upload
that ZIP to an HTTPS host for the app's Download field. The workflow does not deploy it.

Android cannot compile GraphHopper's custom models at runtime (Janino generates JVM bytecode), so
`PhoneGraphHopper` builds the same weighting from plain code; `GraphSpec` holds everything the
builder and the phone must agree on, and `OfflineGraphTest` checks both give identical routes.

## Offline display map build and distribution

**Actions → Offline map pack → Run workflow** builds a Ukraine display map from a Geofabrik OSM
extract. The workflow records the extract SHA-256, Planetiler version and JAR SHA-256, source and
output sizes, and peak build memory. You can supply an expected extract checksum to reject a changed
download. The OpenFreeMap light and dark styles, sprites, and fonts are snapshotted before generating
tiles; Planetiler emits only the vector layers those styles use, keeps English, Ukrainian and Russian
name translations, and omits unused feature IDs. Street geometry stays at zoom 14 with gzip-compressed
MVT tiles in PMTiles v3. If the standard hosted runner runs out of RAM or disk, the job fails and reports
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
work offline in a few milliseconds; street-type words are ignored and results near you rank first.
The route panel uses the same text search for both the starting address and the destination; the
current trusted position remains the default start until the user chooses another one.
When the offline index finds nothing and online use is allowed (**Settings → Maps and route planning → Offline routing →
Allow online routing and search**), a [Photon](https://github.com/komoot/photon) geocoder is asked.
The public server `photon.komoot.io` is the default; **Settings → Maps and route planning → Address search** accepts your own
server instead (a host such as `http://192.168.1.10:2322` or the full `…/api` URL), with a _Test_
button that runs a sample query. Self-hosting keeps search text off third-party servers.
