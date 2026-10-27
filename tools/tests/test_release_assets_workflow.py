"""Exercise public release staging and the write-token boundary without contacting GitHub."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).parents[2]
WORKFLOW = ROOT / '.github/workflows/fdroid.yml'
GIT_BASH = Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe'
BASH = str(GIT_BASH) if os.name == 'nt' and GIT_BASH.is_file() else shutil.which('bash')


def step(text, name):
    return next(part for part in re.split(r'(?m)^      - name: ', text) if part.startswith(name))


def script(text, name):
    lines = step(text, name).splitlines()
    index = next(index for index, line in enumerate(lines) if line.startswith('        run: '))
    command = lines[index].removeprefix('        run: ')
    if command != '|':
        return command
    result = []
    for line in lines[index + 1:]:
        if not line.startswith('          '):
            break
        result.append(line[10:])
    return '\n'.join(result)


class ReleaseAssetsPolicyTests(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_published_releases_build_the_exact_tag_with_protected_signing(self):
        self.assertIn('  release:\n    types: [published]', self.workflow)
        self.assertEqual(self.workflow.count("github.event_name != 'release' || startsWith(github.event.release.tag_name, 'v')"), 2)
        build = self.workflow.split('  fdroid:', 1)[1].split('  updates:', 1)[0]
        environment = next(line for line in build.splitlines() if line.strip().startswith('environment:'))
        self.assertIn("github.event_name == 'release'", environment)
        self.assertIn("'android-distribution'", environment)
        self.assertNotIn('ref: main', build)
        for name in ('Sign direct-download release', 'Sign benchmark with'):
            condition = next(line for line in step(self.workflow, name).splitlines() if line.strip().startswith('if:'))
            self.assertIn("github.event_name == 'release'", condition)

    def test_publisher_waits_for_checks_and_never_receives_signing_keys_or_source(self):
        publisher = self.workflow.split('  publish:', 1)[1]
        self.assertIn('needs: [fdroid, updates]', publisher)
        self.assertIn("if: github.event_name == 'release' && startsWith(github.event.release.tag_name, 'v') && needs.fdroid.outputs.release_mode == 'signed'", publisher)
        self.assertIn('permissions:\n      contents: write', publisher)
        self.assertEqual(self.workflow.count('contents: write'), 1)
        self.assertIn('GH_TOKEN: ${{ github.token }}', publisher)
        self.assertNotIn('secrets.', publisher)
        self.assertNotIn('actions/checkout', publisher)
        self.assertNotIn('--clobber', script(self.workflow, 'Attach APKs and checksums'))
        self.assertIn('run: sha256sum --check fdroid-SHA256SUMS', publisher)
        self.assertLess(publisher.index('Verify downloaded APK checksums'), publisher.index('Attach APKs and checksums'))

    def test_public_artifact_contains_only_staged_installable_files(self):
        staging = step(self.workflow, 'Prepare installable GitHub Release assets')
        self.assertIn("if: github.event_name == 'release' && steps.release_signing.outputs.mode == 'signed'", staging)
        self.assertLess(self.workflow.index('Verify 16 KB'), self.workflow.index('Prepare installable GitHub Release assets'))
        artifact = step(self.workflow, 'Upload verified GitHub Release assets for publication')
        self.assertIn('path: build/github-release/', artifact)
        self.assertIn('if-no-files-found: error', artifact)
        publisher = self.workflow.split('  publish:', 1)[1]
        self.assertIn('name: imu-nav-fdroid-release-assets-${{ github.sha }}', artifact)
        self.assertIn('name: imu-nav-fdroid-release-assets-${{ github.sha }}', publisher)


@unittest.skipUnless(BASH, 'Bash is required to execute the Ubuntu release workflow commands')
class ReleaseAssetsCommandsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.workflow = WORKFLOW.read_text()
        for directory in ('release-distribution', 'benchmark-distribution'):
            target = self.root / 'build' / directory
            target.mkdir(parents=True)
            (target / 'unpublished-report.json').write_text('{}')
        (self.root / 'build/release-distribution/app-fdroid-release.apk').write_bytes(b'synthetic signed release')
        (self.root / 'build/benchmark-distribution/app-fdroid-benchmark.apk').write_bytes(b'synthetic signed benchmark')
        (self.root / 'build/benchmark-distribution/app-fdroid-benchmark-unsigned.apk').write_bytes(b'synthetic unsigned benchmark')

    def run_script(self, name, **environment):
        return subprocess.run([BASH, '--noprofile', '--norc', '-eo', 'pipefail', '-c', script(self.workflow, name)],
                              cwd=self.root, env={**os.environ, **environment}, capture_output=True, text=True)

    def assert_assets(self, mode, expected):
        result = self.run_script('Prepare installable GitHub Release assets', BENCHMARK_MODE=mode)
        self.assertEqual(result.returncode, 0, result.stderr)
        assets = self.root / 'build/github-release'
        self.assertEqual({file.name for file in assets.iterdir()}, expected | {'fdroid-SHA256SUMS'})
        entries = (assets / 'fdroid-SHA256SUMS').read_text().splitlines()
        self.assertEqual(len(entries), len(expected))
        for entry in entries:
            digest, name = entry.split(maxsplit=1)
            name = name.removeprefix('*')
            self.assertIn(name, expected)
            self.assertEqual(digest, hashlib.sha256((assets / name).read_bytes()).hexdigest())

    def test_signed_release_is_published_without_an_unsigned_benchmark(self):
        self.assert_assets('unsigned', {'app-fdroid-release.apk'})

    def test_signed_benchmark_is_included_with_independent_checksum(self):
        self.assert_assets('signed', {'app-fdroid-release.apk', 'app-fdroid-benchmark.apk'})

    def test_missing_signed_apk_fails_instead_of_publishing_a_partial_set(self):
        (self.root / 'build/release-distribution/app-fdroid-release.apk').unlink()
        result = self.run_script('Prepare installable GitHub Release assets', BENCHMARK_MODE='signed')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'build/github-release/fdroid-SHA256SUMS').exists())

    def test_upload_passes_tag_as_data_and_never_replaces_existing_assets(self):
        self.assert_assets('signed', {'app-fdroid-release.apk', 'app-fdroid-benchmark.apk'})
        shutil.copytree(self.root / 'build/github-release', self.root / 'release-assets')
        command = "gh() { printf '%s\\0' \"$@\" > gh-arguments; }\n" + script(self.workflow, 'Attach APKs and checksums')
        tag = 'v1.2.3-$(touch injected)'
        result = subprocess.run([BASH, '--noprofile', '--norc', '-eo', 'pipefail', '-c', command], cwd=self.root,
                                env={**os.environ, 'RELEASE_TAG': tag, 'GH_REPO': 'fixture/project'}, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = (self.root / 'gh-arguments').read_bytes().decode().strip('\0').split('\0')
        self.assertEqual(arguments, ['release', 'upload', tag, 'release-assets/app-fdroid-benchmark.apk',
                                     'release-assets/app-fdroid-release.apk', 'release-assets/fdroid-SHA256SUMS', '--repo', 'fixture/project'])
        self.assertFalse((self.root / 'injected').exists())


if __name__ == '__main__':
    unittest.main()
