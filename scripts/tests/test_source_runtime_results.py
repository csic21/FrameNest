"""Synthetic host reports and mocked shell cleanup. No native/emulator/security action."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_source_runtime_results as target
import check_source_runtime_ui_result as ui_target


class SourceRuntimeResultTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'input').mkdir()
        pair = {'status': 'retained-pair-verified', 'profile': {'runtime_identity': 'source', 'aar_sha256': 'a' * 64},
                'app': {'sha256': 'b' * 64}, 'test': {'sha256': 'c' * 64}, 'fixture_manifest_sha256': 'd' * 64}
        (self.root / 'input/pair-verification.json').write_text(json.dumps(pair))
        self.report = {'schema_version': 1, 'status': 'passed', 'runtime_actual_native': '1.13.8',
                       'source_runtime_comparison': True, 'hy_mt_native_build_profile': False,
                       'runtime_build_identity': 'source', 'source_aar_sha256': 'a' * 64,
                       'apk_sha256': 'b' * 64, 'test_apk_sha256': 'c' * 64, 'fixture_manifest_sha256': 'd' * 64,
                       'api': 35, 'abi': 'x86_64', 'windows_per_pass': 33,
                       'runs': [{'text': 'synthetic', 'tokens': ['a'], 'decode_ms': 1} for _ in range(198)],
                       'gap_coverage_accepted': False, 'accuracy_accepted': False,
                       'hardware_performance_accepted': False, 'translation_tested': False,
                       'production_float_input_bit_identical_to_raw': True, 'production_float_roundtrip_max_abs_error': 0,
                       'raw_stream_counts_balanced': True, 'production_close_reload_result_equal': True,
                       'lifecycle': {'raw_stream_created': 99, 'raw_stream_released': 99,
                                     'raw_recognizer_created': 1, 'raw_recognizer_released': 1},
                       'cancellation_probe': {'native_decode_interrupted_proven': False}}
        for i in (1, 2):
            (self.root / f'session-{i}').mkdir()
            self.write(i, self.report)
            (self.root / f'session-{i}/shell-status.txt').write_text('passed\n')

    def write(self, i, report):
        (self.root / f'session-{i}/result.json').write_text(json.dumps(report))

    def test_equal_outputs_allow_timing_variation_without_speed_claim(self):
        second = copy.deepcopy(self.report)
        second['runs'][0]['decode_ms'] = 100
        self.write(2, second)
        result = target.summarize(self.root)
        self.assertEqual(result['status'], 'passed')
        self.assertFalse(result['historical_stock_timing_comparison_accepted'])

    def test_text_or_token_variation_is_preserved_as_repeatability_difference(self):
        second = copy.deepcopy(self.report)
        second['runs'][0]['tokens'] = ['different']
        self.write(2, second)
        result = target.summarize(self.root)
        self.assertEqual(result['status'], 'output-repeatability-difference')
        self.assertEqual(result['different_output_row_indices'], [0])

    def test_partial_or_failed_shell_never_passes(self):
        (self.root / 'session-2/shell-status.txt').write_text('failed exit=124\n')
        self.assertEqual(target.summarize(self.root)['status'], 'failed')

    def test_missing_report_produces_failed_summary(self):
        (self.root / 'session-2/result.json').unlink()
        self.assertEqual(target.summarize(self.root)['status'], 'failed')
        self.assertTrue((self.root / 'source-session-summary.json').exists())

    def test_wrong_identity_or_counter_fails(self):
        second = copy.deepcopy(self.report)
        second['apk_sha256'] = 'f' * 64
        self.write(2, second)
        self.assertEqual(target.summarize(self.root)['status'], 'failed')
        second = copy.deepcopy(self.report)
        second['lifecycle']['raw_stream_released'] = 98
        self.write(2, second)
        self.assertEqual(target.summarize(self.root)['status'], 'failed')


class SourceUiResultTest(unittest.TestCase):
    def check(self, output, exit_code):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'input').mkdir()
        (root / 'ui').mkdir()
        pair = {'app': {'sha256': 'a' * 64}, 'test': {'sha256': 'b' * 64},
                'profile': {'runtime_identity': 'source'}}
        (root / 'input/pair-verification.json').write_text(json.dumps(pair))
        if output is not None:
            (root / 'ui/instrumentation.txt').write_text(output)
        return ui_target.record(root, exit_code)

    def test_seven_tests_pass_as_separate_phone_only_evidence(self):
        result = self.check('OK (7 tests)\n', 0)
        self.assertEqual(result['status'], 'passed')
        self.assertFalse(result['tablet_tested'])
        self.assertFalse(result['native_runtime_measured_by_this_ui_class'])

    def test_wrong_test_count_fails(self):
        self.assertEqual(self.check('OK (1 test)\n', 0)['status'], 'failed')

    def test_timeout_overrides_previously_printed_success(self):
        self.assertEqual(self.check('OK (7 tests)\n', 124)['status'], 'failed')

    def test_missing_output_fails(self):
        self.assertEqual(self.check(None, 1)['status'], 'failed')


class MockedCleanupTest(unittest.TestCase):
    def cleanup_run(self, *, sudo_code=0, changed_acl=False, backup=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        binary = root / 'mock-bin'
        binary.mkdir()
        results = root / 'results'
        results.mkdir()
        if backup:
            (results / 'kvm-original.acl').write_text('synthetic original ACL\n')
        mocks = {'sudo': f'#!/bin/sh\nprintf "sudo mock\\n" >> "$FN74_RESULTS/mock-calls"\nexit {sudo_code}\n',
                 'getfacl': '#!/bin/sh\nprintf "' + ('different ACL' if changed_acl else 'synthetic original ACL') + '\\n"\n',
                 'adb': '#!/bin/sh\necho unexpected-adb-call >> "$FN74_RESULTS/mock-calls"\nexit 91\n'}
        for name, text in mocks.items():
            path = binary / name
            path.write_text(text)
            path.chmod(0o700)
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                   FN74_RESULTS=str(results), ANDROID_HOME=str(root / 'unused-sdk'))
        # Prove these subprocesses cannot resolve real permission or device tools.
        for name in mocks:
            self.assertEqual(shutil.which(name, path=env['PATH']), str(binary / name))
        runner = Path(__file__).resolve().parents[1] / 'run_source_runtime_sessions.sh'
        command = subprocess.run(['bash', '-c', 'source "$1" cleanup || exit "$?"', 'bash', str(runner)], env=env, capture_output=True, text=True, timeout=10)
        calls = (results / 'mock-calls').read_text() if (results / 'mock-calls').exists() else ''
        self.assertNotIn('unexpected-adb-call', calls)  # No owned PID, therefore no device command.
        return command, results, calls

    def test_success_requires_exact_mocked_restore(self):
        command, results, _ = self.cleanup_run()
        self.assertEqual(command.returncode, 0)
        self.assertTrue((results / 'kvm-restore.txt').exists())

    def test_failed_mocked_permission_command_cannot_claim_success(self):
        command, results, _ = self.cleanup_run(sudo_code=7)
        self.assertNotEqual(command.returncode, 0)
        self.assertFalse((results / 'kvm-restore.txt').exists())

    def test_different_mocked_acl_cannot_claim_success(self):
        command, results, _ = self.cleanup_run(changed_acl=True)
        self.assertNotEqual(command.returncode, 0)
        self.assertFalse((results / 'kvm-restore.txt').exists())

    def test_no_backup_means_no_permission_or_device_tool(self):
        command, _, calls = self.cleanup_run(backup=False)
        self.assertEqual(command.returncode, 0)
        self.assertEqual(calls, '')


if __name__ == '__main__':
    unittest.main()
