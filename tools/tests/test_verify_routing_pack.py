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
    files = {"shared.routing": b"IMUGEO02", "car.routing": b"IMURT002", "foot.routing": b"IMURT002", "search.db": b"SQLite format 3\x00" + b"index"}
    if native:
        files = {"shared.routing": b"IMUGEO02", "car.routing": b"IMURT002", "foot.routing": b"IMURT002", "search.tantivy/search-format": b"imu-nav-tantivy-1\n", "search.tantivy/meta.json": b'{"segments": [], "schema": []}'}
    info = {
        "name": "Test",
        "builtAt": "2026-10-24T00:00:00Z",
        "source": "test.osm.pbf",
        "routingFormat": "rust-ch-2",
        "bounds": [44.0, 52.0, 22.0, 40.0],
        "sizeBytes": sum(len(data) for data in files.values()),
        "profiles": ["car", "foot"],
        "elevation": False,
        "turnCosts": True,
        "searchFormat": "tantivy-1" if native else None,
    }
    info.update(overrides)
    if "profiles" in overrides:
        for profile in ("car", "foot"):
            if profile not in info["profiles"]:
                files.pop(f"{profile}.routing")
        info["sizeBytes"] = sum(len(data) for data in files.values())
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

    def test_single_mode_archives_are_valid(self):
        for profiles in (["car"], ["foot"]):
            with self.subTest(profiles=profiles), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "routing.zip"
                write_pack(archive, native=True, profiles=profiles)
                self.assertEqual(verify(archive)["profiles"], profiles)
                with zipfile.ZipFile(archive, "a") as pack:
                    other = "foot" if profiles == ["car"] else "car"
                    pack.writestr(f"{other}.routing", b"IMURT002")
                with self.assertRaisesRegex(ValueError, "declared profiles"):
                    verify(archive)

    def test_empty_or_unknown_profiles_are_rejected(self):
        for profiles in ([], ["car", "car"], ["bicycle"]):
            with self.subTest(profiles=profiles), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "routing.zip"
                write_pack(archive, profiles=profiles)
                with self.assertRaisesRegex(ValueError, "profiles"):
                    verify(archive)

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

    def test_legacy_routing_format_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "routing.zip"
            write_pack(archive, routingFormat="rust-ch-1")
            with self.assertRaisesRegex(ValueError, "Rust routing format"):
                verify(archive)

    def test_missing_shared_routing_data_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            original = Path(temporary) / "original.zip"
            missing = Path(temporary) / "missing.zip"
            write_pack(original, native=True)
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(missing, "w") as target:
                for item in source.infolist():
                    if item.filename != "shared.routing":
                        target.writestr(item, source.read(item))
            with self.assertRaisesRegex(ValueError, "shared.routing"):
                verify(missing)

    def test_pack_without_turn_costs_is_rejected(self):
        for value in (False, None):
            with self.subTest(turnCosts=value), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "routing.zip"
                write_pack(archive, turnCosts=value)
                with self.assertRaisesRegex(ValueError, "turnCosts"):
                    verify(archive)


if __name__ == "__main__":
    unittest.main()
