"""Synthetic ELF/APK fixtures ensure page gates cannot pass missing or incompatible libraries."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('android_pages', Path(__file__).parents[1] / 'verify_android_pages.py')
pages = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pages)


def elf(alignment=16384, relro_end=16384):
    data = bytearray(16384)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 2)
    struct.pack_into('<IIQQQQQQ', data, 64, pages.PT_LOAD, 5, 0, 0, 0, 16384, 16384, alignment)
    struct.pack_into('<IIQQQQQQ', data, 120, pages.PT_GNU_RELRO, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(data)


class AndroidPagesTest(unittest.TestCase):
    def test_load_and_relro_boundaries_are_checked(self):
        self.assertEqual(pages.elf_errors(elf()), [])
        self.assertTrue(pages.elf_errors(elf(alignment=4096)))
        self.assertTrue(pages.elf_errors(elf(alignment=24000)))
        self.assertTrue(pages.elf_errors(elf(relro_end=4096)))
        self.assertTrue(pages.elf_errors(b'not ELF'))
        self.assertTrue(pages.elf_errors(elf()[:128]))

    def test_checks_prebuilt_libraries_and_zipalign_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libimu_nav_jni.so', elf())
                archive.writestr('lib/arm64-v8a/libmaplibre.so', elf(alignment=4096))
                archive.writestr('lib/armeabi-v7a/libimu_nav_jni.so', b'32-bit ABI is outside the 16 KB gate')
            with patch.object(pages.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, 'ZIP misaligned')) as run:
                report = pages.verify_apk(apk, Path('zipalign'))
            self.assertEqual(len(report['libraries']), 2)
            self.assertEqual(len(report['errors']), 2)
            self.assertIn('libmaplibre.so', report['errors'][0])
            self.assertEqual(run.call_args.args[0][1:6], ['-v', '-c', '-P', '16', '4'])

    def test_empty_apk_never_passes_even_when_zipalign_succeeds(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'empty.apk'
            with zipfile.ZipFile(apk, 'w'):
                pass
            with patch.object(pages.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'PASS')):
                self.assertTrue(pages.verify_apk(apk, Path('zipalign'))['errors'])

    def test_dependency_only_apk_cannot_hide_a_missing_jni_build(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'missing-jni.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/x86_64/libmaplibre.so', elf())
            with patch.object(pages.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'PASS')):
                report = pages.verify_apk(apk, Path('zipalign'))
            self.assertIn('Missing IMU Nav 64-bit JNI library', report['errors'])


if __name__ == '__main__':
    unittest.main()
