#!/usr/bin/env python3
"""Check that a routing ZIP has the graph files, search index and metadata required by the app."""

import argparse
import json
import math
import zipfile
from pathlib import Path


def verify(archive_path: Path) -> dict:
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        def valid_name(name: str) -> bool:
            parts = name.split("/")
            return "\\" not in name and all(part not in ("", ".", "..") for part in parts) and (
                len(parts) == 1 or (len(parts) == 2 and parts[0] == "search.tantivy")
            )
        if len(names) != len(set(names)) or any(entry.is_dir() or not valid_name(entry.filename) for entry in entries):
            raise ValueError("routing archive must contain unique graph files and a search.tantivy subtree")
        native = any(name.startswith("search.tantivy/") for name in names)
        required = {"pack.json", "car.routing", "foot.routing", "shared.routing"} | (
            {"search.tantivy/search-format", "search.tantivy/meta.json"} if native else {"search.db"}
        )
        if not required.issubset(names):
            raise ValueError(f"routing archive is missing {sorted(required - set(names))}")
        if any(archive.getinfo(name).file_size == 0 for name in required):
            raise ValueError("routing archive has an empty required file")
        if native:
            if archive.getinfo("search.tantivy/search-format").file_size > 128 or archive.read("search.tantivy/search-format") != b"imu-nav-tantivy-1\n":
                raise ValueError("unsupported Tantivy search format")
            if archive.getinfo("search.tantivy/meta.json").file_size > 4 * 1024 * 1024:
                raise ValueError("Tantivy metadata exceeds size limit")
            metadata = json.loads(archive.read("search.tantivy/meta.json"))
            if not isinstance(metadata, dict) or not isinstance(metadata.get("segments"), list) or not isinstance(metadata.get("schema"), list):
                raise ValueError("invalid Tantivy metadata")
        else:
            with archive.open("search.db") as search:
                if search.read(16) != b"SQLite format 3\x00":
                    raise ValueError("search.db is not a SQLite database")
        if archive.getinfo("pack.json").file_size > 65536:
            raise ValueError("routing metadata exceeds size limit")
        for profile in ("car", "foot"):
            with archive.open(f"{profile}.routing") as graph:
                if graph.read(8) != b"IMURT002":
                    raise ValueError("unsupported Rust routing data")
        info = json.loads(archive.read("pack.json"))
        if isinstance(info, dict) and (info.get("searchFormat") not in (None, "tantivy-1") or (native and info.get("searchFormat") != "tantivy-1") or (not native and info.get("searchFormat") is not None)):
            raise ValueError("pack.json searchFormat does not match the search index")
        with archive.open("shared.routing") as shared:
            if shared.read(8) != b"IMUGEO02":
                raise ValueError("unsupported shared routing data")
        if not isinstance(info, dict) or not info.get("name") or info.get("routingFormat") != "rust-ch-2":
            raise ValueError("pack.json has invalid name or Rust routing format")
        if info.get("profiles") != ["car", "foot"] or not isinstance(info.get("elevation"), bool):
            raise ValueError("pack.json must describe the car and foot profiles and elevation flag")
        # Current builders store turn restrictions and turn penalties; a pack without them is stale.
        if info.get("turnCosts") is not True:
            raise ValueError("pack.json must declare turnCosts: true (rebuild the pack with the current :routing builder)")
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
