"""Regression checks for reports that could otherwise silently overstate coverage."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('coverage_runner', Path(__file__).parents[1] / 'rust_coverage.py')
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


class CoverageTests(unittest.TestCase):
    def test_discovers_cargo_reported_paths_not_a_guessed_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'arbitrary-layout' / 'test-binary'
            binary.parent.mkdir()
            binary.touch()
            item = {'reason': 'compiler-artifact', 'target': {
                'src_path': str(coverage.ROOT / 'native/nav-core/src/lib.rs'), 'kind': ['lib']},
                'profile': {'test': True}, 'executable': str(binary), 'filenames': [str(binary)]}
            output = 'running 10 tests\n' + json.dumps(item)
            self.assertEqual(coverage.artifacts(output), [str(binary)])
            binary.unlink()
            with self.assertRaisesRegex(RuntimeError, 'Missing Cargo artifacts'):
                coverage.artifacts(output)
            with self.assertRaisesRegex(RuntimeError, 'no coverage objects'):
                coverage.artifacts('[]\n'.replace('[]', '{}'))

    def test_cdylib_discovery_does_not_use_test_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / 'libimu_nav_jni.so'
            library.touch()
            item = {'reason': 'compiler-artifact', 'target': {
                'src_path': str(coverage.ROOT / 'native/nav-jni/src/lib.rs'), 'kind': ['cdylib']},
                'profile': {'test': False}, 'executable': None, 'filenames': [str(library)]}
            self.assertEqual(coverage.artifacts(json.dumps(item), library=True), [str(library)])
            with self.assertRaises(RuntimeError):
                coverage.artifacts(json.dumps(item))

    def test_missing_required_application_cannot_be_hidden_by_test_coverage(self):
        item = {'reason': 'compiler-artifact', 'target': {'name': 'imu_nav_jni', 'kind': ['cdylib']},
                'profile': {'test': True}, 'executable': '/test-binary'}
        coverage.validate_artifact_set(json.dumps(item), 'heap-tests')
        with self.assertRaisesRegex(RuntimeError, 'Incomplete native artifacts'):
            coverage.validate_artifact_set(json.dumps(item), 'native')

    def test_union_counts_uncovered_lines_and_excludes_tests_and_dependencies(self):
        source = str(coverage.ROOT / 'native/nav-core/src/trust.rs')
        tests = str(coverage.ROOT / 'native/nav-core/src/trust/tests.rs')
        dependency = '/external/dependency.rs'
        first = f'SF:{source}\nDA:1,2\nDA:2,0\nend_of_record\nSF:{tests}\nDA:1,1\nend_of_record'
        second = f'SF:{source}\nDA:1,0\nDA:2,1\nDA:3,0\nend_of_record\nSF:{dependency}\nDA:1,1\nend_of_record'
        self.assertEqual(coverage.merge_lcov([first, second]), {source: {1: 2, 2: 1, 3: 0}})
        for path in ['native/nav-core/src/network_speed_tests.rs', 'native/nav-core/tests/contracts.rs', 'native/nav-sim/build.rs']:
            self.assertIsNone(coverage.production_file(coverage.ROOT / path))

    def test_empty_or_zero_byte_jvm_profiles_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, 'No nonempty execution profiles'):
                coverage.require_profiles(root)
            profile = root / 'jvm.profraw'
            profile.touch()
            with self.assertRaises(RuntimeError):
                coverage.require_profiles(root)
            profile.write_bytes(b'raw profile')
            self.assertEqual(coverage.require_profiles(root), [profile])

    def test_gates_compare_exact_counts_before_rounding(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(coverage, 'ROOT', Path(directory)):
            root = Path(directory) / 'server/src'
            root.mkdir(parents=True)
            source = root / 'main.rs'
            source.write_text('fn main() {}')
            lines = dict.fromkeys(range(10001), 1)
            for number in range(1001):
                lines[number] = 0
            item = coverage.summarize({str(source): lines}, 'server')['server']
            self.assertEqual(round(item['percent'], 1), 90.0)
            self.assertFalse(item['passed'])
            lines[0] = 1
            self.assertTrue(coverage.summarize({str(source): lines}, 'server')['server']['passed'])
            (root / 'missing.rs').write_text('fn uncovered() {}')
            with self.assertRaisesRegex(RuntimeError, 'Missing production module'):
                coverage.summarize({str(source): lines}, 'server')
            with self.assertRaisesRegex(RuntimeError, 'No production coverage'):
                coverage.summarize({}, 'server')

    def test_successful_export_with_mapping_warning_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'export.log'
            def warned(*args, **kwargs):
                kwargs['stdout'].write('warning: 7 functions have mismatched data\n')
                return type('Process', (), {'returncode': 0})()
            with patch.object(coverage.subprocess, 'run', side_effect=warned):
                with self.assertRaisesRegex(RuntimeError, 'profile validation failed'):
                    coverage.checked(['llvm-cov', 'export'], {}, log)


if __name__ == '__main__':
    unittest.main()
