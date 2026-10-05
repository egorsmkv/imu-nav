#!/usr/bin/env python3
"""Validate that a downloadable map ZIP and optional APK assets contain the same usable pack."""

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

from make_map_pack import pmtiles_header, used_layers

REQUIRED = {"pack.json", "tiles.pmtiles", "style-light.json", "style-dark.json",
            "sprites/ofm.json", "sprites/ofm.png", "sprites/ofm@2x.json", "sprites/ofm@2x.png"}


def digest(stream) -> str:
    """Hash a pack entry without loading a potentially huge PMTiles file into memory."""
    hash_value = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        hash_value.update(chunk)
    return hash_value.hexdigest()


def verify(archive_path: Path, assets_dir: Path | None) -> None:
    """Check required files, vector styles, metadata and byte-for-byte asset parity."""
    with zipfile.ZipFile(archive_path) as archive:
        entries = set(archive.namelist())
        if not REQUIRED.issubset(entries):
            raise ValueError(f"missing map files: {sorted(REQUIRED - entries)}")
        if any(name.startswith("/") or ".." in Path(name).parts for name in entries):
            raise ValueError("unsafe ZIP member path")
        metadata = json.loads(archive.read("pack.json"))
        if metadata.get("kind") != "map" or metadata.get("maxzoom", 0) < 14:
            raise ValueError("pack metadata is not a detailed map")
        if metadata.get("sizeBytes") != archive.getinfo("tiles.pmtiles").file_size:
            raise ValueError("PMTiles size does not match metadata")
        if archive.getinfo("tiles.pmtiles").compress_type != zipfile.ZIP_STORED:
            raise ValueError("PMTiles must be stored without redundant ZIP compression")
        styles = [json.loads(archive.read(name)) for name in ("style-light.json", "style-dark.json")]
        if not used_layers(styles):
            raise ValueError("styles have no map vector layers")
        for style in styles:
            if style["sources"]["openmaptiles"]["url"] != "pmtiles://{PACK_URI}/tiles.pmtiles":
                raise ValueError("style does not refer to the local PMTiles file")
            if style.get("glyphs") != "{PACK_URI}/fonts/{fontstack}/{range}.pbf":
                raise ValueError("style does not refer to local glyphs")
        if assets_dir is not None:
            asset_entries = {path.relative_to(assets_dir).as_posix() for path in assets_dir.rglob("*") if path.is_file()}
            if entries != asset_entries:
                raise ValueError(f"ZIP and APK assets differ: {sorted(entries ^ asset_entries)}")
            for name in sorted(entries):
                with archive.open(name) as zipped, (assets_dir / name).open("rb") as asset:
                    if digest(zipped) != digest(asset):
                        raise ValueError(f"ZIP and APK asset differ: {name}")
            header = pmtiles_header(assets_dir / "tiles.pmtiles")
            if header["maxzoom"] != metadata["maxzoom"] or header["bounds"] != metadata["bounds"]:
                raise ValueError("PMTiles header and pack metadata differ")
    print(f"verified {archive_path} ({archive_path.stat().st_size // 1_048_576} MB)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    parser.add_argument("--assets-dir", type=Path)
    args = parser.parse_args()
    verify(args.zip, args.assets_dir)


if __name__ == "__main__":
    main()
