"""Fail-closed validation of Kani evidence, including non-vacuity and subprocess deadlines."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import MagicMock, call, patch

SPEC = importlib.util.spec_from_file_location('verify_native', Path(__file__).parents[1] / 'verify_native.py')
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


def successful_report():
    names = verification.REQUIRED
    return {
        'metadata': {'version': '1.0', 'kani_version': verification.VERSION},
        'tools': {'rustc': verification.RUSTC, 'kani': verification.VERSION},
        'project': {'crate_name': ['imu_nav_core']},
        'harness_metadata': [{'pretty_name': name, 'attributes': {'kind': 'Proof', 'should_panic': False}}
                             for name in names],
        'error_details': [{'harness_id': name, 'has_errors': False} for name in names],
        'verification_results': {
            'summary': {'status': 'completed', 'failed': 0, 'total_harnesses': len(names),
                        'executed': len(names), 'successful': len(names)},
            'results': [{'harness_id': name, 'status': 'Success', 'checks': [
                {'category': 'assertion', 'status': 'Success', 'description': 'state invariant'},
                *({'category': 'cover', 'status': 'Satisfied', 'description': label} for label in labels)
            ]} for name, labels in names.items()]}}


class VerificationTests(unittest.TestCase):
    def test_complete_evidence_passes(self):
        self.assertTrue(verification.validate_report(successful_report())['passed'])

    def test_missing_or_duplicate_harness_cannot_pass(self):
        report = successful_report()
        results = report['verification_results']['results']
        results.pop()
        with self.assertRaisesRegex(ValueError, 'inventory'):
            verification.validate_report(report)
        results.append(copy.deepcopy(results[0]))
        with self.assertRaisesRegex(ValueError, 'inventory'):
            verification.validate_report(report)

    def test_failure_timeout_and_undetermined_are_rejected(self):
        for status in ['Failure', 'Timeout', 'Undetermined', 'Error']:
            with self.subTest(status=status):
                report = successful_report()
                report['verification_results']['results'][0]['status'] = status
                with self.assertRaises(ValueError):
                    verification.validate_report(report)

    def test_hidden_check_failure_and_unreachable_witness_are_rejected(self):
        for category, status in [('assertion', 'Failure'), ('unwind', 'Failure'),
                                 ('unsupported', 'Failure'), ('assertion', 'Undetermined'),
                                 ('cover', 'Unsatisfiable')]:
            with self.subTest(category=category, status=status):
                report = successful_report()
                report['verification_results']['results'][0]['checks'].append(
                    {'category': category, 'status': status, 'description': 'bad check'})
                with self.assertRaises(ValueError):
                    verification.validate_report(report)

    def test_removed_cover_or_all_unreachable_assertions_are_rejected(self):
        for remove_covers in [True, False]:
            report = successful_report()
            result = report['verification_results']['results'][0]
            if remove_covers:
                result['checks'] = [check for check in result['checks'] if check['category'] != 'cover']
            else:
                result['checks'][0]['status'] = 'Unreachable'
            with self.assertRaises(ValueError):
                verification.validate_report(report)

    def test_malformed_report_wrong_toolchain_and_expected_panic_are_rejected(self):
        for report in [{}, [], {'metadata': None}]:
            with self.assertRaisesRegex(ValueError, 'Malformed'):
                verification.validate_report(report)
        report = successful_report()
        report['tools']['rustc'] = 'wrong compiler'
        with self.assertRaisesRegex(ValueError, 'compiler'):
            verification.validate_report(report)
        report = successful_report()
        report['harness_metadata'][0]['attributes']['should_panic'] = True
        with self.assertRaisesRegex(ValueError, 'ordinary proof'):
            verification.validate_report(report)

    def test_deadline_kills_solver_process_group(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.pid = 12345
        process.wait.side_effect = [subprocess.TimeoutExpired('cargo', 1), -9]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', return_value=process), \
                patch.object(verification.time, 'monotonic', side_effect=[0, 0, 1, 1]), \
                patch.object(verification.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'timed out'):
                verification.run_logged(['cargo', 'kani'], {}, Path(directory) / 'log', 1)
            kill.assert_called_once_with(process.pid, verification.signal.SIGKILL)

    def test_progress_polling_does_not_restart_the_deadline(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.pid = 12345
        process.wait.side_effect = [subprocess.TimeoutExpired('cargo', 30),
                                    subprocess.TimeoutExpired('cargo', 30), -9]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', return_value=process), \
                patch.object(verification.time, 'monotonic', side_effect=[0, 0, 30, 30, 60, 60]), \
                patch.object(verification.os, 'killpg'), \
                patch('builtins.print') as output:
            with self.assertRaisesRegex(RuntimeError, 'timed out after 60s'):
                verification.run_logged(['cargo', 'kani'], {}, Path(directory) / 'log', 60)
            self.assertEqual(process.wait.call_args_list, [call(timeout=30), call(timeout=30), call()])
            self.assertEqual(output.call_count, 2)

    def test_child_exit_race_preserves_the_original_error(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.pid = 12345
        process.wait.side_effect = [RuntimeError('interrupted'), -9]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', return_value=process), \
                patch.object(verification.os, 'killpg', side_effect=ProcessLookupError):
            with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                verification.run_logged(['cargo', 'kani'], {}, Path(directory) / 'log', 60)

    def test_success_after_a_progress_update_is_not_killed(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.wait.side_effect = [subprocess.TimeoutExpired('cargo', 30), 0]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification.subprocess, 'Popen', return_value=process), \
                patch.object(verification.time, 'monotonic', side_effect=[0, 0, 30, 30]), \
                patch.object(verification.os, 'killpg') as kill, \
                patch('builtins.print') as output:
            log = verification.run_logged(['cargo', 'kani'], {}, Path(directory) / 'log', 60)
            self.assertIn('Command: cargo kani', log)
            kill.assert_not_called()
            output.assert_called_once()

    @unittest.skipUnless(sys.platform == 'linux', 'Kani process-group cleanup targets Linux runners')
    def test_cancellation_records_failure_and_reaps_the_real_child(self):
        # Exercise actual signal delivery and subprocess cleanup, without launching Kani.
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                pid_path = output / 'child.pid'
                child_code = (f'import os, time; from pathlib import Path; '
                              f'Path({str(pid_path)!r}).write_text(str(os.getpid())); time.sleep(60)')
                runner_code = f'''
import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('verification', {str(SPEC.origin)!r})
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
verification.OUTPUT = Path({directory!r})
real_run_logged = verification.run_logged
def run_logged(command, env, path, timeout):
    if '--version' in command:
        return 'Kani Rust Verifier {verification.VERSION} (cargo plugin)\\n'
    return real_run_logged([sys.executable, '-c', {child_code!r}], env, path, timeout)
verification.run_logged = run_logged
sys.exit(verification.main())
'''
                child_pid = None
                with subprocess.Popen([sys.executable, '-c', runner_code], stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, text=True) as runner:
                    try:
                        deadline = time.monotonic() + 5
                        while runner.poll() is None and time.monotonic() < deadline:
                            try:
                                child_pid = int(pid_path.read_text())
                                break
                            except (FileNotFoundError, ValueError):
                                time.sleep(0.01)
                        self.assertIsNotNone(child_pid, 'verification child did not start')
                        runner.send_signal(signum)
                        _, stderr = runner.communicate(timeout=5)
                        self.assertEqual(runner.returncode, 1, stderr)
                        summary = json.loads((output / 'summary.json').read_text())
                        self.assertFalse(summary['passed'])
                        self.assertIn(signal.Signals(signum).name, summary['error'])
                        self.assertIn(signal.Signals(signum).name, stderr)
                        with self.assertRaises(ProcessLookupError):
                            os.kill(child_pid, 0)
                        child_pid = None
                    finally:
                        if runner.poll() is None:
                            runner.kill()
                        if child_pid is not None:
                            try:
                                os.killpg(child_pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                        runner.communicate(timeout=5)

    def test_failed_run_cannot_reuse_previous_success(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(verification, 'OUTPUT', Path(directory)), \
                patch.object(verification, 'run_logged', side_effect=RuntimeError('compile failure')):
            (Path(directory) / 'results.json').write_text(json.dumps(successful_report()))
            (Path(directory) / 'summary.json').write_text('{"passed": true}')
            self.assertEqual(verification.main(), 1)
            self.assertFalse((Path(directory) / 'results.json').exists())
            self.assertFalse(json.loads((Path(directory) / 'summary.json').read_text())['passed'])


if __name__ == '__main__':
    unittest.main()
