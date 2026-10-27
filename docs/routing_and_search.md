# Routing and address search

[Documentation index](../README.md)

A routing pack lets the phone calculate routes and search addresses without internet. A map pack draws the streets; it is a different download. See the [glossary](glossary.md) if these terms are new.

In **Settings → Maps and routes**, the app shows storage available for the packs, including cache space Android can reclaim.
Allow room for the ZIP, its extracted contents, and the currently installed pack during replacement.
Earlier v2 Ukraine routing downloads were about 909 MB for car, 667 MB for walking, or 1.34 GB for both.
Their installed payloads are about 1.68 GB, 1.22 GB, and 2.53 GB respectively; display maps need additional space.
Current v3 car packs contain two car hierarchies and need new size measurements.
Use **Check again** after freeing space.

The app accepts locations worldwide. Install packs for the region you will use; only one map pack and one routing pack are active at a time. Outside a map pack's bounds, the map uses online tiles when available.

## Use an offline routing pack

1. Install the built-in pack during setup if your build offers one. Otherwise open **Settings → Maps and routes → Offline routing** and import or download a routing-pack ZIP.
   The app rejects archives with more than 20,000 entries or 8 GiB of extracted data. It budgets
   256 MiB of free storage when extraction starts. For a picked file the progress shows the percentage of
   the archive already read (a streamed ZIP lists its files only at its end, so a file count is unknown until then).
   After unpacking, the pack is fully validated once (**Checking the pack…**, up to a minute or two
   for a whole country on a slower phone) and activated without a second check. The app then stores a
   `validated.stamp` with the routing files' sizes and modification times, so later app starts open the
   pack in under a second without the full scan. If those files change, or a pack was installed by an
   older version, the next start validates fully again and writes a new stamp; Settings shows
   **Opening the pack…** meanwhile. Only a pack that really fails to open is reported as
   **Installed, but the pack could not be loaded**.
2. In **Where to?**, choose **Car** or **Walk**. Install a current Rust pack; old GraphHopper and v1/v2 Rust packs must be replaced.
3. Search for a destination and, if needed, a starting address. Review the route before tapping **Start**.

For **Car**, choose **Prefer main roads** (default) or **Balanced** in the route preview.
Main roads discourages shortcuts through courtyards, parking areas and small access roads,
while retaining access to your start, stops and destination. Balanced permits useful public-road
shortcuts. Review distance and estimated time on the route cards when alternatives differ;
the blue route is selected and the grey route is the alternative. The choice is remembered
and retained during rerouting and in saved route bookmarks. You can also add a stop on a
specific street to route through it.

Main roads requires a current offline pack covering the trip. Balanced can use the allowed
online fallback, whose server chooses road preferences. Walking stays offline-only. Both
offline car choices honor local-only access; delivery-only roads are excluded for cars.
At the bottom of **Settings → Community links**, the Telegram group link points to map and routing ZIP archives. It opens <https://t.me/imu_nav> in every interface language.

## Search for an address

1. Type a town, street or house number into the **From** or **To** field.
2. Choose a result. A current routing pack with `search.tantivy/` provides offline results.
3. If no offline result appears, you can allow online search in **Settings → Maps and routes → Offline routing**. You can choose your own search server in **Address search**.

Online search sends your search text to the chosen server. Turn it off if you want to keep searches on the phone.

## Build a pack on a computer

Build the Rust importer and routing engine, then provide a public OSM PBF extract:

```bash
cargo build --locked --release --manifest-path native/Cargo.toml -p imu-nav-routing --features builder
native/target/release/build-routing --osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine --threads 2
```

Import the resulting ZIP in the app. Java and the Android SDK are not needed for this builder.
Country-scale graphs still need several GB of RAM and temporary disk space. For local HGT road
heights, pack bundling and routing policy, see the [detailed reference](reference/routing_and_search.md).
The new app requires newly built Rust packs; previous GraphHopper and v1/v2 Rust archives cannot be imported.

## Build an offline routing pack in CI

Open **Actions → Offline routing pack → Run workflow**. The `ch_threads` input defaults to `2` to use two workers inside each hierarchy preparation. Profiles run sequentially; select `1` if memory is limited. The defaults prepare Ukraine once, then publish car-only, walking-only and combined downloads with offline address search. For another region, enter a one-word pack name and an
HTTPS link to its `.osm.pbf` extract. You can enter the extract's SHA-256 checksum to reject a
changed download.

After the workflow succeeds, choose one GitHub artifact:

- `routing-pack-car-...`: car routes only, with address search.
- `routing-pack-walk-...`: walking routes only, with address search.
- `routing-pack-car-walk-...`: both modes, with address search.

Import the downloaded GitHub artifact ZIP as it is, or the `routing-<region>-<mode>.zip` unzipped from it,
through **Settings → Maps and routes → Offline routing → Import pack**; the app unpacks the pack ZIP nested
in the artifact. Each download includes a
checksum and a report listing the sizes of all three choices. You can also upload a ZIP to
your own HTTPS server and enter its URL in the app's **Download** field. CI does not publish
archives to a server. Only one routing pack is installed at a time: choose the combined pack
if you want both modes. Car packs include both road preferences; walking packs contain the foot hierarchy and referenced
shared geometry; their Tantivy address index is unchanged.

The workflow checks the ZIP and opens its extracted graph with the phone's routing rules before
uploading it. It uses a hosted runner and may need a larger runner for a region that exceeds its
memory or disk. See the [detailed routing reference](reference/routing_and_search.md) for the build
steps.

## Build an offline display map in CI

Open **Actions → Offline map pack → Run workflow** to build a detailed Ukraine map. Download the
`map-ukraine` artifact after the workflow succeeds. It contains `map-ukraine.zip` for manual upload
to your HTTPS server and a checksum; the separate Play benchmark APK artifact includes that same
map. Enter your uploaded ZIP URL in **Settings → Offline map** on builds without a bundled map.

The Play APK reads its included map through an on-device range reader, with no first-run copy. F-Droid builds can import or
download the ZIP. Imported map archives have the same 20,000-entry limit and a 4 GiB extracted-data
limit. See the [detailed routing reference](reference/routing_and_search.md) for the
build inputs, size report, and local commands.

Address search recognizes settlement names of up to three words at the beginning or end of a
street query. House numbers are checked across all retrieved street candidates; exact numbers
rank before building parts. If a house is missing, matching streets remain available. These
improvements also work with existing packs. Newly built packs additionally index common Latin
and Cyrillic prefixes for autocomplete; installed packs are not rewritten.
