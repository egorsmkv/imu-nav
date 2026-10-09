"""Policy cases and real git histories for selective CI; no GitHub-glob emulation."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('ci_changes', Path(__file__).parents[1] / 'ci_changes.py')
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)


class SelectionTests(unittest.TestCase):
    def selected(self, *paths):
        return {suite for suite in ci.SUITES if ci.affected(suite, paths)}

    def test_docs_skip_expensive_work(self):
        self.assertEqual(self.selected('README.md', 'docs/build_and_test.md', 'native/README.md'), set())

    def test_component_changes_do_not_cross_rust_workspaces(self):
        self.assertEqual(self.selected('native/nav-core/src/lib.rs'), {'native', 'kotlin', 'fdroid'})
        self.assertEqual(self.selected('server/src/api.rs'), {'server', 'kotlin'})
        self.assertEqual(self.selected('server/tools/traffic_sim.py'), {'server'})
        self.assertEqual(self.selected('app/src/main/kotlin/org/imunav/app/ui/NavMap.kt'), {'kotlin', 'fdroid'})
        self.assertEqual(self.selected('app/src/test/kotlin/SomeTest.kt'), {'kotlin'})

    def test_shared_build_inputs_and_included_assets_are_selected(self):
        for script in ci.ANDROID_RUST_BUILD:
            self.assertEqual(self.selected(script), {'native', 'kotlin', 'fdroid'})
        self.assertEqual(self.selected('gradle/libs.versions.toml'), ci.GRADLE_SUITES)
        self.assertEqual(self.selected('links.properties'), ci.GRADLE_SUITES)
        self.assertEqual(self.selected('core/src/test/resources/fixture.json'), ci.GRADLE_SUITES)
        self.assertEqual(self.selected('server/static/maplibre/license.txt'), {'server', 'kotlin'})
        self.assertEqual(self.selected('server/templates/trips.html'), {'server', 'kotlin'})
        self.assertEqual(self.selected('server/Cargo.lock'), {'server', 'kotlin'})
        self.assertEqual(self.selected('native/Cargo.lock'), {'native', 'kotlin', 'fdroid'})
        self.assertEqual(self.selected('.cargo/config.toml'), {'native', 'server', 'kotlin', 'fdroid'})

    def test_workflow_and_selector_changes(self):
        self.assertEqual(self.selected('.github/workflows/rust-coverage.yml'), {'native', 'server'})
        self.assertEqual(self.selected('.github/workflows/map-pack.yml'), {'map'})
        self.assertEqual(self.selected('tools/ci_changes.py'), set(ci.SUITES))
        self.assertEqual(self.selected('tools/tests/test_ci_changes.py'), set(ci.SUITES))

    def test_unrelated_tools_do_not_build_android_or_rust(self):
        self.assertEqual(self.selected('tools/make_map_pack.py'), {'map', 'tools'})
        self.assertEqual(self.selected('tools/verify_routing_pack.py'), {'routing', 'tools'})
        self.assertEqual(self.selected('tools/kotlin_coverage.py'), {'kotlin', 'tools'})
        self.assertEqual(self.selected('tools/rust_coverage_rustc.py'), {'native', 'server', 'tools'})
        self.assertEqual(self.selected('tools/verify_native.py'), {'tools'})

    def test_unknown_history_manual_tags_and_merge_queues_run_all(self):
        for name, event in [('workflow_dispatch', {}), ('merge_group', {}),
                            ('push', {'ref': 'refs/tags/v1.0'}), ('push', {}),
                            ('push', {'before': '0' * 40, 'after': 'a' * 40})]:
            with self.subTest(name=name, event=event):
                paths = ci.changed_paths(name, event)
                self.assertIsNone(paths)
                self.assertTrue(all(ci.affected(suite, paths) for suite in ci.SUITES))

    def test_git_failure_runs_all_instead_of_skipping(self):
        with patch.object(ci, 'git', side_effect=subprocess.CalledProcessError(128, 'git')):
            self.assertIsNone(ci.changed_paths('push', {'before': 'a' * 40, 'after': 'b' * 40}))


class GitHistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'ci-test@example.invalid')
        self.git('config', 'user.name', 'CI test')
        self.write('README.md', 'base')
        self.base = self.commit()
        # Use real git operations in the temporary repository without changing process cwd.
        mocked = patch.object(ci, 'git', side_effect=self.git)
        mocked.start()
        self.addCleanup(mocked.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args])

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD').decode().strip()

    def paths(self, before, after, event='push'):
        payload = {'before': before, 'after': after}
        if event == 'pull_request':
            payload = {'pull_request': {'base': {'sha': before}, 'head': {'sha': after}}}
        return ci.changed_paths(event, payload)

    def test_entire_multicommit_pr_and_push(self):
        self.write('server/src/api.rs', 'changed')
        self.commit()
        self.write('README.md', 'docs-only final commit')
        head = self.commit()
        for event in ['push', 'pull_request']:
            self.assertIn('server/src/api.rs', self.paths(self.base, head, event))

    def test_pr_uses_merge_base_not_unrelated_target_changes(self):
        self.write('core/src/new.kt', 'target only')
        target = self.commit()
        self.git('checkout', '-q', '--detach', self.base)
        self.write('README.md', 'pr only')
        head = self.commit()
        self.assertEqual(self.paths(target, head, 'pull_request'), ['README.md'])
        # A force push must include removal of content from the previous head.
        self.assertIn('core/src/new.kt', self.paths(target, head, 'push'))

    def test_rename_and_deletion_include_old_paths(self):
        self.write('server/src/old.rs', 'moved')
        self.write('native/nav-core/src/deleted.rs', 'deleted')
        before = self.commit()
        self.git('mv', 'server/src/old.rs', 'moved.txt')
        self.git('rm', '-q', 'native/nav-core/src/deleted.rs')
        paths = self.paths(before, self.commit())
        self.assertEqual(set(paths), {'server/src/old.rs', 'moved.txt', 'native/nav-core/src/deleted.rs'})

    def test_large_diff_has_no_300_file_cutoff_and_preserves_newlines(self):
        for index in range(310):
            self.write(f'docs/{index}.md', 'docs')
        self.write('server/src/z.rs', 'selected beyond 300 paths')
        self.write('docs/new\nline.md', 'unusual path')
        paths = self.paths(self.base, self.commit())
        self.assertEqual(len(paths), 312)
        self.assertIn('docs/new\nline.md', paths)
        self.assertTrue(ci.affected('server', paths))


if __name__ == '__main__':
    unittest.main()
