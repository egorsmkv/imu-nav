# Routing and address search

[Documentation index](../README.md)

A routing pack lets the phone calculate routes and search addresses without internet. A map pack draws the streets; it is a different download. See the [glossary](GLOSSARY.md) if these terms are new.
The app accepts locations worldwide. Install packs for the region you will use; only one map pack and one routing pack are active at a time. Outside a map pack's bounds, the map uses online tiles when available.

## Use an offline routing pack

1. Install the built-in pack during setup if your build offers one. Otherwise open **Settings → Maps and route planning → Offline routing** and import or download a routing-pack ZIP.
   The app rejects archives with more than 20,000 entries or 8 GiB of extracted data. It budgets
   256 MiB of free storage when extraction starts.
2. In **Where to?**, choose **Car** or **Walk**. Older packs may support only Car.
3. Search for a destination and, if needed, a starting address. Review the route before tapping **Start**.

Walking routes require an offline pack with a foot profile. If you allow online routing, the app can use an online fallback for car routes when offline routing is unavailable.
At the bottom of **Settings → Community links**, the Telegram group link points to map and routing ZIP archives. English opens <https://t.me/imu_nav_en>; Ukrainian and Russian open <https://t.me/imu_nav>.

## Search for an address

1. Type a town, street or house number into the **From** or **To** field.
2. Choose a result. A routing pack with `search.db` provides offline results.
3. If no offline result appears, you can allow online search in **Settings → Maps and route planning → Offline routing**. You can choose your own search server in **Address search**.

Online search sends your search text to the chosen server. Turn it off if you want to keep searches on the phone.

## Build a pack on a computer

A full Ukraine build needs about 10 GB of RAM; other regions vary. Download the OpenStreetMap extract for your chosen region and run, for example:

```bash
./gradlew :routing:run --args="--osm ukraine-latest.osm.pbf --out graph-ukraine --name Ukraine"
```

Import the resulting ZIP in the app. For road heights, pack bundling and matching the phone's routing rules, see the [detailed routing reference](reference/ROUTING_AND_SEARCH.md).

## Build an offline routing pack in CI

Open **Actions → Offline routing pack → Run workflow**. The defaults build a Ukraine pack with car
and foot routes plus offline address search. For another region, enter a one-word pack name and an
HTTPS link to its `.osm.pbf` extract. You can enter the extract's SHA-256 checksum to reject a
changed download.

After the workflow succeeds, download the `routing-pack-...` artifact and unzip that GitHub artifact.
Import the `routing-<name>.zip` inside it through **Settings → Maps and route planning → Offline
routing → Import pack**. You can also upload that ZIP to your own HTTPS server and enter its URL in
the app's **Download** field. The artifact also includes a checksum and build details. CI does not
publish the archive to a server.

The workflow checks the ZIP and opens its extracted graph with the phone's routing rules before
uploading it. It uses a hosted runner and may need a larger runner for a region that exceeds its
memory or disk. See the [detailed routing reference](reference/ROUTING_AND_SEARCH.md) for the build
steps.

## Build an offline display map in CI

Open **Actions → Offline map pack → Run workflow** to build a detailed Ukraine map. Download the
`map-ukraine` artifact after the workflow succeeds. It contains `map-ukraine.zip` for manual upload
to your HTTPS server and a checksum; the separate Play benchmark APK artifact includes that same
map. Enter your uploaded ZIP URL in **Settings → Offline map** on builds without a bundled map.

The Play APK reads its included map through an on-device range reader, with no first-run copy. F-Droid builds can import or
download the ZIP. Imported map archives have the same 20,000-entry limit and a 4 GiB extracted-data
limit. See the [detailed routing reference](reference/ROUTING_AND_SEARCH.md) for the
build inputs, size report, and local commands.
