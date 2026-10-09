"""Exercise Windows native-build orchestration without downloading an NDK or building Rust."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
TARGETS = (
    ('aarch64-linux-android', 'arm64-v8a', 'aarch64-linux-android'),
    ('armv7-linux-androideabi', 'armeabi-v7a', 'armv7a-linux-androideabi'),
    ('x86_64-linux-android', 'x86_64', 'x86_64-linux-android'),
)


@unittest.skipUnless(os.name == 'nt', 'requires native Windows PowerShell')
class WindowsNativeBuildTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='imu nav windows build ')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / 'scripts'
        scripts.mkdir()
        self.script = scripts / 'build-rust-android.ps1'
        shutil.copyfile(REPO_ROOT / 'scripts/build-rust-android.ps1', self.script)
        tools = self.root / 'fake tools'
        tools.mkdir()
        fake_cargo = tools / 'cargo_fixture.py'
        fake_cargo.write_text('''import json, os, pathlib, sys
target = sys.argv[sys.argv.index('--target') + 1]
key = 'CARGO_TARGET_' + target.upper().replace('-', '_') + '_LINKER'
with open(os.environ['BUILD_TEST_LOG'], 'a', encoding='utf-8') as log:
    log.write(json.dumps({'args': sys.argv[1:], 'linker': os.environ[key]}) + '\\n')
if os.environ.get('BUILD_TEST_FAIL') == target:
    sys.exit(17)
library = pathlib.Path(os.environ['CARGO_TARGET_DIR']) / target / 'release/libimu_nav_jni.so'
library.parent.mkdir(parents=True, exist_ok=True)
library.write_text(target, encoding='utf-8')
''', encoding='utf-8')
        (tools / 'cargo.cmd').write_text(
            f'@"{sys.executable}" "{fake_cargo}" %*\r\n', encoding='utf-8')
        self.log = self.root / 'cargo.jsonl'
        self.sdk = self.root / 'Android SDK'
        self.output = self.root / 'JNI output'
        self.env = os.environ.copy()
        for name in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
            self.env.pop(name, None)
        self.env.update(ANDROID_SDK_ROOT=str(self.sdk), LOCALAPPDATA=str(self.root),
                        BUILD_TEST_LOG=str(self.log), PATH=str(tools) + os.pathsep + self.env['PATH'])

    def ndk(self, version):
        directory = self.sdk / 'ndk' / version
        binaries = directory / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
        binaries.mkdir(parents=True)
        for _, _, prefix in TARGETS:
            (binaries / f'{prefix}26-clang.cmd').touch()
        return directory

    def run_build(self):
        return subprocess.run(
            ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
             '-File', str(self.script), '-OutputRoot', str(self.output)],
            env=self.env, capture_output=True, text=True, timeout=30)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text(encoding='utf-8').splitlines()]

    def test_all_abis_use_windows_api26_linkers_and_copy_libraries_with_spaces_in_paths(self):
        ndk = self.ndk('28.2.13676358')
        result = self.run_build()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        self.assertEqual(len(calls), 3)
        for call, (target, abi, prefix) in zip(calls, TARGETS):
            self.assertEqual(call['args'], ['build', '--manifest-path', str(self.root / 'native/Cargo.toml'),
                                           '--package', 'imu-nav-jni', '--release', '--target', target])
            self.assertEqual(Path(call['linker']), ndk / f'toolchains/llvm/prebuilt/windows-x86_64/bin/{prefix}26-clang.cmd')
            self.assertEqual((self.output / abi / 'libimu_nav_jni.so').read_text(), target)

    def test_sdk_discovery_sorts_ndk_versions_numerically(self):
        self.ndk('9.0.1')
        newest = self.ndk('28.2.13676358')
        result = self.run_build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(Path(call['linker']).is_relative_to(newest) for call in self.calls()))

    def test_explicit_ndk_precedes_sdk_discovery(self):
        explicit = self.ndk('27.0.1')
        self.ndk('28.2.13676358')
        self.env['ANDROID_NDK_HOME'] = str(explicit)
        result = self.run_build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(Path(call['linker']).is_relative_to(explicit) for call in self.calls()))

    def test_cargo_failure_stops_before_copying_or_building_another_abi(self):
        self.ndk('28.2.13676358')
        self.env['BUILD_TEST_FAIL'] = TARGETS[0][0]
        result = self.run_build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cargo build failed for aarch64-linux-android (exit 17)', result.stderr)
        self.assertEqual(len(self.calls()), 1)
        self.assertFalse(self.output.exists())

    def test_missing_ndk_reports_setup_instructions_without_launching_cargo(self):
        result = self.run_build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Android NDK not found', result.stderr)
        self.assertFalse(self.log.exists())

    def test_missing_linker_stops_before_launching_cargo(self):
        ndk = self.ndk('28.2.13676358')
        (ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd').unlink()
        result = self.run_build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Android linker not found', result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()
