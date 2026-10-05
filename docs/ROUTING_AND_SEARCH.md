# Routing and address search

[Documentation index](../README.md)

A routing pack lets the phone calculate routes and search addresses without internet. A map pack draws the streets; it is a different download. See the [glossary](GLOSSARY.md) if these terms are new.
The app accepts locations worldwide. Install packs for the region you will use; only one map pack and one routing pack are active at a time. Outside a map pack's bounds, the map uses online tiles when available.

## Use an offline routing pack

1. Install the built-in pack during setup if your build offers one. Otherwise open **Settings → Maps and route planning → Offline routing** and import or download a routing-pack ZIP.
2. In **Where to?**, choose **Car** or **Walk**. Older packs may support only Car.
3. Search for a destination and, if needed, a starting address. Review the route before tapping **Start**.

Walking routes require an offline pack with a foot profile. If you allow online routing, the app can use an online fallback for car routes when offline routing is unavailable.

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

## Build an offline display map in CI

Open **Actions → Offline map pack → Run workflow** to build a detailed Ukraine map. Download the
`map-ukraine` artifact after the workflow succeeds. It contains `map-ukraine.zip` for manual upload
to your HTTPS server and a checksum; the separate Play benchmark APK artifact includes that same
map. Enter your uploaded ZIP URL in **Settings → Offline map** on builds without a bundled map.

The Play APK reads its included map through an on-device range reader, with no first-run copy. F-Droid builds can import or
download the ZIP. See the [detailed routing reference](reference/ROUTING_AND_SEARCH.md) for the
build inputs, size report, and local commands.
