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
offers _Install built-in routing pack_). The APK grows by the zip size (~400+ MB for Ukraine with car and
foot profiles and the search index, making the APK ~450+ MB — over Google Play's 200 MB base-APK limit, fine for
sideloading); both files are gitignored. The F-Droid flavor deliberately never bundles this locally
generated pack: users can import or download the same freely licensed data pack in the app.

Packs can also be installed at runtime with **Import pack** (the `.zip`) or **Download** from any HTTP(S) URL — the
zip is unpacked while it streams, and interrupted downloads resume. The graph is memory-mapped, so
large regions do not need a large heap.

Android cannot compile GraphHopper's custom models at runtime (Janino generates JVM bytecode), so
`PhoneGraphHopper` builds the same weighting from plain code; `GraphSpec` holds everything the
builder and the phone must agree on, and `OfflineGraphTest` checks both give identical routes.

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
