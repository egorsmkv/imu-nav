#!/usr/bin/env python3
"""Reproduce the locally served MapLibre 5.20.0 CSP assets, verifying the npm archive."""
import base64
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request

VERSION = "5.20.0"
SHA512 = "hUQ/4KkxVKLbAD4coW+9/tJ9/jOKKcN7q4F92EQ5mjbUJ2m1sz6uoiB3VqW/VaogUxmWd896l1cc9TtV4+uvJA=="
FILES = {
    "dist/maplibre-gl-csp.js": "maplibre-gl-csp.js",
    "dist/maplibre-gl-csp-worker.js": "maplibre-gl-csp-worker.js",
    "dist/maplibre-gl.css": "maplibre-gl.css",
    "LICENSE.txt": "license.txt",
}


def main():
    target = Path(__file__).resolve().parents[1] / "server/static/maplibre"
    with urllib.request.urlopen(f"https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-{VERSION}.tgz", timeout=60) as response:
        archive = response.read(32 * 1024 * 1024)
    if base64.b64encode(hashlib.sha512(archive).digest()).decode() != SHA512:
        raise ValueError("MapLibre archive checksum mismatch")
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
        for source, destination in FILES.items():
            with package.extractfile("package/" + source) as entry:
                (target / destination).write_bytes(entry.read())


if __name__ == "__main__":
    main()
