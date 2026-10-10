"""Regression coverage for adb's misleading success status and partial instrumentation output."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


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
    def test_complete_suite_passes_and_start_records_are_not_counted(self):
        results, valid = runner.parse_results(transcript().replace('\n', '\r\n'))
        self.assertTrue(valid)
        self.assertEqual(len(results), 9)

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
            self.assertEqual(suite.get('failures'), '10')
            self.assertIn('process crashed', path.read_text())


if __name__ == '__main__':
    unittest.main()
