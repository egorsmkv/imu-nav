#!/usr/bin/env python3
"""Collect Kotlin host coverage and enforce reviewed per-module line baselines.

Requires JDK 17, Android SDK 36 and a Rust toolchain supported by native/Cargo.toml.
The default run verifies config/kotlin-coverage-baseline.json. --update-baseline
explicitly replaces that file after a complete, successful measurement.
"""
import argparse
import html
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('core', 'routing', 'replay', 'app')
EXECUTIONS = ('core-test', 'routing-test', 'replay-test', 'app-testFdroidDebugUnitTest')
BASELINE = ROOT / 'config/kotlin-coverage-baseline.json'
SCHEMA = 1


def jacoco_version():
    """Use the same version as Gradle so tool upgrades cannot bypass baseline review."""
    catalog = (ROOT / 'gradle/libs.versions.toml').read_text()
    version = re.search(r'^jacoco\s*=\s*"([^"]+)"\s*$', catalog, re.M)
    if not version:
        raise RuntimeError('Missing pinned JaCoCo version in the Gradle version catalog')
    return version[1]


JACOCO = jacoco_version()


def source_inventory(root=ROOT):
    """Resolve original files by Kotlin package and filename, independent of compiler output."""
    inventory = {}
    for module in MODULES:
        for source in (root / module / 'src/main/kotlin').rglob('*.kt'):
            contents = source.read_text()
            package = re.search(r'^package\s+([\w.]+)', contents, re.M)
            if not package:
                raise RuntimeError(f'Missing source package: {source}')
            key = (package[1].replace('.', '/'), source.name)
            if key in inventory:
                raise RuntimeError(f'Ambiguous source ownership: {key}')
            inventory[key] = source.relative_to(root).as_posix()
    return inventory


def merge_reports(reports, inventory, root=ROOT):
    """Union executable/covered source lines; wrappers compiled by replay belong to app."""
    sources = {}
    for domain in MODULES:
        report = reports[domain]
        if report.tag != 'report' or not report.findall('package/class'):
            raise RuntimeError(f'Empty or invalid {domain} coverage report')
        owned_sources = 0
        for package in report.findall('package'):
            for source in package.findall('sourcefile'):
                filename = source.attrib['name']
                if not filename.endswith('.kt'):
                    raise RuntimeError(f'Unexpected non-Kotlin production source: {filename}')
                key = (package.attrib['name'], filename)
                if key not in inventory:
                    # JaCoCo filters generated inline dependency bodies but retains empty
                    # source entries (for example stdlib Comparisons.kt and Compose Effects.kt).
                    classes = [item for item in package.findall('class') if item.get('sourcefilename') == filename]
                    if not source.findall('line') and classes and all('$$inlined$' in item.get('name', '') for item in classes):
                        continue
                    raise RuntimeError(f'Unresolved production source in {domain}: {key}')
                path = inventory[key]
                owner = path.split('/')[0]
                if owner != domain and not (domain == 'replay' and owner == 'app' and '/nativecore/' in path):
                    raise RuntimeError(f'Unexpected {owner} source in {domain}: {path}')
                owned_sources += owner == domain
                lines = sources.setdefault(path, {})
                length = len((root / path).read_text().splitlines())
                for line in source.findall('line'):
                    number = int(line.attrib['nr'])
                    missed, covered = int(line.attrib['mi']), int(line.attrib['ci'])
                    if not 1 <= number <= length or missed < 0 or covered < 0 or missed + covered == 0:
                        raise RuntimeError(f'Invalid coverage line in {path}: {line.attrib}')
                    lines[number] = lines.get(number, False) or covered > 0
        if not owned_sources:
            raise RuntimeError(f'No owned production sources in {domain}')
    missing = set(inventory.values()) - set(sources)
    if missing:
        raise RuntimeError(f'Missing production sources in reports: {sorted(missing)}')
    return sources


def summarize(sources):
    result = {}
    for module in MODULES:
        lines = [hit for path, values in sources.items() if path.startswith(module + '/') for hit in values.values()]
        if not lines:
            raise RuntimeError(f'No executable production lines for {module}')
        result[module] = {'covered': sum(lines), 'lines': len(lines), 'percent': 100 * sum(lines) / len(lines)}
    return result


def verify_baseline(summary, baseline):
    if baseline.get('schema') != SCHEMA or baseline.get('jacoco') != JACOCO:
        raise RuntimeError('Baseline schema or JaCoCo version differs; review and explicitly update the baseline')
    if set(baseline.get('modules', {})) != set(MODULES):
        raise RuntimeError('Baseline must contain all four modules')
    for module in MODULES:
        floor = baseline['modules'][module]
        covered, total = floor.get('covered'), floor.get('lines')
        if type(covered) is not int or type(total) is not int or not 0 <= covered <= total or total <= 0:
            raise RuntimeError(f'Invalid baseline counts for {module}')
        current = summary[module]
        current['baseline_covered'] = covered
        current['baseline_lines'] = total
        current['passed'] = current['covered'] * total >= covered * current['lines']
    return all(item['passed'] for item in summary.values())


def validate_inputs(directory):
    """A fresh report must include every promised execution and real class output."""
    for name in EXECUTIONS:
        execution = directory / 'execution' / (name + '.exec')
        if not execution.is_file() or execution.stat().st_size == 0:
            raise RuntimeError(f'Missing or empty execution data: {execution}')
    for module in MODULES:
        manifest = directory / module / 'classes.txt'
        if not manifest.is_file():
            raise RuntimeError(f'Missing production class inventory: {manifest}')
        classes = manifest.read_text().splitlines()
        if not classes:
            raise RuntimeError(f'Empty production class inventory: {manifest}')
        for filename in classes:
            path = Path(filename)
            if path.suffix != '.class' or not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f'Missing production class: {path}')
        for filename in ('report.xml', 'html/index.html'):
            report = directory / module / filename
            if not report.is_file() or report.stat().st_size == 0:
                raise RuntimeError(f'Missing {module} coverage report: {report}')


