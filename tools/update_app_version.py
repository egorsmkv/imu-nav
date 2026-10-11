#!/usr/bin/env python3
"""Prepare an Android release version and matching F-Droid recipe without publishing it."""
import argparse
from pathlib import Path
import re

MAX_VERSION_CODE = 2_100_000_000
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'


def release_version(value):
    """Accept stable major.minor.patch versions, suitable for a release tag and branch."""
    if len(value) > 64 or not re.fullmatch(VERSION, value):
        raise ValueError('version name must be major.minor.patch without leading zeros')
    return tuple(map(int, value.split('.')))


def one(text, pattern):
    """Fail closed if the version declaration is missing or ambiguous."""
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    if len(matches) != 1:
        raise ValueError(f'expected exactly one declaration matching {pattern}')
    return matches[0]


def replace_one(text, pattern, value):
    match = one(text, pattern)
    return text[:match.start(1)] + value + text[match.end(1):]


def update(root, version_name, version_code=None):
    """Validate both files before writing; retain historical F-Droid build recipes."""
    new_version = release_version(version_name)
    gradle_path = root / 'app/build.gradle.kts'
    metadata_path = root / 'fdroid/metadata.yml'
    gradle = gradle_path.read_text(encoding='utf-8')
    metadata = metadata_path.read_text(encoding='utf-8')
    name_pattern = r'^\s*versionName = "([^"\n]+)"\s*$'
    code_pattern = r'^\s*versionCode = ([0-9]+)\s*$'
    old_name = one(gradle, name_pattern)[1]
    old_code = int(one(gradle, code_pattern)[1])
    if new_version <= release_version(old_name):
        raise ValueError('version name must increase')
    code = old_code + 1 if version_code is None else version_code
    if not old_code < code <= MAX_VERSION_CODE:
        raise ValueError(f'version code must increase and be at most {MAX_VERSION_CODE}')
    if one(metadata, r'^CurrentVersion: (\S+)$')[1] != old_name or int(
        one(metadata, r'^CurrentVersionCode: ([0-9]+)$')[1]
    ) != old_code:
        raise ValueError('Gradle and F-Droid current versions disagree')
    builds = list(re.finditer(r'^  - versionName: (\S+)\n(?:(?:    [^\n]*|)\n)*', metadata, re.MULTILINE))
    current = [build for build in builds if build[1] == old_name]
    if len(current) != 1:
        raise ValueError('expected one F-Droid recipe for the current version')
    recipe = current[0].group()
    if int(one(recipe, r'^    versionCode: ([0-9]+)$')[1]) != old_code:
        raise ValueError('F-Droid build code disagrees with Gradle')
    if one(recipe, r'^    commit: (\S+)$')[1] != f'v{old_name}':
        raise ValueError('current F-Droid recipe must use the release tag')
    # Clone the latest recipe so its subdir, flavors and future build options survive.
    recipe = replace_one(recipe, r'^  - versionName: (\S+)$', version_name)
    recipe = replace_one(recipe, r'^    versionCode: ([0-9]+)$', str(code))
    recipe = replace_one(recipe, r'^    commit: (\S+)$', f'v{version_name}')
    metadata = metadata[:builds[-1].end()] + recipe + metadata[builds[-1].end():]
    metadata = replace_one(metadata, r'^CurrentVersion: (\S+)$', version_name)
    metadata = replace_one(metadata, r'^CurrentVersionCode: ([0-9]+)$', str(code))
    gradle = replace_one(gradle, name_pattern, version_name)
    gradle = replace_one(gradle, code_pattern, str(code))
    gradle_path.write_text(gradle, encoding='utf-8')
    metadata_path.write_text(metadata, encoding='utf-8')
    return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int)
    args = parser.parse_args()
    try:
        code = update(Path(__file__).resolve().parents[1], args.version_name, args.version_code)
    except (ValueError, OSError) as error:
        parser.exit(1, f'Version update failed: {error}\n')
    print(f'Prepared version {args.version_name} (code {code}); release tag is v{args.version_name}')


if __name__ == '__main__':
    main()
