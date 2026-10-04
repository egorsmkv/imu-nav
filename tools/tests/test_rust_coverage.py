"""Regression checks for reports that could otherwise silently overstate coverage."""
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('coverage_runner', Path(__file__).parents[1] / 'rust_coverage.py')
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


class CoverageTests(unittest.TestCase):
    def test_proof_harnesses_do_not_count_as_production(self):
        for module in ['', 'trust/', 'network/', 'speed/', 'estimator/', 'route/', 'estimator/network_position/',
                       'estimator/network_position/speed/', 'estimator/motion/', 'estimator/walking/', 'estimator/turn/']:
            for suffix in ['kani_proofs.rs', 'kani_proofs/covariance.rs', 'kani_proofs/nested/contracts.rs']:
                proof = coverage.ROOT / f'native/nav-core/src/{module}{suffix}'
                with self.subTest(proof=proof):
                    self.assertIsNone(coverage.production_file(proof))
                    self.assertEqual(coverage.merge_lcov([f'SF:{proof}\nDA:1,0\nend_of_record']), {})
                    self.assertIsNotNone(re.search(coverage.LLVM_EXCLUSION, proof.as_posix()))
        self.assertEqual(coverage.production_file(coverage.ROOT / 'native/nav-core/src/trust.rs'), 'core')

    def test_proof_exclusion_does_not_hide_similarly_named_production(self):
        for relative in ['covariance.rs', 'kani_proofs_helpers.rs', 'kani_proofs_helpers/covariance.rs',
                         'estimator/covariance.rs']:
            source = coverage.ROOT / 'native/nav-core/src' / relative
            with self.subTest(source=source):
                self.assertEqual(coverage.production_file(source), 'core')
                self.assertIsNone(re.search(coverage.LLVM_EXCLUSION, source.as_posix()))
                self.assertEqual(coverage.merge_lcov([f'SF:{source}\nDA:1,0\nend_of_record']),
                                 {str(source): {1: 0}})

    def test_nested_proofs_are_optional_but_production_siblings_are_required(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(coverage, 'ROOT', Path(directory)):
            sources = {}
            for relative in ['native/nav-core/src/lib.rs', 'native/nav-core/src/trust.rs',
                             'native/nav-jni/src/lib.rs', 'native/nav-sim/src/main.rs']:
                source = coverage.ROOT / relative
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('fn production() {}')
                sources[str(source)] = {1: 1}
            proof = coverage.ROOT / 'native/nav-core/src/kani_proofs/covariance.rs'
            proof.parent.mkdir()
            proof.write_text('fn covariance_proof() {}')
            self.assertTrue(all(item['passed'] for item in coverage.summarize(sources, 'native').values()))
            production = coverage.ROOT / 'native/nav-core/src/covariance.rs'
            production.write_text('fn production_covariance() {}')
            with self.assertRaisesRegex(RuntimeError, 'Missing production module'):
                coverage.summarize(sources, 'native')

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
