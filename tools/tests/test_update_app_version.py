"""Exercise release updates against isolated representative Gradle and F-Droid fixtures."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update_app_version import MAX_VERSION_CODE, update

FIXTURES = {
    'app/build.gradle.kts': 'android {\n    defaultConfig {\n        versionCode = 7\n        versionName = "0.6.0"\n    }\n}\n',
    'fdroid/metadata.yml': ('Builds:\n  - versionName: 0.6.0\n    versionCode: 7\n'
                            '    commit: v0.6.0\n    subdir: app\n    gradle:\n      - fdroid\n\n'
                            'AutoUpdateMode: Version\nCurrentVersion: 0.6.0\nCurrentVersionCode: 7\n'),
}


class VersionUpdateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.paths = ['app/build.gradle.kts', 'fdroid/metadata.yml']
        for path in self.paths:
            destination = self.root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(FIXTURES[path], encoding='utf-8')

    def read(self, path):
        return (self.root / path).read_text(encoding='utf-8')

    def test_increment_and_preserve_history(self):
        previous = self.read(self.paths[1])
        self.assertEqual(update(self.root, '0.7.0'), 8)
        self.assertIn('versionCode = 8', self.read(self.paths[0]))
        metadata = self.read(self.paths[1])
        self.assertIn(previous.split('Builds:\n')[1].split('AutoUpdateMode:')[0], metadata)
        self.assertIn('commit: v0.7.0', metadata)
        self.assertIn('CurrentVersion: 0.7.0\nCurrentVersionCode: 8', metadata)
        self.assertEqual(update(self.root, '0.7.1', 20), 20)
        self.assertIn('commit: v0.7.0', self.read(self.paths[1]))
        self.assertIn('commit: v0.7.1', self.read(self.paths[1]))

    def test_reject_invalid_inputs_without_writes(self):
        before = [self.read(path) for path in self.paths]
        cases = [('0.6.0', None), ('0.5.9', None), ('v0.7.0', None), ('0.07.0', None),
                 ('0.7.0\nmalicious', None), ('$(id)', None), ('0.7.0-beta', None),
                 ('0.7.0', 7), ('0.7.0', 0), ('0.7.0', MAX_VERSION_CODE + 1)]
        for name, code in cases:
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                update(self.root, name, code)
            self.assertEqual([self.read(path) for path in self.paths], before)

    def test_metadata_mismatch_and_ambiguous_gradle(self):
        for path, addition in [(self.paths[0], '\nversionName = "0.6.0"\n'),
                               (self.paths[1], '\nCurrentVersionCode: 99\n')]:
            original = self.read(path)
            (self.root / path).write_text(original + addition, encoding='utf-8')
            before = [self.read(item) for item in self.paths]
            with self.assertRaises(ValueError):
                update(self.root, '0.7.0')
            self.assertEqual([self.read(item) for item in self.paths], before)
            (self.root / path).write_text(original, encoding='utf-8')


if __name__ == '__main__':
    unittest.main()
