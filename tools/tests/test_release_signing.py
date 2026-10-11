"""Production credentials stay separate and are available only to trusted distribution builds."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location('release_signing_common', TOOLS / 'benchmark_signing.py')
signing = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(signing)
GENERATOR_SPEC = importlib.util.spec_from_file_location('release_generator', TOOLS / 'generate_benchmark_signing_env.py')
generator = importlib.util.module_from_spec(GENERATOR_SPEC)
with patch.object(sys, 'path', [str(TOOLS), *sys.path]):
    GENERATOR_SPEC.loader.exec_module(generator)


class ReleaseSigningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'app-fdroid-release-unsigned.apk'
        self.apk.write_bytes(b'synthetic unsigned APK')
        self.output = self.root / 'distribution'
        self.variables = signing.credential_variables('release')
        self.env = dict(zip(self.variables, (base64.b64encode(b'fixture release key').decode(),
                                             'fixture store password', 'fixture alias', 'fixture key password')))
        self.digest = 'cd' * 32

    def test_release_never_falls_back_to_benchmark_credentials(self):
        benchmark = dict(zip(signing.KEY_VARIABLES, self.env.values()))
        with self.assertRaisesRegex(ValueError, 'No durable release'):
            signing.sign(self.apk, self.output, 'apksigner', env=benchmark, profile='release')
        report = signing.sign(self.apk, self.output, 'apksigner', True, benchmark, profile='release')
        self.assertEqual(report['mode'], 'unsigned')
        self.assertTrue((self.output / 'release-signing.json').exists())
        self.assertFalse((self.output / 'benchmark-signing.json').exists())

    def test_partial_release_credentials_fail_even_for_validation(self):
        for variable in self.variables:
            with self.subTest(variable=variable), self.assertRaisesRegex(ValueError, 'all four IMUNAV_RELEASE'):
                signing.sign(self.apk, self.output, 'apksigner', True, {variable: self.env[variable]}, profile='release')
        self.assertFalse(self.output.exists())

    def test_release_signing_preserves_input_and_verifies_its_own_pin(self):
        keys = []
        original = self.apk.read_bytes()

        def signer(arguments, env):
            if arguments[1] == 'sign':
                self.assertEqual(env, self.env)
                self.assertIn('env:IMUNAV_RELEASE_STORE_PASSWORD', arguments)
                self.assertIn('env:IMUNAV_RELEASE_KEY_PASSWORD', arguments)
                self.assertNotIn('fixture store password', arguments)
                key = Path(arguments[arguments.index('--ks') + 1])
                keys.append(key)
                self.assertEqual(key.read_bytes(), b'fixture release key')
                Path(arguments[arguments.index('--out') + 1]).write_bytes(b'fixture signed APK')
                return ''
            return f'Signer #1 certificate SHA-256 digest: {self.digest}\n'

        self.env[signing.certificate_variable('release')] = self.digest
        with patch.object(signing, 'run_signer', side_effect=signer):
            report = signing.sign(self.apk, self.output, 'apksigner', env=self.env, profile='release')
        self.assertEqual(report['mode'], 'signed')
        self.assertEqual(report['apk'], 'app-fdroid-release.apk')
        self.assertEqual(report['certificate_sha256'], self.digest)
        self.assertEqual(self.apk.read_bytes(), original)
        self.assertTrue(all(not key.exists() for key in keys))
        self.assertEqual(json.loads((self.output / 'release-signing.json').read_text()), report)
        self.assertNotIn('password', json.dumps(report))

    def test_wrong_release_pin_never_publishes(self):
        self.env[signing.certificate_variable('release')] = 'ab' * 32

        def signer(arguments, env):
            if arguments[1] == 'sign':
                Path(arguments[arguments.index('--out') + 1]).write_bytes(b'fixture signed APK')
                return ''
            return f'Signer #1 certificate SHA-256 digest: {self.digest}\n'

        with patch.object(signing, 'run_signer', side_effect=signer), self.assertRaisesRegex(ValueError, 'fingerprint'):
            signing.sign(self.apk, self.output, 'apksigner', env=self.env, profile='release')
        self.assertFalse(self.output.exists())

    def test_release_cli_requires_credentials_by_default(self):
        result = subprocess.run([sys.executable, str(TOOLS / 'release_signing.py'), '--apk', str(self.apk),
                                 '--output-dir', str(self.output), '--apksigner', 'not-called'],
                                env={name: value for name, value in os.environ.items()
                                     if not name.startswith('IMUNAV_RELEASE_')}, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No durable release', result.stderr)
        self.assertFalse(self.output.exists())

    def test_generator_release_profile_has_separate_settings_and_filename(self):
        def keytool(arguments, env):
            if arguments[0] == 'whoami':
                return '"fixture user","S-1-5-21-1-2-3-1000"\n'
            if '-genkeypair' in arguments:
                Path(arguments[arguments.index('-keystore') + 1]).write_bytes(b'fixture release key')
                self.assertIn('CN=IMU Nav Release', arguments)
                self.assertIn('IMUNAV_RELEASE_STORE_PASSWORD', arguments)
            if '-exportcert' in arguments:
                Path(arguments[arguments.index('-file') + 1]).write_bytes(b'fixture certificate')
            return ''

        with patch.object(generator, 'run_private', side_effect=keytool):
            generator.generate(self.output, keytool='keytool', env={}, profile='release')
        settings = json.loads((self.output / 'github-settings.json').read_text())
        self.assertEqual(set(settings['secrets']), {name.removeprefix('IMUNAV_') for name in self.variables})
        self.assertEqual(set(settings['variables']), {'RELEASE_CERT_SHA256'})
        self.assertEqual(settings['secrets']['RELEASE_KEY_ALIAS'], 'imunav-release')
        self.assertEqual((self.output / 'release.jks').read_bytes(), b'fixture release key')
        self.assertFalse((self.output / 'benchmark.jks').exists())

    def test_unknown_profiles_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown signing profile'):
            signing.sign(self.apk, self.output, 'apksigner', profile='unknown')
        with self.assertRaisesRegex(ValueError, 'Unknown signing profile'):
            generator.generate(self.output, profile='unknown')


class ReleaseWorkflowTest(unittest.TestCase):
    def test_release_secret_boundary_and_artifact_paths(self):
        workflow = (TOOLS.parent / '.github/workflows/fdroid.yml').read_text()
        steps = re.split(r'(?m)^      - name: ', workflow)
        signing_step = next(step for step in steps if step.startswith('Sign direct-download release'))
        condition = next(line.strip() for line in signing_step.splitlines() if line.strip().startswith('if:'))
        self.assertEqual(condition, "if: steps.changes.outputs.run == 'true' && (github.event_name == 'workflow_dispatch' || "
                                   "(github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')))")
        self.assertNotIn('--allow-unsigned', signing_step)
        self.assertNotIn('BENCHMARK_', signing_step)
        for variable in signing.credential_variables('release'):
            self.assertEqual(workflow.count('${{ secrets.' + variable.removeprefix('IMUNAV_') + ' }}'), 1)
            self.assertIn('${{ secrets.' + variable.removeprefix('IMUNAV_') + ' }}', signing_step)
        validation = next(step for step in steps if step.startswith('Stage unsigned release'))
        self.assertIn("github.event_name == 'pull_request' || github.event_name == 'merge_group'", validation)
        self.assertIn('--allow-unsigned', validation)
        self.assertNotIn('secrets.', validation)
        artifact = next(step for step in steps if step.startswith('Upload explicitly labeled direct-download release'))
        self.assertIn('steps.release_signing.outputs.mode || steps.release_validation.outputs.mode', artifact)
        self.assertIn('build/release-distribution/', artifact)
        self.assertNotIn('outputs/apk/fdroid/release/', artifact)
        alignment = next(step for step in steps if step.startswith('Verify 16 KB'))
        self.assertIn('build/release-distribution/*.apk', alignment)


if __name__ == '__main__':
    unittest.main()
