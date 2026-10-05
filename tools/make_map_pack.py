#!/usr/bin/env python3
"""
Build an offline *map display* pack for the app (Settings -> Offline map -> Download / Import).

The pack is a zip with everything MapLibre needs to draw the map without internet:

    tiles.pmtiles          vector tiles (OpenMapTiles schema), e.g. built with Planetiler
    style-light.json       OpenFreeMap "liberty" style, pointing at the local files
    style-dark.json        OpenFreeMap "dark" style, same
    sprites/ofm*.json|png  map icons
    fonts/<font>/<range>.pbf  label glyphs (Latin, Cyrillic, punctuation)
    pack.json              name, build time, bounds, size (shown in the app)

Styles, sprites and fonts are downloaded once from tiles.openfreemap.org. In the styles, every
local path starts with the placeholder {PACK_URI}; the app replaces it with the pack's folder
(file:///data/...). The online-only shaded-relief raster layer is removed.

Usage (build the tiles first, see README "Offline map"):
    java -Xmx8g -jar planetiler.jar --osm-path=ukraine-latest.osm.pbf --output=ukraine.pmtiles --download
    python3 tools/make_map_pack.py --tiles ukraine.pmtiles --name Ukraine --out map-ukraine.zip

CI first runs --prepare-resources DIR, then --print-layers --resources-dir DIR to select the
Planetiler layers used by both styles. Packaging reuses those exact resources and can write the
same files to --assets-dir for direct access from the Play APK.

Only the Python standard library is used.
"""
import argparse
import datetime
import json
import os
import shutil
import struct
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

OFM = "https://tiles.openfreemap.org"
STYLES = {"style-light.json": "liberty", "style-dark.json": "dark"}
SPRITE_FILES = ["ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png"]

# Unicode blocks (256 code points each) worth shipping: Latin, Latin Extended, combining marks,
# Greek, Cyrillic (+ supplement), Latin Extended Additional, punctuation, letterlike symbols,
# arrows, math, box drawing, geometric shapes. Characters outside these simply are not drawn.
GLYPH_RANGES = [0, 256, 512, 768, 1024, 1280, 7424, 7680, 7936, 8192, 8448, 8704, 9472, 9728]

USER_AGENT = "blind-driver-opensource map pack builder"


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def pmtiles_header(path: Path) -> dict:
    """Read bounds and zoom range from a PMTiles v3 header (127 bytes, little endian)."""
    with path.open("rb") as f:
        header = f.read(127)
    if header[:7] != b"PMTiles" or header[7] != 3:
        sys.exit(f"{path} is not a PMTiles v3 file")
    min_zoom, max_zoom = header[100], header[101]
    min_lon, min_lat, max_lon, max_lat = (v / 1e7 for v in struct.unpack_from("<iiii", header, 102))
    return {"minzoom": min_zoom, "maxzoom": max_zoom, "bounds": [min_lat, max_lat, min_lon, max_lon]}


def localize_style(style: dict, attribution: str) -> dict:
    """Point the style at the pack's own files and drop online-only layers."""
    style["sprite"] = "{PACK_URI}/sprites/ofm"
    style["glyphs"] = "{PACK_URI}/fonts/{fontstack}/{range}.pbf"
    online_only = [name for name, source in style["sources"].items() if name != "openmaptiles"]
    for name in online_only:
        del style["sources"][name]
    style["layers"] = [layer for layer in style["layers"] if layer.get("source") not in online_only]
    style["sources"]["openmaptiles"] = {
        "type": "vector",
        "url": "pmtiles://{PACK_URI}/tiles.pmtiles",
        "attribution": attribution,
    }
    return style


def fonts_used(styles: list) -> set:
    fonts = set()
    for style in styles:
        for layer in style["layers"]:
            stack = layer.get("layout", {}).get("text-font")
            if isinstance(stack, list):
                fonts.add(",".join(stack))
    return fonts


def used_layers(styles: list) -> set[str]:
    """Keep only vector layers that the two offline styles can draw."""
    return {layer["source-layer"] for style in styles for layer in style["layers"]
            if layer.get("source") == "openmaptiles" and "source-layer" in layer}


def styles_in(directory: Path) -> list[dict]:
    """Read the resource snapshot used for both layer selection and final packaging."""
    return [json.loads((directory / name).read_text(encoding="utf-8")) for name in STYLES]


