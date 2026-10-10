#!/usr/bin/env python3
"""Run the GPS AndroidTest package on a disposable emulator and retain strict JUnit evidence.

Build APKs once, then use the identical APKs on every API. adb's shell exit status alone does
not reliably signal instrumentation failures; validate test status records and completion too.
"""
import argparse
from dataclasses import dataclass
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


EXPECTED_CLASSES = {
    'org.imunav.app.location.AndroidMockLocationProviderTest': 5,
    'org.imunav.app.location.MockLocationSharingTest': 3,
    'org.imunav.app.location.MockLocationProcessRecoveryTest': 1,
}


@dataclass
class Result:
    classname: str
    name: str
    code: int
    stack: str = ''


def parse_results(text, expected_classes=None):
    """Keep only terminal JUnit records, preserving multiline assertion stacks."""
    expected_classes = EXPECTED_CLASSES if expected_classes is None else expected_classes
    results, fields, current_key = [], {}, None
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                fields[key] = value
                current_key = key
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(':')[2].strip())
            if code != 1 and 'class' in fields and 'test' in fields:
                results.append(Result(fields['class'], fields['test'], code, fields.get('stack', '')))
            fields, current_key = {}, None
        elif current_key == 'stack' and not line.startswith('INSTRUMENTATION_'):
            fields[current_key] += '\n' + line
    complete = any(line.strip() == 'INSTRUMENTATION_CODE: -1' for line in text.splitlines())
    counts = {name: sum(r.classname == name and r.code == 0 for r in results) for name in expected_classes}
    valid = complete and all(r.code == 0 for r in results) and all(counts[name] >= minimum for name, minimum in expected_classes.items())
    # Repeated success records must not cover for a missing test.
    valid = valid and len({(r.classname, r.name) for r in results}) == len(results)
    return results, valid


def write_junit(path, results, valid, diagnostic=''):
    suite = ET.Element('testsuite', name='Android GPS instrumentation')
    for result in results:
        case = ET.SubElement(suite, 'testcase', classname=result.classname, name=result.name)
        if result.code != 0:
            ET.SubElement(case, 'failure', message=f'Instrumentation status {result.code}').text = result.stack
    if not valid:
        case = ET.SubElement(suite, 'testcase', classname='instrumentation', name='complete_expected_suite')
        ET.SubElement(case, 'failure', message='GPS suite failed, crashed, skipped or did not run all expected tests').text = diagnostic
    suite.set('tests', str(len(suite)))
    suite.set('failures', str(sum(case.find('failure') is not None for case in suite)))
    ET.indent(suite)
    ET.ElementTree(suite).write(path, encoding='utf-8', xml_declaration=True)


def adb(*arguments, timeout=60):
    return subprocess.run(['adb', *arguments], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=True).stdout


def instrumentation(classes):
    return adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes, '-e', 'timeout_msec', '60000',
               'org.imunav.app.debug.test/androidx.test.runner.AndroidJUnitRunner', timeout=300)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results, valid, transcript, original_location_mode = [], False, '', None
    try:
        apks = list(args.apk_directory.rglob('*.apk'))
        apps = [path for path in apks if 'androidTest' not in path.parts]
        tests = [path for path in apks if 'androidTest' in path.parts]
        if len(apps) != 1 or len(tests) != 1:
            raise RuntimeError(f'Expected one app and one test APK, found {len(apps)} and {len(tests)}')
        (args.output / 'android-api.txt').write_text(adb('shell', 'getprop', 'ro.build.version.sdk'))
        original_location_mode = adb('shell', 'settings', 'get', 'secure', 'location_mode').strip()
        adb('install', '-r', '-g', str(apps[0]))
        adb('install', '-r', '-g', str(tests[0]))
        adb('logcat', '-c')
        transcript = instrumentation(','.join(EXPECTED_CLASSES))
        results, valid = parse_results(transcript)
        if valid:
            recovery_class = 'org.imunav.app.location.MockLocationAppRecoveryTest'
            seed = instrumentation(recovery_class + '#seedPersistedTripAndLeaveGpsActive')
            transcript += '\nWhole-app recovery seed:\n' + seed
            seed_results, seed_valid = parse_results(seed, {recovery_class: 1})
            results += seed_results
            valid = valid and seed_valid
            if seed_valid:
                adb('shell', 'am', 'force-stop', 'org.imunav.app.debug')
                restored = instrumentation(recovery_class + '#relaunchRestoresSavedTripAndPublishesFreshGps')
                transcript += '\nWhole-app recovery after force-stop:\n' + restored
                restored_results, restored_valid = parse_results(restored, {recovery_class: 1})
                results += restored_results
                valid = valid and restored_valid
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        transcript += f'\nRunner error: {error}\n'
    finally:
        (args.output / 'instrumentation.txt').write_text(transcript)
        write_junit(args.output / 'results.xml', results, valid, transcript)
        for filename, command in [('logcat.txt', ('logcat', '-d')), ('location.txt', ('shell', 'dumpsys', 'location'))]:
            try:
                (args.output / filename).write_text(adb(*command))
            except (OSError, subprocess.SubprocessError) as error:
                (args.output / filename).write_text(str(error))
        # Fresh emulators only: remove runtime processes and shell-selected mock access after evidence.
        try:
            adb('shell', 'am', 'force-stop', 'org.imunav.app.debug')
            adb('shell', 'appops', 'set', 'org.imunav.app.debug', 'android:mock_location', 'default')
            if original_location_mode is not None:
                adb('shell', 'settings', 'put', 'secure', 'location_mode', original_location_mode)
        except (OSError, subprocess.SubprocessError) as error:
            valid = False
            transcript += f'\nEmulator cleanup error: {error}\n'
            (args.output / 'instrumentation.txt').write_text(transcript)
            write_junit(args.output / 'results.xml', results, valid, transcript)
    print(transcript)
    print(f'GPS instrumentation: {len(results)} terminal test records; {"PASS" if valid else "FAIL"}')
    raise SystemExit(0 if valid else 1)


if __name__ == '__main__':
    main()
