#!/usr/bin/env python3
"""Check that a routing ZIP has the flat files and metadata required by the app."""

import argparse
import json
import math
import zipfile
from pathlib import Path


def verify(archive_path: Path) -> dict:
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)) or any(entry.is_dir() or "/" in entry.filename or "\\" in entry.filename for entry in entries):
            raise ValueError("routing archive must contain unique files at its root")
        required = {"pack.json", "properties", "search.db"}
        if not required.issubset(names):
            raise ValueError(f"routing archive is missing {sorted(required - set(names))}")
        if any(archive.getinfo(name).file_size == 0 for name in required):
            raise ValueError("routing archive has an empty required file")
        with archive.open("search.db") as search:
            if search.read(16) != b"SQLite format 3\x00":
                raise ValueError("search.db is not a SQLite database")
        info = json.loads(archive.read("pack.json"))
        if not isinstance(info, dict) or not info.get("name") or info.get("graphhopper") != "11.0":
            raise ValueError("pack.json has invalid name or GraphHopper version")
        if info.get("profiles") != ["car", "foot"] or not isinstance(info.get("elevation"), bool):
            raise ValueError("pack.json must describe the car and foot profiles and elevation flag")
        bounds = info.get("bounds")
        if not isinstance(bounds, list) or len(bounds) != 4 or not all(isinstance(value, (int, float)) and math.isfinite(value) for value in bounds):
            raise ValueError("pack.json has invalid bounds")
        if not (-90 <= bounds[0] <= bounds[1] <= 90 and -180 <= bounds[2] <= bounds[3] <= 180):
            raise ValueError("pack.json has out-of-range bounds")
        graph_bytes = sum(entry.file_size for entry in entries if entry.filename != "pack.json")
        if info.get("sizeBytes") != graph_bytes:
            raise ValueError(f"pack.json sizeBytes={info.get('sizeBytes')} differs from graph bytes={graph_bytes}")
        bad = archive.testzip()
        if bad is not None:
            raise ValueError(f"routing archive has a corrupt file: {bad}")
    return info


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="routing pack ZIP produced by :routing:run")
    args = parser.parse_args()
    info = verify(args.archive)
    print(f"Valid routing pack: {info['name']} ({', '.join(info['profiles'])}), {args.archive.stat().st_size} ZIP bytes")


if __name__ == "__main__":
    main()
