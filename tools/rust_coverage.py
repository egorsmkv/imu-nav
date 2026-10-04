#!/usr/bin/env python3
"""Measure production Rust lines across isolated Cargo and real JVM executions.

Requires Linux, the pinned Rust nightly and matching LLVM tools. Each execution
has fresh profile files; incompatible builds are joined only after LCOV export.
Branch coverage is diagnostic and remains in each domain's HTML/LCOV report.
"""
import argparse
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = 'nightly-2026-09-25'
CRATES = {'core': 'native/nav-core/src', 'jni': 'native/nav-jni/src',
          'sim': 'native/nav-sim/src', 'server': 'server/src'}
FLOORS = {'core': 95, 'trust': 95, 'jni': 85, 'sim': 95, 'server': 90}
LLVM_EXCLUSION = r'(/tests/|/tests\.rs$|_tests\.rs$|/kani_proofs/|/kani_proofs\.rs$|/\.cargo/|/rustc/|/\.rustup/|/build/|/target/)'


def production_file(filename):
    """Keep handwritten production sources, without hiding uncovered functions."""
    path = Path(filename).resolve()
    try:
        relative = path.relative_to(ROOT)
    except ValueError:
        return None
    if ('tests' in relative.parts or 'kani_proofs' in relative.parts or path.stem in ('tests', 'kani_proofs') or
            path.stem.endswith('_tests') or path.stem.startswith('test_')):
        return None
    return next((name for name, root in CRATES.items()
                 if relative.as_posix().startswith(root + '/')), None)


def artifacts(output, library=False):
    """Cargo owns artifact paths, including toolchain-specific directory layouts."""
    found = set()
    for line in output.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue  # Test harness output shares stdout with Cargo's JSON stream.
        if item.get('reason') != 'compiler-artifact':
            continue
        target = item['target']
        if not production_file(target['src_path']):
            # Integration test executables still contain instrumented library code.
            if not item.get('profile', {}).get('test'):
                continue
            if not Path(target['src_path']).resolve().is_relative_to(ROOT):
                continue
        if library:
            if 'cdylib' in target['kind']:
                found.update(p for p in item['filenames'] if p.endswith('.so'))
        elif item.get('executable'):
            found.add(item['executable'])
    if not found:
        raise RuntimeError('Cargo reported no coverage objects')
    missing = [path for path in found if not Path(path).is_file()]
    if missing:
        raise RuntimeError(f'Missing Cargo artifacts: {missing}')
    return sorted(found)


def validate_artifact_set(output, domain):
    """Require the test harnesses and spawned applications promised by each suite."""
    required = {
        'native': {('imu_nav_core', True), ('imu_nav_jni', True), ('imu-nav-sim', True),
                   ('imu-nav-sim', False), ('capture', True)},
        'heap-tests': {('imu_nav_jni', True)},
        'jni': {('imu_nav_jni', False)},
        'jni-heap': {('imu_nav_jni', False)},
        'server': {('imu_nav_cell_server', True), ('imu-nav-cell-server', True),
                   ('imu-nav-cell-server', False), ('cli', True), ('http_ws', True)},
    }[domain]
    found = set()
    for line in output.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if item.get('reason') == 'compiler-artifact':
            if item.get('executable') or 'cdylib' in item['target']['kind']:
                found.add((item['target']['name'], item['profile']['test']))
    if missing := required - found:
        raise RuntimeError(f'Incomplete {domain} artifacts: {sorted(missing)}')


def merge_lcov(reports):
    """Union executable lines and hits across builds; never combine raw domains."""
    sources = {}
    for report in reports:
        current = None
        for line in report.splitlines():
            if line.startswith('SF:'):
                filename = str(Path(line[3:]).resolve())
                current = sources.setdefault(filename, {}) if production_file(filename) else None
            elif line.startswith('DA:') and current is not None:
                number, hits, *_ = line[3:].split(',')
                number = int(number)
                current[number] = max(current.get(number, 0), int(hits))
            elif line == 'end_of_record':
                current = None
    return sources


