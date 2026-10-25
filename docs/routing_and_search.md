# Routing and address search

[Documentation index](../README.md)

A routing pack lets the phone calculate routes and search addresses without internet. A map pack draws the streets; it is a different download. See the [glossary](glossary.md) if these terms are new.

In **Settings → Maps and routes**, the app shows storage available for the packs, including cache space Android can reclaim.
Allow room for the ZIP, its extracted contents, and the currently installed pack during replacement.
The measured Ukraine Rust routing payload alone is about 5.9 GB unpacked; display maps need additional space.
Use **Check again** after freeing space.

The app accepts locations worldwide. Install packs for the region you will use; only one map pack and one routing pack are active at a time. Outside a map pack's bounds, the map uses online tiles when available.

## Use an offline routing pack

1. Install the built-in pack during setup if your build offers one. Otherwise open **Settings → Maps and routes → Offline routing** and import or download a routing-pack ZIP.
   The app rejects archives with more than 20,000 entries or 8 GiB of extracted data. It budgets
   256 MiB of free storage when extraction starts.
2. In **Where to?**, choose **Car** or **Walk**. Install a current Rust pack; old GraphHopper packs must be replaced.
3. Search for a destination and, if needed, a starting address. Review the route before tapping **Start**.

Walking routes require an offline pack with a foot profile. If you allow online routing, the app can use an online fallback for car routes when offline routing is unavailable.
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
The new app requires newly built Rust packs; previous GraphHopper archives cannot be imported.

## Build an offline routing pack in CI

Open **Actions → Offline routing pack → Run workflow**. The `ch_threads` input defaults to `2` to use two workers inside each hierarchy preparation. Profiles run sequentially; select `1` if memory is limited. The defaults build a Ukraine pack with car
and foot routes plus offline address search. For another region, enter a one-word pack name and an
HTTPS link to its `.osm.pbf` extract. You can enter the extract's SHA-256 checksum to reject a
changed download.

After the workflow succeeds, download the `routing-pack-...` artifact and unzip that GitHub artifact.
Import the `routing-<name>.zip` inside it through **Settings → Maps and routes → Offline
routing → Import pack**. You can also upload that ZIP to your own HTTPS server and enter its URL in
the app's **Download** field. The artifact also includes a checksum and build details. CI does not
publish the archive to a server.

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
