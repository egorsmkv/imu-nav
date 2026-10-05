"""Check that the hosted build emits one valid map for ZIP and direct APK access."""

import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from make_map_pack import package, used_layers  # noqa: E402
from verify_map_pack import verify  # noqa: E402


class MapPackTests(unittest.TestCase):
    def test_packaging_keeps_full_detail_and_identical_distribution_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tiles = root / "region.pmtiles"
            header = bytearray(127)
            header[:8] = b"PMTiles\x03"
            header[100:102] = bytes((0, 14))
            struct.pack_into("<iiii", header, 102, 30_0000000, 45_0000000, 40_0000000, 55_0000000)
            tiles.write_bytes(header + b"tile data")
            resources = root / "resources"
            resources.mkdir()
            for name, layer in (("style-light.json", "transportation"), ("style-dark.json", "water")):
                style = {
                    "sources": {"openmaptiles": {"url": "pmtiles://{PACK_URI}/tiles.pmtiles"}},
                    "glyphs": "{PACK_URI}/fonts/{fontstack}/{range}.pbf",
                    "layers": [{"source": "openmaptiles", "source-layer": layer}],
                }
                (resources / name).write_text(json.dumps(style))
            self.assertEqual(used_layers([json.loads(path.read_text()) for path in resources.glob("style-*.json")]), {"transportation", "water"})
            sprites = resources / "sprites"
            sprites.mkdir()
            for name in ("ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png"):
                (sprites / name).write_bytes(b"sprite")
            archive = root / "map.zip"
            assets = root / "assets"
            package(tiles, resources, archive, "Region", assets, "abc123")
            verify(archive, assets)
            with zipfile.ZipFile(archive) as packed:
                self.assertEqual(packed.getinfo("tiles.pmtiles").compress_type, zipfile.ZIP_STORED)
                self.assertEqual(json.loads(packed.read("pack.json"))["sourceSha256"], "abc123")

    def test_missing_style_layer_is_rejected(self):
        self.assertEqual(used_layers([{"layers": [{"source": "other", "source-layer": "road"}]}]), set())


if __name__ == "__main__":
    unittest.main()
