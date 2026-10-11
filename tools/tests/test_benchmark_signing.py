"""Secret handling and artifact policy regressions; Android itself checks updates in emulator CI."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('benchmark_signing', Path(__file__).parents[1] / 'benchmark_signing.py')
signing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signing)


class BenchmarkSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'build' / 'app-fdroid-benchmark-unsigned.apk'
        self.apk.parent.mkdir()
        self.apk.write_bytes(b'synthetic unsigned input; apksigner is isolated in unit tests')
        self.output = self.root / 'distribution'
        self.env = dict(zip(signing.KEY_VARIABLES, (base64.b64encode(b'synthetic key').decode(),
                                                   'store-secret', 'alias-secret', 'key-secret')))
        self.digest = 'ab' * 32

    def test_missing_key_is_explicitly_unsigned_and_preserves_filename(self):
        with patch.object(signing.subprocess, 'run') as run:
            report = signing.sign(self.apk, self.output, 'apksigner', allow_unsigned=True, env={})
        run.assert_not_called()
        self.assertEqual(report['mode'], 'unsigned')
        self.assertIsNone(report['certificate_sha256'])
        self.assertEqual(report['apk'], self.apk.name)
        self.assertEqual((self.output / report['apk']).read_bytes(), self.apk.read_bytes())
        self.assertEqual(json.loads((self.output / 'benchmark-signing.json').read_text()), report)

    def test_no_key_is_rejected_without_explicit_unsigned_opt_in(self):
        with self.assertRaisesRegex(ValueError, 'No durable'):
            signing.sign(self.apk, self.output, 'apksigner', env={})
        self.assertFalse(self.output.exists())

    def test_partial_secrets_never_fall_back_to_unsigned(self):
        for name in signing.KEY_VARIABLES:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'all four'):
                signing.sign(self.apk, self.output, 'apksigner', True, {name: self.env[name]})
        self.assertFalse(self.output.exists())

    def test_malformed_key_is_redacted(self):
        self.env[signing.KEY_VARIABLES[0]] = 'private-invalid-base64-value'
        with self.assertRaises(ValueError) as raised:
            signing.sign(self.apk, self.output, 'apksigner', env=self.env)
        self.assertNotIn('private-invalid', str(raised.exception))
        self.assertFalse(self.output.exists())

    def test_passwords_use_environment_and_temporary_key_is_removed(self):
        key_paths = []

        def signer(arguments, **kwargs):
            self.assertNotIn('store-secret', arguments)
            self.assertNotIn('key-secret', arguments)
            if arguments[1] == 'sign':
                self.assertIn('env:' + signing.KEY_VARIABLES[1], arguments)
                self.assertIn('env:' + signing.KEY_VARIABLES[3], arguments)
                key = Path(arguments[arguments.index('--ks') + 1])
                key_paths.append(key)
                self.assertEqual(key.read_bytes(), b'synthetic key')
                self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                Path(arguments[arguments.index('--out') + 1]).write_bytes(b'synthetic signed output')
                return subprocess.CompletedProcess(arguments, 0, '', '')
            return subprocess.CompletedProcess(arguments, 0, f'Signer #1 certificate SHA-256 digest: {self.digest}\n', '')

        self.env[signing.FINGERPRINT_VARIABLE] = ':'.join(['AB'] * 32)
        with patch.object(signing.subprocess, 'run', side_effect=signer):
            report = signing.sign(self.apk, self.output, 'apksigner', env=self.env, temporary_root=self.root)
        self.assertEqual(report['mode'], 'signed')
        self.assertEqual(report['certificate_sha256'], self.digest)
        self.assertEqual(report['apk'], 'app-fdroid-benchmark.apk')
        self.assertTrue(all(not key.exists() for key in key_paths))
        self.assertNotIn('secret', json.dumps(report))

    def test_signer_errors_do_not_expose_diagnostics(self):
        result = subprocess.CompletedProcess([], 1, 'store-secret', 'alias-secret key-secret')
        with patch.object(signing.subprocess, 'run', return_value=result), self.assertRaises(ValueError) as raised:
            signing.sign(self.apk, self.output, 'apksigner', env=self.env, temporary_root=self.root)
        self.assertNotIn('secret', str(raised.exception))
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('imunav-benchmark-signing-*')), [])

    def test_verification_rejects_missing_multiple_and_wrong_certificates(self):
        for digests in ([], [self.digest, self.digest], ['cd' * 32]):
            with self.subTest(digests=digests):
                output = ''.join(f'Signer #{index + 1} certificate SHA-256 digest: {digest}\n' for index, digest in enumerate(digests))
                result = subprocess.CompletedProcess([], 0, output, '')
                with patch.object(signing.subprocess, 'run', return_value=result), self.assertRaises(ValueError):
                    signing.verify('apksigner', self.apk, {}, self.digest)

    def test_stale_output_and_non_unsigned_inputs_are_rejected(self):
        self.output.mkdir()
        (self.output / 'previous-signer.apk').touch()
        with self.assertRaisesRegex(ValueError, 'must be empty'):
            signing.sign(self.apk, self.output, 'apksigner', True, {})
        with self.assertRaisesRegex(ValueError, 'separate'):
            signing.sign(self.apk, self.apk.parent, 'apksigner', True, {})
        with self.assertRaisesRegex(ValueError, 'unsigned.apk'):
            signing.sign(self.root / 'signed.apk', self.output, 'apksigner', True, {})


if __name__ == '__main__':
    unittest.main()
