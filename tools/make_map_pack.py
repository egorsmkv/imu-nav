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

Only the Python standard library is used.
"""
import argparse
import datetime
import json
import struct
import sys
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tiles", required=True, type=Path, help="vector tiles (.pmtiles, OpenMapTiles schema)")
    parser.add_argument("--name", required=True, help="pack name shown in the app, e.g. Ukraine")
    parser.add_argument("--out", required=True, type=Path, help="output .zip")
    args = parser.parse_args()

    info = pmtiles_header(args.tiles)
    attribution = '<a href="https://openfreemap.org">OpenFreeMap</a> <a href="https://www.openmaptiles.org/">© OpenMapTiles</a> ' \
                  '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>'
    styles = {}
    sprite_url = None
    for file_name, style_id in STYLES.items():
        print(f"style {style_id}")
        original = json.loads(fetch(f"{OFM}/styles/{style_id}"))
        sprite_url = original["sprite"]  # both styles use the same sprite sheet
        styles[file_name] = localize_style(original, attribution)
    fonts = fonts_used(list(styles.values()))

    with zipfile.ZipFile(args.out, "w") as zf:
        # The tiles are already compressed: store them as-is so the app can copy them quickly.
        print(f"tiles {args.tiles} ({args.tiles.stat().st_size // 1_048_576} MB)")
        zf.write(args.tiles, "tiles.pmtiles", compress_type=zipfile.ZIP_STORED)
        for file_name, style in styles.items():
            zf.writestr(file_name, json.dumps(style, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
        for sprite in SPRITE_FILES:
            suffix = sprite[len("ofm"):]
            zf.writestr(f"sprites/{sprite}", fetch(f"{sprite_url}{suffix}"), compress_type=zipfile.ZIP_DEFLATED)
        for font in sorted(fonts):
            print(f"font {font}")
            for start in GLYPH_RANGES:
                name = f"{start}-{start + 255}.pbf"
                try:
                    data = fetch(f"{OFM}/fonts/{urllib.request.quote(font)}/{name}")
                except urllib.error.HTTPError as e:
                    print(f"  skip {name}: HTTP {e.code}")
                    continue
                zf.writestr(f"fonts/{font}/{name}", data, compress_type=zipfile.ZIP_DEFLATED)
        size = args.tiles.stat().st_size
        pack = {
            "kind": "map",
            "name": args.name,
            "builtAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "source": args.tiles.name,
            "bounds": info["bounds"],
            "minzoom": info["minzoom"],
            "maxzoom": info["maxzoom"],
            "sizeBytes": size,
        }
        zf.writestr("pack.json", json.dumps(pack), compress_type=zipfile.ZIP_DEFLATED)
    print(f"done: {args.out} ({args.out.stat().st_size // 1_048_576} MB), zoom {info['minzoom']}-{info['maxzoom']}, bounds {info['bounds']}")


if __name__ == "__main__":
    main()
