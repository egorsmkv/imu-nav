"""Regression tests for release identity, version reuse and unsafe/native bundle payloads."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location('play_bundle', TOOLS / 'play_bundle.py')
play = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(play)
from test_android_pages import elf

GRADLE = 'versionCode = 8\nversionName = "0.8.0"\n'
METADATA = 'CurrentVersionCode: 8\nCurrentVersion: 0.8.0\n'
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="org.imunav.app" android:versionCode="8" android:versionName="0.8.0">
 <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <application android:debuggable="false"/></manifest>'''


class PlayBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / 'input.aab'

    def archive(self, entries):
        with zipfile.ZipFile(self.bundle, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def test_version_ceiling_and_sdk_identity(self):
        self.assertEqual(play.manifest_info(MANIFEST, GRADLE, METADATA, 7)['version_code'], 8)
        for ceiling in (8, 9, -1):
            with self.assertRaises(ValueError):
                play.manifest_info(MANIFEST, GRADLE, METADATA, ceiling)
        for xml in (MANIFEST.replace('org.imunav.app', 'org.imunav.app.bench'),
                    MANIFEST.replace('debuggable="false"', 'debuggable="true"'),
                    MANIFEST.replace('targetSdkVersion="36"', 'targetSdkVersion="35"')):
            with self.assertRaises(ValueError):
                play.manifest_info(xml, GRADLE, METADATA)
        with self.assertRaises(ValueError):
            play.manifest_info(MANIFEST, GRADLE.replace('8', '9'), METADATA)
        with self.assertRaises(ValueError):
            play.manifest_info(MANIFEST, GRADLE, METADATA.replace('0.8.0', '0.9.0'))

    def test_no_private_key_fallback_or_overwrite(self):
        self.archive({'base/manifest/AndroidManifest.xml': b'fixture'})
        with self.assertRaises(ValueError):
            play.sign(self.bundle, self.root / 'signed.aab', env={})
        env = {name: 'fixture' for name in play.credential_variables('release')}
        with self.assertRaises(ValueError):
            play.sign(self.bundle, self.root / 'signed.aab', env=env)
        env['IMUNAV_PLAY_UPLOAD_CERT_SHA256'] = 'ab' * 32
        output = self.root / 'signed.aab'
        output.write_bytes(b'keep')
        with self.assertRaises(ValueError):
            play.sign(self.bundle, output, env=env)
        self.assertEqual(output.read_bytes(), b'keep')

    def test_native_dependency_and_missing_abi_fail_closed(self):
        entries = {'base/lib/arm64-v8a/libimu_nav_jni.so': elf(),
                   'base/lib/armeabi-v7a/libimu_nav_jni.so': b'32bit'}
        self.archive(entries)
        self.assertEqual(len(play.bundle_libraries(self.bundle)), 1)
        self.archive(dict(entries, **{'base/lib/arm64-v8a/libmaplibre.so': elf(alignment=4096)}))
        with self.assertRaisesRegex(ValueError, 'libmaplibre'):
            play.bundle_libraries(self.bundle)
        self.archive({'base/lib/arm64-v8a/libimu_nav_jni.so': elf()})
        with self.assertRaisesRegex(ValueError, 'armeabi-v7a'):
            play.bundle_libraries(self.bundle)

    def test_signed_or_unsafe_input_is_rejected(self):
        for name in ('META-INF/UPLOAD.RSA', 'META-INF/upload.sf', '../escape', '/absolute', 'bad\\path'):
            self.archive({name: b'data'})
            with self.assertRaises(ValueError):
                play.unsigned_archive(self.bundle)

    def test_java_crypto_verifier_rejects_unsigned_payload(self):
        self.archive({'base/manifest/AndroidManifest.xml': b'fixture'})
        with self.assertRaises(ValueError):
            play.verify_signature(self.bundle, 'ab' * 32)

    def test_key_certificate_mismatch_never_calls_jarsigner(self):
        self.archive({'base/manifest/AndroidManifest.xml': b'fixture'})
        env = {name: 'fixture' for name in play.credential_variables('release')}
        env['IMUNAV_RELEASE_KEYSTORE_BASE64'] = 'Zml4dHVyZQ=='
        env['IMUNAV_PLAY_UPLOAD_CERT_SHA256'] = 'ab' * 32
        def tool(arguments, environment=None):
            Path(arguments[-1]).write_bytes(b'wrong certificate')
            return ''
        with patch.object(play, 'run', side_effect=tool) as runner:
            with self.assertRaisesRegex(ValueError, 'differs'):
                play.sign(self.bundle, self.root / 'signed.aab', env)
        self.assertEqual(runner.call_count, 1)
        self.assertFalse((self.root / 'signed.aab').exists())
        self.assertEqual(list(self.root.iterdir()), [self.bundle])


if __name__ == '__main__':
    unittest.main()
