#!/usr/bin/env python3
"""Select expensive CI steps using complete git diffs, while every check still reports.

Rules are exact paths or directory prefixes ending in '/'; these are deliberately
not GitHub glob patterns. Missing history selects all work, never an empty diff.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


SHARED = ('tools/ci_changes.py', 'tools/tests/test_ci_changes.py')
GRADLE = ('gradle/', 'gradlew', 'gradlew.bat', 'gradle.properties', '.editorconfig',
          'links.properties', 'config/detekt.yml')
RUST = ('.cargo/', 'rust-toolchain', 'rust-toolchain.toml', 'rustfmt.toml', '.rustfmt.toml')
NATIVE = ('native/nav-core/', 'native/nav-jni/', 'native/nav-sim/', 'native/Cargo.toml',
          'native/Cargo.lock', 'native/.cargo/', 'native/rust-toolchain.toml', 'native/rust-toolchain')
SERVER = ('server/src/', 'server/templates/', 'server/static/', 'server/Cargo.toml',
          'server/Cargo.lock', 'server/build.rs', 'server/.cargo/', 'server/rust-toolchain.toml',
          'server/rust-toolchain')
CORE = ('core/src/',)
ROUTING = ('routing/src/',)
REPLAY = ('replay/src/',)
COVERAGE = ('tools/rust_coverage.py', 'tools/rust_coverage_rustc.py', 'tools/tests/test_rust_coverage.py')
ANDROID_RUST_BUILD = ('scripts/build-rust-android.sh', 'scripts/build-rust-android.ps1')
SUITES = {
    'tools': ('tools/', '.github/workflows/kotlin-coverage.yml'),
    'fdroid': ('.github/workflows/fdroid.yml', 'app/src/main/', 'app/src/fdroid/',
               'app/lint.xml', 'app/proguard-rules.pro') + ANDROID_RUST_BUILD + CORE + ROUTING + NATIVE + RUST,
    'kotlin': ('.github/workflows/kotlin-coverage.yml', 'app/src/main/', 'app/src/fdroid/',
               'app/src/test/', 'tools/kotlin_coverage.py',
               'tools/tests/test_kotlin_coverage.py', 'config/kotlin-coverage-baseline.json') + ANDROID_RUST_BUILD + CORE + ROUTING + REPLAY + NATIVE + SERVER + RUST,
    'native': ('.github/workflows/rust-coverage.yml', 'app/src/main/kotlin/org/imunav/app/nativecore/') + ANDROID_RUST_BUILD + CORE + REPLAY + NATIVE + COVERAGE + RUST,
    'server': ('.github/workflows/rust-coverage.yml', 'server/tests/', 'server/tools/') + SERVER + COVERAGE + RUST,
    'map': ('.github/workflows/map-pack.yml', 'tools/make_map_pack.py',
            'tools/verify_map_pack.py', 'tools/tests/test_map_pack.py'),
    'routing': ('.github/workflows/routing-pack.yml', 'tools/verify_routing_pack.py') + CORE + ROUTING,
}
GRADLE_SUITES = {'fdroid', 'kotlin', 'native', 'routing'}


def matches(path, rules):
    return any(path.startswith(rule) if rule.endswith('/') else path == rule for rule in rules)


def affected(suite, paths):
    """None means detection was unavailable or the event explicitly requests full validation."""
    if paths is None:
        return True
    rules = SHARED + SUITES[suite]
    for path in paths:
        if matches(path, rules):
            return True
        if suite in GRADLE_SUITES and (matches(path, GRADLE) or path.endswith('.gradle.kts')):
            return True
    return False


def git(*args):
    return subprocess.check_output(['git', *args])


def changed_paths(event_name, event):
    """PRs use the whole branch diff; pushes use before..after, including force pushes."""
    if event_name not in ('pull_request', 'push') or event.get('ref', '').startswith('refs/tags/'):
        return None
    try:
        if event_name == 'pull_request':
            before = event['pull_request']['base']['sha']
            after = event['pull_request']['head']['sha']
        else:
            before, after = event['before'], event['after']
        if any(not re.fullmatch(r'[0-9a-fA-F]{40}', sha) or set(sha) == {'0'} for sha in (before, after)):
            return None
        if event_name == 'pull_request':
            before = git('merge-base', before, after).decode().strip()
        # --no-renames includes both old and new paths. NUL separation supports any git filename.
        output = git('diff', '--name-only', '--no-renames', '-z', before, after, '--')
        return [os.fsdecode(path) for path in output.split(b'\0') if path]
    except (KeyError, TypeError, subprocess.CalledProcessError, OSError):
        print('Change history unavailable; selecting all work.')
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=SUITES, required=True)
    args = parser.parse_args()
    # Invalid/missing event JSON fails the step instead of reporting an unrelated-change success.
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    paths = changed_paths(os.environ['GITHUB_EVENT_NAME'], event)
    selected = affected(args.suite, paths)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'run={str(selected).lower()}\n')
    reason = 'full validation' if paths is None else f'{len(paths)} changed paths'
    print(f'{args.suite}: {"run" if selected else "skip expensive steps"} ({reason})')


if __name__ == '__main__':
    main()
