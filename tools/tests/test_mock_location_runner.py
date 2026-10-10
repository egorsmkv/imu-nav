"""Regression coverage for adb's misleading success status and partial instrumentation output."""
import importlib.util
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import call, patch


SPEC = importlib.util.spec_from_file_location('mock_location_runner', Path(__file__).parents[1] / 'run_mock_location_tests.py')
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


def transcript(code=0, complete=True):
    records = []
    for classname, count in runner.EXPECTED_CLASSES.items():
        for index in range(count):
            records.append(f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test=test{index}\n'
                           f'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class={classname}\n'
                           f'INSTRUMENTATION_STATUS: test=test{index}\nINSTRUMENTATION_STATUS: stack=assertion failed\n'
                           f'  at SyntheticTest.kt:42\nINSTRUMENTATION_STATUS_CODE: {code}\n')
    return ''.join(records) + ('INSTRUMENTATION_CODE: -1\n' if complete else '')


class InstrumentationResultsTest(unittest.TestCase):
    def test_old_images_use_kernel_mappings_when_getconf_is_missing(self):
        missing = subprocess.CalledProcessError(127, ['adb', 'shell', 'getconf', 'PAGE_SIZE'], output='getconf: not found')
        for kb in (4, 16):
            with self.subTest(kb=kb), patch.object(runner, 'adb', side_effect=[missing, f'KernelPageSize:    {kb} kB\nKernelPageSize: 2048 kB\n']) as adb:
                self.assertEqual(runner.device_page_size(), str(kb * 1024))
                self.assertEqual(adb.call_args_list, [call('shell', 'getconf', 'PAGE_SIZE'), call('shell', 'cat', '/proc/self/smaps')])

    def test_page_size_fallback_does_not_hide_transport_errors_or_missing_evidence(self):
        offline = subprocess.CalledProcessError(1, ['adb'], output='device offline')
        with patch.object(runner, 'adb', side_effect=offline) as adb:
            with self.assertRaises(subprocess.CalledProcessError):
                runner.device_page_size()
            self.assertEqual(adb.call_count, 1)
        missing = subprocess.CalledProcessError(127, ['adb'], output='getconf: not found')
        with patch.object(runner, 'adb', side_effect=[missing, 'unreadable mappings']):
            with self.assertRaisesRegex(RuntimeError, 'Cannot determine kernel page size'):
                runner.device_page_size()

    def test_complete_suite_passes_and_start_records_are_not_counted(self):
        results, valid = runner.parse_results(transcript().replace('\n', '\r\n'))
        self.assertTrue(valid)
        self.assertEqual(len(results), sum(runner.EXPECTED_CLASSES.values()))

    def test_zero_tests_and_partial_process_crash_fail(self):
        self.assertFalse(runner.parse_results('INSTRUMENTATION_CODE: -1\n')[1])
        self.assertFalse(runner.parse_results(transcript(complete=False))[1])

    def test_failure_error_skips_and_assumptions_fail(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code):
                self.assertFalse(runner.parse_results(transcript(code=code))[1])

    def test_missing_class_and_duplicate_successes_fail(self):
        output = transcript().replace('MockLocationProcessRecoveryTest', 'OtherTest')
        self.assertFalse(runner.parse_results(output)[1])
        output = transcript().replace('test=test1', 'test=test0')
        self.assertFalse(runner.parse_results(output)[1])

    def test_host_recovery_stage_requires_its_own_completed_test(self):
        classname = 'org.imunav.app.location.MockLocationAppRecoveryTest'
        output = f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test=seed\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.parse_results(output, {classname: 1})[1])
        self.assertFalse(runner.parse_results(output)[1])
        self.assertFalse(runner.parse_results('INSTRUMENTATION_CODE: -1\n', {classname: 1})[1])

    def test_assertion_stack_and_runner_failure_are_in_junit(self):
        results, valid = runner.parse_results(transcript(code=-2))
        self.assertIn('SyntheticTest.kt:42', results[0].stack)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'results.xml'
            runner.write_junit(path, results, valid, 'process crashed')
            suite = ET.parse(path).getroot()
            self.assertEqual(suite.get('failures'), str(sum(runner.EXPECTED_CLASSES.values()) + 1))
            self.assertIn('process crashed', path.read_text())

    def test_timeout_after_passing_normal_suite_never_passes_recovery(self):
        classname = 'org.imunav.app.location.MockLocationAppRecoveryTest'
        seed = f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test=seed\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n'
        for outputs in ([transcript(), subprocess.TimeoutExpired('adb', 300, output=b'partial recovery output')],
                        [transcript(), seed, subprocess.TimeoutExpired('adb', 300, output=b'partial recovery output')]):
            with self.subTest(stages=len(outputs)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app = root / 'apks/fdroid/debug/app.apk'
                test = root / 'apks/androidTest/fdroid/debug/test.apk'
                for apk in (app, test):
                    apk.parent.mkdir(parents=True, exist_ok=True)
                    apk.write_bytes(b'synthetic APK fixture')
                argv = ['runner', '--apk-directory', str(root / 'apks'), '--output', str(root / 'reports')]
                with patch.object(sys, 'argv', argv), patch.object(runner, 'adb', return_value='3'), \
                        patch.object(runner, 'instrumentation', side_effect=outputs), patch('builtins.print'):
                    with self.assertRaises(SystemExit) as raised:
                        runner.main()
                self.assertEqual(raised.exception.code, 1)
                self.assertGreater(int(ET.parse(root / 'reports/results.xml').getroot().get('failures')), 0)
                self.assertIn('partial recovery output', (root / 'reports/instrumentation.txt').read_text())

    def test_completed_stages_require_bounded_transport_evidence_and_cleanup(self):
        classname = 'org.imunav.app.location.MockLocationAppRecoveryTest'
        stages = [f'INSTRUMENTATION_STATUS: class={classname}\nINSTRUMENTATION_STATUS: test={name}\n'
                  'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n' for name in ('seed', 'restore')]
        for failed_step in (None, 'transport', 'logcat', 'location', 'cleanup'):
            with self.subTest(failed_step=failed_step), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app = root / 'apks/fdroid/debug/app.apk'
                test = root / 'apks/androidTest/fdroid/debug/test.apk'
                for apk in (app, test):
                    apk.parent.mkdir(parents=True, exist_ok=True)
                    apk.write_bytes(b'synthetic APK fixture')
                force_stops = 0

                def fake_adb(*arguments, timeout=60):
                    nonlocal force_stops
                    if arguments == ('shell', 'am', 'force-stop', 'org.imunav.app.debug'):
                        force_stops += 1
                    failing = ((failed_step == 'transport' and arguments == ('wait-for-device',)) or
                               (failed_step == 'logcat' and arguments == ('logcat', '-d')) or
                               (failed_step == 'location' and arguments == ('shell', 'dumpsys', 'location')) or
                               (failed_step == 'cleanup' and force_stops == 2))
                    if failing:
                        raise subprocess.CalledProcessError(1, ['adb', *arguments], output='adb: device offline')
                    return '3'

                argv = ['runner', '--apk-directory', str(root / 'apks'), '--output', str(root / 'reports')]
                with patch.object(sys, 'argv', argv), patch.object(runner, 'adb', side_effect=fake_adb) as mock_adb, \
                        patch.object(runner, 'instrumentation', side_effect=[transcript(), *stages]), patch('builtins.print'):
                    with self.assertRaises(SystemExit) as raised:
                        runner.main()
                self.assertIn(call('wait-for-device', timeout=30), mock_adb.call_args_list)
                self.assertEqual(force_stops, 2)
                self.assertEqual(raised.exception.code, int(failed_step is not None))
                suite = ET.parse(root / 'reports/results.xml').getroot()
                self.assertEqual(suite.get('failures'), str(int(failed_step is not None)))
                if failed_step is not None:
                    self.assertIn('adb: device offline', (root / 'reports/instrumentation.txt').read_text())

    def test_16kb_job_rejects_a_4kb_emulator_before_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ('apks/fdroid/debug/app.apk', 'apks/androidTest/fdroid/debug/test.apk'):
                apk = root / relative
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.write_bytes(b'synthetic APK fixture')
            argv = ['runner', '--apk-directory', str(root / 'apks'), '--output', str(root / 'reports'), '--expected-page-size', '16384']
            with patch.object(sys, 'argv', argv), patch.object(runner, 'adb', return_value='4096') as adb, \
                    patch.object(runner, 'instrumentation') as instrumentation, patch('builtins.print'):
                with self.assertRaises(SystemExit) as raised:
                    runner.main()
            self.assertEqual(raised.exception.code, 1)
            self.assertEqual((root / 'reports/page-size.txt').read_text(), '4096\n')
            self.assertFalse(any(c.args[0] == 'install' for c in adb.call_args_list))
            instrumentation.assert_not_called()


if __name__ == '__main__':
    unittest.main()
