"""Check the routing archive validator used by the routing-pack workflow."""

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from verify_routing_pack import verify  # noqa: E402


def write_pack(path: Path, native: bool = False, **overrides) -> None:
    """A minimal flat archive with a valid pack.json; [overrides] replace pack.json fields."""
    files = {"properties": b"graph", "search.db": b"SQLite format 3\x00" + b"index"}
    if native:
        files = {"properties": b"graph", "search.tantivy/search-format": b"imu-nav-tantivy-1\n", "search.tantivy/meta.json": b'{"segments": [], "schema": []}'}
    info = {
        "name": "Test",
        "builtAt": "2026-10-24T00:00:00Z",
        "source": "test.osm.pbf",
        "graphhopper": "11.0",
        "bounds": [44.0, 52.0, 22.0, 40.0],
        "sizeBytes": sum(len(data) for data in files.values()),
        "profiles": ["car", "foot"],
        "elevation": False,
        "turnCosts": True,
        "searchFormat": "tantivy-1" if native else None,
    }
    info.update(overrides)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr("pack.json", json.dumps({key: value for key, value in info.items() if value is not None}))


class VerifyRoutingPackTests(unittest.TestCase):
    def test_current_pack_with_turn_costs_is_valid(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "routing.zip"
            write_pack(archive)
            self.assertTrue(verify(archive)["turnCosts"])

    def test_native_directory_and_format_are_validated(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "routing.zip"
            write_pack(archive, native=True)
            self.assertTrue(verify(archive)["turnCosts"])
            with zipfile.ZipFile(archive, "a") as pack:
                pack.writestr("search.tantivy/../escape", b"invalid")
            with self.assertRaisesRegex(ValueError, "subtree"):
                verify(archive)

    def test_search_format_must_match_index(self):
        for native, search_format in ((True, None), (True, "tantivy-2"), (False, "tantivy-1")):
            with self.subTest(native=native, searchFormat=search_format), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "routing.zip"
                write_pack(archive, native=native, searchFormat=search_format)
                with self.assertRaisesRegex(ValueError, "searchFormat"):
                    verify(archive)

    def test_pack_without_turn_costs_is_rejected(self):
        for value in (False, None):
            with self.subTest(turnCosts=value), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "routing.zip"
                write_pack(archive, turnCosts=value)
                with self.assertRaisesRegex(ValueError, "turnCosts"):
                    verify(archive)


if __name__ == "__main__":
    unittest.main()