def prepare_resources(directory: Path) -> None:
    """Snapshot styles, icons and glyphs once so a CI build cannot mix style revisions."""
    directory.mkdir(parents=True, exist_ok=True)
    attribution = '<a href="https://openfreemap.org">OpenFreeMap</a> <a href="https://www.openmaptiles.org/">© OpenMapTiles</a> ' \
                  '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>'
    styles = []
    sprite_url = None
    for name, style_id in STYLES.items():
        original = json.loads(fetch(f"{OFM}/styles/{style_id}"))
        sprite_url = original["sprite"]
        local = localize_style(original, attribution)
        (directory / name).write_text(json.dumps(local, ensure_ascii=False), encoding="utf-8")
        styles.append(local)
    assert sprite_url is not None
    for sprite in SPRITE_FILES:
        suffix = sprite[len("ofm"):]
        path = directory / "sprites" / sprite
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(fetch(f"{sprite_url}{suffix}"))
    for font in sorted(fonts_used(styles)):
        for start in GLYPH_RANGES:
            name = f"{start}-{start + 255}.pbf"
            path = directory / "fonts" / font / name
            try:
                data = fetch(f"{OFM}/fonts/{urllib.request.quote(font)}/{name}")
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)


def package(tiles: Path, resources: Path, out: Path, name: str, assets_dir: Path | None, source_sha256: str | None) -> None:
    """Create the downloadable ZIP and optionally the identical direct-access APK assets."""
    info = pmtiles_header(tiles)
    styles = styles_in(resources)
    layers = used_layers(styles)
    if not layers or info["maxzoom"] < 14:
        raise ValueError("offline map needs referenced vector layers and zoom 14 street detail")
    for style in styles:
        if style["sources"]["openmaptiles"]["url"] != "pmtiles://{PACK_URI}/tiles.pmtiles":
            raise ValueError("offline style does not point at the packaged PMTiles file")
    pack = {
        "kind": "map",
        "name": name,
        "builtAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source": tiles.name,
        "bounds": info["bounds"],
        "minzoom": info["minzoom"],
        "maxzoom": info["maxzoom"],
        "sizeBytes": tiles.stat().st_size,
    }
    if source_sha256:
        pack["sourceSha256"] = source_sha256
    metadata = json.dumps(pack, ensure_ascii=False).encode("utf-8")
    resource_files = sorted(path for path in resources.rglob("*") if path.is_file())
    required = {"style-light.json", "style-dark.json", *(f"sprites/{name}" for name in SPRITE_FILES)}
    if not required.issubset({path.relative_to(resources).as_posix() for path in resource_files}):
        raise ValueError("resource snapshot is incomplete")
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w") as archive:
        archive.write(tiles, "tiles.pmtiles", compress_type=zipfile.ZIP_STORED)
        for path in resource_files:
            archive.write(path, path.relative_to(resources).as_posix(), compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("pack.json", metadata, compress_type=zipfile.ZIP_DEFLATED)
    if assets_dir is not None:
        assets_dir.mkdir(parents=True, exist_ok=True)
        for path in resource_files:
            destination = assets_dir / path.relative_to(resources)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
        destination = assets_dir / "tiles.pmtiles"
        try:
            os.link(tiles, destination)
        except OSError:
            shutil.copyfile(tiles, destination)
        (assets_dir / "pack.json").write_bytes(metadata)
    print(f"done: {out} ({out.stat().st_size // 1_048_576} MB), zoom {info['minzoom']}-{info['maxzoom']}, layers {len(layers)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tiles", type=Path, help="vector tiles (.pmtiles, OpenMapTiles schema)")
    parser.add_argument("--name", help="pack name shown in the app, e.g. Ukraine")
    parser.add_argument("--out", type=Path, help="output .zip")
    parser.add_argument("--resources-dir", type=Path, help="previously downloaded style, sprite and font snapshot")
    parser.add_argument("--prepare-resources", type=Path, help="download a style, sprite and font snapshot and exit")
    parser.add_argument("--print-layers", action="store_true", help="print Planetiler --only-layers value from --resources-dir")
    parser.add_argument("--assets-dir", type=Path, help="also stage the same files as direct Play APK assets")
    parser.add_argument("--source-sha256", help="SHA-256 of the source OSM extract for pack provenance")
    args = parser.parse_args()
    if args.prepare_resources:
        prepare_resources(args.prepare_resources)
        return
    if args.print_layers:
        if not args.resources_dir:
            parser.error("--print-layers requires --resources-dir")
        print(",".join(sorted(used_layers(styles_in(args.resources_dir)))))
        return
    if not args.tiles or not args.name or not args.out:
        parser.error("packaging requires --tiles, --name and --out")
    if args.resources_dir:
        package(args.tiles, args.resources_dir, args.out, args.name, args.assets_dir, args.source_sha256)
    else:
        with tempfile.TemporaryDirectory(prefix="imu-map-resources-") as directory:
            prepare_resources(Path(directory))
            package(args.tiles, Path(directory), args.out, args.name, args.assets_dir, args.source_sha256)


if __name__ == "__main__":
    main()
