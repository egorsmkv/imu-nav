"""Exercise release workflow reruns against disposable real Git repositories."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

from test_update_app_version import FIXTURES, update


class AppVersionWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.repository = self.root / 'repository'
        self.repository.mkdir()
        self.remote = self.root / 'remote.git'
        self.git('init', '--bare', str(self.remote))
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Workflow Test')
        self.git('config', 'user.email', 'test@example.invalid')
        for name, content in FIXTURES.items():
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.git('add', '.')
        self.git('commit', '-m', 'Initial version')
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', 'origin', 'main')
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        gh = self.bin / 'gh'
        gh.write_text(textwrap.dedent('''\
            #!/bin/bash
            if [[ "$2" == list ]]; then
              if [[ "$PR_CASE" == existing ]]; then echo https://github.com/example/repo/pull/1; fi
              exit 0
            fi
            echo create >> "$GH_CALLS"
            case "$PR_CASE" in
              blocked) echo 'GitHub Actions is not permitted to create or approve pull requests' >&2; exit 1 ;;
              other) echo 'Resource not accessible by integration' >&2; exit 1 ;;
              *) echo https://github.com/example/repo/pull/1 ;;
            esac
            '''))
        gh.chmod(0o755)
        workflow = Path(__file__).resolve().parents[2] / '.github/workflows/app-version.yml'
        self.script = textwrap.dedent(workflow.read_text().rsplit('        run: |\n', 1)[1])

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.repository, check=True,
                              capture_output=True, text=True).stdout.strip()

    def run_workflow(self, case='success'):
        summary = self.root / 'summary'
        summary.write_text('')
        env = dict(os.environ, PATH=f'{self.bin}:' + os.environ['PATH'],
                   VERSION_NAME='0.7.0', REPOSITORY='example/repo', SERVER_URL='https://github.com',
                   RUNNER_TEMP=str(self.root), GITHUB_STEP_SUMMARY=str(summary),
                   PR_CASE=case, GH_CALLS=str(self.root / 'gh-calls'))
        return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', self.script],
                              cwd=self.repository, env=env, capture_output=True, text=True)

    def prepare_branch(self):
        update(self.repository, '0.7.0')
        result = self.run_workflow()
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.git('rev-parse', 'release/version-0.7.0')

    def test_new_branch_and_rerun_after_main_advances(self):
        original = self.prepare_branch()
        self.git('switch', 'main')
        (self.repository / 'README.md').write_text('Unrelated main change\n')
        self.git('add', 'README.md')
        self.git('commit', '-m', 'Advance main')
        update(self.repository, '0.7.0')
        result = self.run_workflow()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Reusing verified version branch', result.stdout)
        self.assertEqual(self.git('rev-parse', 'origin/release/version-0.7.0'), original)

    def test_existing_pr_is_reused(self):
        self.prepare_branch()
        calls = (self.root / 'gh-calls').read_text()
        self.git('switch', 'main')
        update(self.repository, '0.7.0')
        result = self.run_workflow('existing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'gh-calls').read_text(), calls)
        self.assertIn('Existing version update PR:', (self.root / 'summary').read_text())

    def test_different_version_code_fails_without_overwriting(self):
        original = self.prepare_branch()
        self.git('switch', 'main')
        update(self.repository, '0.7.0', 20)
        result = self.run_workflow()
        self.assertEqual(result.returncode, 1)
        self.assertIn('differs from the requested update', result.stdout)
        self.assertEqual(self.git('rev-parse', 'origin/release/version-0.7.0'), original)

    def test_unrelated_branch_changes_fail(self):
        self.prepare_branch()
        (self.repository / 'extra.txt').write_text('Unrelated branch change\n')
        self.git('add', 'extra.txt')
        self.git('commit', '-m', 'Unrelated change')
        self.git('push', 'origin', 'release/version-0.7.0')
        original = self.git('rev-parse', 'HEAD')
        self.git('switch', 'main')
        update(self.repository, '0.7.0')
        result = self.run_workflow()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.git('rev-parse', 'origin/release/version-0.7.0'), original)

    def test_blocked_pr_is_a_warning(self):
        update(self.repository, '0.7.0')
        result = self.run_workflow('blocked')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('::warning::', result.stdout)
        self.assertIn('compare/main...release/version-0.7.0?expand=1', (self.root / 'summary').read_text())

    def test_unrelated_parent_commit_fails(self):
        self.git('switch', '-c', 'unrelated')
        (self.repository / 'extra.txt').write_text('Unrelated parent change\n')
        self.git('add', 'extra.txt')
        self.git('commit', '-m', 'Unrelated parent')
        original = self.prepare_branch()
        self.git('switch', 'main')
        update(self.repository, '0.7.0')
        result = self.run_workflow()
        self.assertEqual(result.returncode, 1)
        self.assertIn('differs from the requested update', result.stdout)
        self.assertEqual(self.git('rev-parse', 'origin/release/version-0.7.0'), original)

    def test_other_pr_errors_fail(self):
        update(self.repository, '0.7.0')
        result = self.run_workflow('other')
        self.assertEqual(result.returncode, 1)
        self.assertIn('Resource not accessible', result.stderr)


if __name__ == '__main__':
    unittest.main()