def summarize(sources, suite):
    """Use integer comparisons so rounding cannot turn a failed gate green."""
    names = ['server'] if suite == 'server' else ['core', 'trust', 'jni', 'sim']
    if suite == 'all':
        names.append('server')
    result = {}
    for name in names:
        lines = [hits for path, counts in sources.items()
                 if (path == str(ROOT / 'native/nav-core/src/trust.rs') if name == 'trust'
                     else production_file(path) == name) for hits in counts.values()]
        if not lines:
            raise RuntimeError(f'No production coverage collected for {name}')
        covered = sum(hits > 0 for hits in lines)
        result[name] = dict(covered=covered, lines=len(lines), percent=100 * covered / len(lines),
                            minimum=FLOORS[name], passed=100 * covered >= FLOORS[name] * len(lines))
    # Missing files must fail instead of inflating the denominator by omission.
    for name in names:
        if name == 'trust':
            continue
        for source in (ROOT / CRATES[name]).rglob('*.rs'):
            if production_file(source) and str(source) not in sources:
                # Module-only lib.rs files have no executable lines.
                if re.search(r'\bfn\s+\w+', source.read_text()):
                    raise RuntimeError(f'Missing production module: {source}')
    return result


def checked(command, env, log):
    """Keep full diagnostics on disk and reject even nonfatal mapping warnings."""
    print(f'Running {command[0]} {command[1]} (log: {log})', flush=True)
    with log.open('w') as stream:
        process = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    output = log.read_text()
    llvm_warning = Path(str(command[0])).name.startswith('llvm-') and re.search(r'^(?:warning|error):', output, re.M | re.I)
    if process.returncode or llvm_warning:
        raise RuntimeError(f'Command or profile validation failed; see {log}\n{output[-4000:]}')
    return output


def llvm_tool(name, env):
    override = env.get(name.upper().replace('-', '_'))
    if override:
        return override
    sysroot = subprocess.check_output(['rustc', '--print', 'sysroot'], env=env, text=True).strip()
    host = re.search(r'host: (\S+)', subprocess.check_output(['rustc', '-vV'], env=env, text=True))[1]
    bundled = Path(sysroot) / 'lib/rustlib' / host / 'bin' / name
    if bundled.is_file():
        return str(bundled)
    version = re.search(r'LLVM version: (\d+)', subprocess.check_output(['rustc', '-vV'], env=env, text=True))[1]
    tool = shutil.which(f'{name}-{version}')
    if not tool:
        raise RuntimeError(f'Install llvm-tools-preview for {TOOLCHAIN}, or set {name.upper().replace("-", "_")}')
    return tool


def require_profiles(directory):
    profiles = sorted(path for path in directory.glob('*.profraw') if path.stat().st_size)
    if not profiles:
        raise RuntimeError(f'No nonempty execution profiles in {directory}')
    return profiles