def validate_log(output, returncode):
    if returncode or re.search(r'(does not match|do not match|different class with same name|execution data.*(?:missing|invalid)|error while instrumenting)', output, re.I):
        raise RuntimeError('Gradle failed or reported incompatible coverage data; see gradle.log')


def write_reports(output, domain_directory, sources, summary, root=ROOT):
    """Publish deduplicated line reports alongside original JaCoCo branch reports."""
    (output / 'summary.json').write_text(json.dumps({'schema': SCHEMA, 'jacoco': JACOCO, 'modules': summary}, indent=2) + '\n')
    lcov = []
    file_rows = []
    style = '<style>body{font:16px system-ui;max-width:1200px;margin:2em auto}td,th{text-align:left;padding:.3em 1em}pre{margin:0;white-space:pre-wrap}.hit{background:#ddf5df}.miss{background:#ffe1df}a{color:#174ea6}</style>'
    for path, lines in sorted(sources.items()):
        lcov += [f'SF:{root / path}', *(f'DA:{number},{int(hit)}' for number, hit in sorted(lines.items())),
                 f'LF:{len(lines)}', f'LH:{sum(lines.values())}', 'end_of_record']
        destination = output / 'sources' / (path + '.html')
        destination.parent.mkdir(parents=True, exist_ok=True)
        contents = []
        for number, line in enumerate((root / path).read_text().splitlines(), 1):
            status = ('hit' if lines[number] else 'miss') if number in lines else ''
            contents.append(f'<pre id="L{number}" class="{status}">{number:4} {html.escape(line)}</pre>')
        contents = ''.join(contents)
        destination.write_text(f'<!doctype html><meta charset="utf-8"><title>{html.escape(path)}</title>{style}<h1>{html.escape(path)}</h1>' + contents)
        file_rows.append(f'<tr><td><a href="sources/{html.escape(path)}.html">{html.escape(path)}</a></td><td>{sum(lines.values())}/{len(lines)}</td></tr>')
    (output / 'coverage.lcov').write_text('\n'.join(lcov) + '\n')
    rows = ''.join(f'<tr><td>{module}</td><td>{item["covered"]}/{item["lines"]}</td><td>{item["percent"]:.2f}%</td>'
                   f'<td>{item["baseline_covered"]}/{item["baseline_lines"]}</td><td>{"PASS" if item["passed"] else "FAIL"}</td></tr>' for module, item in summary.items())
    relative = domain_directory.relative_to(output).as_posix()
    links = ''.join(f'<li><a href="{relative}/{module}/html/index.html">{module}: original JaCoCo lines and branches</a></li>' for module in MODULES)
    (output / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Kotlin host coverage</title>' + style +
        '<h1>Kotlin host coverage</h1><p>Production source lines, counted once under their owning module. Branches are diagnostic in the original reports.</p>' +
        '<table><tr><th>Module</th><th>Covered/total</th><th>Coverage</th><th>Baseline</th><th>Gate</th></tr>' + rows +
        '</table><ul>' + links + '</ul><h2>Combined source coverage</h2><table>' + ''.join(file_rows) + '</table>')


def run(args):
    output = args.output.resolve()
    domain_directory = output / 'runs' / uuid.uuid4().hex
    domain_directory.mkdir(parents=True)
    log = domain_directory / 'gradle.log'
    env = dict(os.environ)
    # A real local map pack must not silently change the portable CI baseline.
    env.pop('GRAPH_DIR', None)
    command = ['./gradlew', '--no-daemon', '-PkotlinCoverage', f'-PkotlinCoverageOutput={domain_directory}', 'kotlinCoverageReport']
    print(f'Collecting Kotlin host coverage (log: {log})', flush=True)
    with log.open('w') as stream:
        process = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    validate_log(log.read_text(), process.returncode)
    validate_inputs(domain_directory)
    inventory = source_inventory()
    reports = {module: ET.parse(domain_directory / module / 'report.xml').getroot() for module in MODULES}
    sources = merge_reports(reports, inventory)
    summary = summarize(sources)
    if args.update_baseline:
        baseline = {'schema': SCHEMA, 'jacoco': JACOCO,
                    'modules': {name: {key: value[key] for key in ('covered', 'lines')} for name, value in summary.items()}}
    else:
        if not BASELINE.is_file():
            raise RuntimeError('No baseline exists; use --update-baseline to record the initial measurement')
        baseline = json.loads(BASELINE.read_text())
    passed = verify_baseline(summary, baseline)
    write_reports(output, domain_directory, sources, summary)
    if args.update_baseline:
        BASELINE.write_text(json.dumps(baseline, indent=2) + '\n')
        print(f'Updated {BASELINE}; review this change before committing.', flush=True)
    print(json.dumps(summary, indent=2), flush=True)
    print(f'Report: {output / "index.html"}', flush=True)
    if not passed:
        raise RuntimeError('Kotlin coverage fell below the committed baseline')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/kotlin-coverage')
    parser.add_argument('--update-baseline', action='store_true', help='Explicitly replace the reviewed module baselines after measuring all tests')
    args = parser.parse_args()
    try:
        run(args)
    except (RuntimeError, OSError, ET.ParseError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
