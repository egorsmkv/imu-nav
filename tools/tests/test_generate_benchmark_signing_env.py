"""Identity preservation, credential secrecy and shell compatibility for signing setup."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location('generate_signing_env', TOOLS / 'generate_benchmark_signing_env.py')
generator = importlib.util.module_from_spec(SPEC)
with patch.object(sys, 'path', [str(TOOLS), *sys.path]):
    SPEC.loader.exec_module(generator)


class SigningEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'bundle'
        self.calls = []
        self.certificate = b'synthetic certificate'
        self.key = b'synthetic private key'

    def keytool(self, arguments, env):
        self.calls.append((arguments, env.copy()))
        if arguments[0] == 'whoami':
            return '"fixture user","S-1-5-21-1-2-3-1000"\n'
        if '-genkeypair' in arguments:
            Path(arguments[arguments.index('-keystore') + 1]).write_bytes(self.key)
        if '-exportcert' in arguments:
            Path(arguments[arguments.index('-file') + 1]).write_bytes(self.certificate)
        if '-certreq' in arguments:
            Path(arguments[arguments.index('-file') + 1]).write_bytes(b'synthetic request')
        return ''

    def prepare(self, **kwargs):
        with patch.object(generator, 'run_private', side_effect=self.keytool):
            return generator.generate(self.output, keytool='keytool', env={}, **kwargs)

    def settings(self):
        return json.loads((self.output / 'github-settings.json').read_text())

    def test_new_bundle_matches_the_signer_and_github_configuration(self):
        self.prepare()
        settings = self.settings()
        secrets = settings['secrets']
        self.assertEqual(set(secrets), {name.removeprefix('IMUNAV_') for name in generator.KEY_VARIABLES})
        self.assertEqual(base64.b64decode(secrets['BENCHMARK_KEYSTORE_BASE64']), self.key)
        self.assertEqual(secrets['BENCHMARK_KEY_ALIAS'], generator.DEFAULT_ALIAS)
        self.assertNotEqual(secrets['BENCHMARK_STORE_PASSWORD'], secrets['BENCHMARK_KEY_PASSWORD'])
        self.assertGreaterEqual(len(secrets['BENCHMARK_STORE_PASSWORD']), 32)
        self.assertEqual(settings['variables']['BENCHMARK_CERT_SHA256'], hashlib.sha256(self.certificate).hexdigest())
        self.assertEqual(set(path.name for path in self.output.iterdir()),
                         {'benchmark.jks', 'env.sh', 'env.ps1', 'github-settings.json'})
        for arguments, env in self.calls:
            for name in (generator.KEY_VARIABLES[1], generator.KEY_VARIABLES[3]):
                self.assertNotIn(env[name], [str(value) for value in arguments])
        if os.name != 'nt':
            self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)
            for filename in self.output.iterdir():
                self.assertEqual(filename.stat().st_mode & 0o777, 0o600)

    def test_reusing_a_key_preserves_its_source_and_existing_passwords(self):
        source = self.root / 'existing.jks'
        source.write_bytes(self.key)
        env = {generator.KEY_VARIABLES[1]: "store secret '$\\\"",
               generator.KEY_VARIABLES[3]: "key secret '$\\\""}
        with patch.object(generator, 'run_private', side_effect=self.keytool):
            generator.generate(self.output, alias="fixture's key", keystore=source, keytool='keytool', env=env)
        self.assertEqual(source.read_bytes(), self.key)
        settings = self.settings()['secrets']
        self.assertEqual(settings['BENCHMARK_STORE_PASSWORD'], env[generator.KEY_VARIABLES[1]])
        self.assertEqual(settings['BENCHMARK_KEY_PASSWORD'], env[generator.KEY_VARIABLES[3]])
        self.assertFalse(any('-genkeypair' in arguments for arguments, _ in self.calls))
        self.assertTrue(any('-certreq' in arguments for arguments, _ in self.calls))
        encoded_alias = base64.b64encode(b"fixture's key").decode('ascii')
        self.assertIn(encoded_alias, (self.output / 'env.ps1').read_text(encoding='utf-8-sig'))

    def test_existing_outputs_are_never_replaced(self):
        self.output.mkdir()
        sentinel = self.output / 'benchmark.jks'
        sentinel.write_bytes(self.key)
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.prepare()
        self.assertEqual(sentinel.read_bytes(), self.key)
        self.assertEqual(self.calls, [])

    def test_loaded_credentials_cannot_silently_generate_a_new_identity(self):
        for name in (*generator.KEY_VARIABLES, generator.FINGERPRINT_VARIABLE):
            with self.subTest(variable=name), self.assertRaisesRegex(ValueError, 'already loaded'):
                generator.generate(self.output, keytool='keytool', env={name: 'synthetic loaded credential'})
        self.assertFalse(self.output.exists())

    def test_checkout_outputs_must_remain_ignored(self):
        with self.assertRaisesRegex(ValueError, 'ignored keystore'):
            generator.generate(generator.ROOT / 'tools' / 'accidental-key-output', keytool='keytool', env={})

    def test_wrong_private_key_password_never_publishes_credentials(self):
        source = self.root / 'existing.jks'
        source.write_bytes(self.key)
        env = {generator.KEY_VARIABLES[1]: 'fixture store password', generator.KEY_VARIABLES[3]: 'incorrect fixture password'}

        def fail(arguments, environment):
            if '-certreq' in arguments:
                raise ValueError('Signing-key preparation failed; check keytool, alias and passwords')
            return self.keytool(arguments, environment)

        with patch.object(generator, 'run_private', side_effect=fail), self.assertRaises(ValueError):
            generator.generate(self.output, keystore=source, keytool='keytool', env=env)
        self.assertEqual(source.read_bytes(), self.key)
        self.assertFalse(self.output.exists())

    def test_reused_identity_must_match_an_existing_certificate_pin(self):
        source = self.root / 'existing.jks'
        source.write_bytes(self.key)
        env = {generator.KEY_VARIABLES[1]: 'fixture store', generator.KEY_VARIABLES[3]: 'fixture key',
               generator.FINGERPRINT_VARIABLE: 'ab' * 32}
        with patch.object(generator, 'run_private', side_effect=self.keytool), self.assertRaisesRegex(ValueError, 'fingerprint'):
            generator.generate(self.output, keystore=source, keytool='keytool', env=env)
        self.assertFalse(self.output.exists())

    def test_keytool_errors_do_not_expose_diagnostics(self):
        result = subprocess.CompletedProcess([], 1, 'fixture-private-key', 'fixture-password')
        with patch.object(generator.subprocess, 'run', return_value=result), self.assertRaises(ValueError) as raised:
            generator.run_private(['keytool'], {})
        self.assertNotIn('fixture-private-key', str(raised.exception))
        self.assertNotIn('fixture-password', str(raised.exception))

    def test_windows_acl_is_applied_to_the_current_sid_before_secret_creation(self):
        with patch.object(generator.os, 'name', 'nt'), patch.object(generator, 'run_private', side_effect=self.keytool):
            generator.protect_directory(self.root, {})
        self.assertEqual(self.calls[1][0], ['icacls', self.root, '/inheritance:r', '/grant:r', '*S-1-5-21-1-2-3-1000:(OI)(CI)F'])

    def test_acl_failures_stop_before_key_generation(self):
        with patch.object(generator, 'protect_directory', side_effect=ValueError('Could not restrict directory')), \
                patch.object(generator, 'run_private') as keytool, self.assertRaises(ValueError):
            generator.generate(self.output, keytool='keytool', env={})
        keytool.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_noninteractive_reuse_requires_environment_passwords(self):
        source = self.root / 'existing.jks'
        source.write_bytes(self.key)
        with patch.object(generator.sys.stdin, 'isatty', return_value=False), self.assertRaisesRegex(ValueError, 'interactive terminal'):
            generator.generate(self.output, keystore=source, keytool='keytool', env={})
        self.assertFalse(self.output.exists())

    def test_cli_never_prints_generated_values(self):
        output = io.StringIO()
        with patch.object(sys, 'argv', ['generator']), patch.object(generator, 'generate'), contextlib.redirect_stdout(output):
            generator.main()
        self.assertIn('no secrets were uploaded', output.getvalue())
        self.assertNotIn('KEYSTORE_BASE64=', output.getvalue())

    def test_shell_exports_roundtrip_literal_passwords(self):
        shell = shutil.which('sh') if os.name != 'nt' else shutil.which('powershell')
        if shell is None:
            self.skipTest('No compatible shell installed')
        values = {name: "fixture '$\\\" literal ’; throw 'not code'; #\n next=42" for name in generator.KEY_VARIABLES}
        values[generator.FINGERPRINT_VARIABLE] = 'ab' * 32
        generator.write_exports(self.root, values)
        expression = 'import json, os; print(json.dumps({key:os.environ[key] for key in ' + repr(list(values)) + '}))'
        if os.name == 'nt':
            script = str(self.root / 'env.ps1').replace("'", "''")
            helper = self.root / 'read-env.py'
            helper.write_text(expression)
            command = f". '{script}'; & '{sys.executable}' '{helper}'"
            arguments = [shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', command]
        else:
            arguments = [shell, '-c', '. "$1"; "$2" -c "$3"', 'test', str(self.root / 'env.sh'), sys.executable, expression]
        result = subprocess.run(arguments, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout), values)


class RealKeytoolTest(unittest.TestCase):
    def test_generated_and_reused_keystores_have_the_same_verified_private_identity(self):
        environment = dict(os.environ)
        for name in (*generator.KEY_VARIABLES, generator.FINGERPRINT_VARIABLE):
            environment.pop(name, None)
        try:
            keytool = generator.locate_keytool(environment)
        except ValueError:
            self.skipTest('JDK keytool not installed')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = generator.generate(root / 'original', env=environment, keytool=keytool)
            settings = json.loads((original / 'github-settings.json').read_text())
            environment.update({'IMUNAV_' + name: value for name, value in settings['secrets'].items()})
            environment[generator.FINGERPRINT_VARIABLE] = settings['variables']['BENCHMARK_CERT_SHA256']
            repeated = generator.generate(root / 'repeated', keystore=original / 'benchmark.jks', env=environment, keytool=keytool)
            self.assertEqual((original / 'github-settings.json').read_bytes(), (repeated / 'github-settings.json').read_bytes())
            environment[generator.KEY_VARIABLES[3]] = 'wrong-fixture-private-key-password'
            with self.assertRaisesRegex(ValueError, 'Signing-key preparation failed'):
                generator.generate(root / 'rejected', keystore=original / 'benchmark.jks', env=environment, keytool=keytool)
            self.assertFalse((root / 'rejected').exists())


if __name__ == '__main__':
    unittest.main()