def run(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, RUSTUP_TOOLCHAIN=TOOLCHAIN, DEBUGINFOD_URLS='',
               RUSTFLAGS='-C debuginfo=2', CARGO_INCREMENTAL='0',
               RUSTC_WORKSPACE_WRAPPER=str(ROOT / 'tools/rust_coverage_rustc.py'))
    cov, profdata = llvm_tool('llvm-cov', env), llvm_tool('llvm-profdata', env)
    rust_version = subprocess.check_output(['rustc', '-vV'], env=env, text=True)
    llvm_major = re.search(r'LLVM version: (\d+)', rust_version)[1]
    for tool in (cov, profdata):
        version = subprocess.check_output([tool, '--version'], text=True)
        if not re.search(r'version\s+' + llvm_major + r'\.', version):
            raise RuntimeError(f'Incompatible LLVM tool: {tool}: {version}')
    domains = []
    if args.suite != 'server':
        domains += [('native', 'native', ['test', '--workspace'], False),
                    ('heap-tests', 'native', ['test', '-p', 'imu-nav-jni', '--features', 'heap-profile'], False),
                    ('jni', 'native', ['build', '-p', 'imu-nav-jni'], True),
                    ('jni-heap', 'native', ['build', '-p', 'imu-nav-jni', '--features', 'heap-profile'], True)]
    if args.suite != 'native':
        domains += [('server', 'server', ['test'], False)]
    reports = []
    for name, workspace, cargo_args, jvm in domains:
        directory = output / name
        directory.mkdir(exist_ok=True)
        raw = directory / ('profiles-' + uuid.uuid4().hex)
        raw.mkdir()
        domain_env = dict(env, CARGO_TARGET_DIR=str(output / (workspace + '-target')),
                          LLVM_PROFILE_FILE=str(raw / '%p-%m.profraw'))
        cargo_output = checked(['cargo', *cargo_args, '--locked', '--manifest-path',
                                str(ROOT / workspace / 'Cargo.toml'), '--message-format=json'],
                               domain_env, directory / 'cargo.log')
        validate_artifact_set(cargo_output, name)
        objects = artifacts(cargo_output, library=jvm)
        if jvm:
            # Compilation profiles must not masquerade as JVM execution evidence.
            jvm_raw = raw / 'jvm'
            jvm_raw.mkdir()
            domain_env['LLVM_PROFILE_FILE'] = str(jvm_raw / '%p-%m.profraw')
            command = ['./gradlew', '--no-daemon', ':replay:test',
                       f'-PnativeLibraryDir={Path(objects[0]).parent}', '-PnativeCoverage']
            if name == 'jni-heap':
                command.append('-PnativeHeapProfile')
            checked(command, domain_env, directory / 'jvm.log')
            raw = jvm_raw
        profiles = require_profiles(raw)
        merged = directory / 'coverage.profdata'
        checked([profdata, 'merge', '-sparse', *map(str, profiles), '-o', str(merged)],
                env, directory / 'merge.log')
        common = ['--debuginfod=false', f'-instr-profile={merged}']
        for obj in objects:
            common += ['-object', obj]
        report = checked([cov, 'export', '-format=lcov', *common, f'-ignore-filename-regex={LLVM_EXCLUSION}'],
                         env, directory / 'coverage.lcov')
        reports.append(report)
        checked([cov, 'show', '-format=html', f'-output-dir={directory / "html"}',
                 '-show-branches=count', *common, f'-ignore-filename-regex={LLVM_EXCLUSION}'],
                env, directory / 'html.log')
        (directory / 'objects.json').write_text(json.dumps(objects, indent=2) + '\n')
    sources = merge_lcov(reports)
    summary = summarize(sources, args.suite)
    lcov = []
    for path, lines in sorted(sources.items()):
        lcov += [f'SF:{path}', *(f'DA:{number},{hits}' for number, hits in sorted(lines.items())),
                 f'LF:{len(lines)}', f'LH:{sum(hits > 0 for hits in lines.values())}', 'end_of_record']
    (output / 'coverage.lcov').write_text('\n'.join(lcov) + '\n')
    (output / 'summary.json').write_text(json.dumps({'toolchain': rust_version, 'coverage': summary}, indent=2) + '\n')
    rows = ''.join(f'<tr><td>{name}</td><td>{item["covered"]}/{item["lines"]}</td>'
                   f'<td>{item["percent"]:.2f}%</td><td>{item["minimum"]}%</td>'
                   f'<td>{"PASS" if item["passed"] else "FAIL"}</td></tr>' for name, item in summary.items())
    links = ''.join(f'<li><a href="{name}/html/index.html">{html.escape(name)} lines and branches</a></li>'
                    for name, *_ in domains)
    (output / 'index.html').write_text('<!doctype html><title>Rust production coverage</title>'
        '<h1>Rust production coverage</h1><table><tr><th>Crate</th><th>Lines</th><th>Coverage</th>'
        '<th>Minimum</th><th>Gate</th></tr>' + rows + '</table><ul>' + links + '</ul>')
    print(json.dumps(summary, indent=2), flush=True)
    if not all(item['passed'] for item in summary.values()):
        raise RuntimeError(f'Coverage gates failed; see {output / "index.html"}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['all', 'native', 'server'], default='all')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rust-coverage')
    args = parser.parse_args()
    try:
        run(args)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
