"""Offline software-route checks. All device/process launches are replaced by mocks."""
import copy
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
import check_source_software_probe as target
import test_source_runtime_results as fixtures


class SoftwareProbeTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SourceRuntimeResultTest('runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.report = self.fixture.report

    def test_first_complete_session_has_explicit_software_cost_boundary(self):
        result = target.record(self.root, 'session', ['1'])
        self.assertEqual(result['status'], 'passed')
        self.assertEqual(result['acceleration'], 'off')
        self.assertFalse(result['hardware_performance_accepted'])
        self.assertFalse(result['historical_stock_timing_comparison_accepted'])
        self.assertIn('never product speed', result['timing_interpretation'])

    def test_session_identity_and_completeness_fail_closed(self):
        for field, bad_value in [('runtime_actual_native', '1.12.32'),
                                 ('runtime_build_identity', 'stock'), ('source_aar_sha256', 'f' * 64),
                                 ('apk_sha256', 'f' * 64), ('test_apk_sha256', 'f' * 64),
                                 ('fixture_manifest_sha256', 'f' * 64), ('hy_mt_native_build_profile', True),
                                 ('runs', []), ('status', 'running'), ('raw_stream_counts_balanced', False),
                                 ('api', 34), ('abi', 'arm64-v8a')]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.report)
                changed[field] = bad_value
                self.fixture.write(1, changed)
                with self.assertRaises(ValueError):
                    target.validate_session(self.root, 1)
        self.fixture.write(1, self.report)
        (self.root / 'session-1/shell-status.txt').write_text('failed exit=124\n')
        with self.assertRaises(ValueError):
            target.validate_session(self.root, 1)

    def test_complete_probe_requires_separate_seven_test_ui_result(self):
        (self.root / 'ui').mkdir()
        (self.root / 'ui/shell-status.txt').write_text('passed\n')
        for count in (6, 7):
            (self.root / 'ui/result.json').write_text(json.dumps({'status': 'passed', 'tests_passed': count}))
            if count == 6:
                with self.assertRaises(ValueError):
                    target.record(self.root, 'finish', [])
            else:
                result = target.record(self.root, 'finish', [])
                self.assertEqual(result['completed_source_sessions'], 2)
                self.assertEqual(result['ui_tests_passed'], 7)

    def mocked_run(self, fail_phase, invalid_first=False):
        binary = self.root / 'mock-bin'
        binary.mkdir()
        (self.root / 'template-report.json').write_text(json.dumps(self.report))
        # This replaces every timeout invocation, so no adb, emulator, or permission
        # operation can execute. Only host shell orchestration and JSON checks run.
        timeout = binary / 'timeout'
        timeout.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
root = pathlib.Path(os.environ['FN74_RESULTS'])
a = sys.argv[1:]
i = a.index('bash')
phase = a[i + 2]
if phase == 'session':
    phase += '-' + a[i + 3]
with (root / 'mock-phases.txt').open('a') as out:
    out.write(phase + '\\n')
if phase == os.environ['MOCK_FAIL_PHASE']:
    sys.exit(124)
if phase.startswith('session-'):
    report = json.loads((root / 'template-report.json').read_text())
    if os.environ.get('MOCK_INVALID_FIRST') == 'true' and phase == 'session-1':
        report['apk_sha256'] = 'f' * 64
    (root / phase / 'result.json').write_text(json.dumps(report))
if phase not in ('boot', 'session-1', 'session-2'):
    sys.exit(93)
''')
        timeout.chmod(0o700)
        for name in ('adb', 'emulator', 'avdmanager', 'sudo', 'setfacl', 'getfacl', 'modprobe', 'usermod'):
            path = binary / name
            path.write_text('#!/bin/sh\necho "$0" >> "$FN74_RESULTS/unexpected-commands.txt"\nexit 94\n')
            path.chmod(0o700)
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                   FN74_RESULTS=str(self.root), GITHUB_ACTIONS='true',
                   ANDROID_HOME=str(self.root / 'unused-sdk'), MOCK_FAIL_PHASE=fail_phase,
                   MOCK_INVALID_FIRST=str(invalid_first).lower())
        self.assertEqual(shutil.which('timeout', path=env['PATH']), str(timeout))
        command = subprocess.run(['bash', str(SCRIPTS / 'run_source_software_sessions.sh'), 'run'],
                                 cwd=ROOT, env=env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(command.returncode, 0)
        self.assertFalse((self.root / 'unexpected-commands.txt').exists())
        result = json.loads((self.root / 'software-probe.json').read_text())
        self.assertFalse(result['phases_after_failure_attempted'])
        return (self.root / 'mock-phases.txt').read_text().splitlines(), result

    def test_boot_failure_prevents_every_instrumentation_session(self):
        phases, result = self.mocked_run('boot')
        self.assertEqual(phases, ['boot'])
        self.assertEqual(result['failed_phase'], 'boot')

    def test_first_timeout_prevents_second_session_and_ui(self):
        phases, result = self.mocked_run('session-1')
        self.assertEqual(phases, ['boot', 'session-1'])
        self.assertEqual(result['failed_phase'], 'session-1')
        self.assertEqual(result['exit_code'], 124)

    def test_first_report_identity_failure_prevents_second_session_and_ui(self):
        phases, result = self.mocked_run('unused', invalid_first=True)
        self.assertEqual(phases, ['boot', 'session-1'])
        self.assertEqual(result['failed_phase'], 'session-1')

    def test_second_failure_prevents_ui_after_verified_first_session(self):
        phases, result = self.mocked_run('session-2')
        self.assertEqual(phases, ['boot', 'session-1', 'session-2'])
        self.assertEqual(result['failed_phase'], 'session-2')
        self.assertEqual(json.loads((self.root / 'session-1/software-verification.json').read_text())['status'], 'passed')


class SoftwareRouteStaticTest(unittest.TestCase):
    def test_only_exact_branch_setup_retry_and_first_attempt_can_run(self):
        workflow = (ROOT / '.github/workflows/asr-source-software.yml').read_text()
        self.assertIn("branches: ['experiment/FN-74-asr-source-acceptance']", workflow)
        self.assertIn('if: github.run_number == 2 && github.run_attempt == 1', workflow)
        self.assertNotIn('workflow_dispatch', workflow)
        self.assertNotIn('pull_request', workflow)
        trigger = workflow.split('permissions:')[0]
        paths = re.findall(r'^      - (.+)$', trigger, re.M)
        self.assertEqual(paths, ['.github/workflows/asr-source-software.yml',
            'scripts/run_source_software_sessions.sh', 'scripts/check_source_software_probe.py',
            'scripts/tests/test_source_software_probe.py', 'docs/experiments/FN-74-asr-source-app/SOFTWARE-PROBE.md'])
        self.assertIn('timeout-minutes: 90', workflow)
        step_limits = [int(x) for x in re.findall(r'^        timeout-minutes: (\d+)$', workflow, re.M)]
        self.assertEqual(len(step_limits), len(re.findall(r'^      - name:', workflow, re.M)))
        self.assertLessEqual(sum(step_limits), 88)
        self.assertIn('timeout --signal=TERM --kill-after=60s 3660 bash', workflow)
        self.assertIn('accept-android-sdk-licenses: false', workflow)
        self.assertIn('prepare_source_runtime_pair.py --download', workflow)

    def test_route_has_no_permission_commands_or_hardware_acceleration(self):
        workflow = (ROOT / '.github/workflows/asr-source-software.yml').read_text()
        runner = (SCRIPTS / 'run_source_software_sessions.sh').read_text()
        for forbidden in ('/dev/kvm', 'setfacl', 'getfacl', 'usermod', 'modprobe',
                          'chmod', 'chown', '-accel on', '-enable-kvm', '-accel-check',
                          'run_source_runtime_sessions.sh', 'apt-get install acl'):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, workflow + runner)
        self.assertNotIn('sudo', runner)
        self.assertEqual(re.findall(r'-accel\s+(\w+)', runner), ['off'])
        self.assertIn('-no-snapshot -wipe-data', runner)
        self.assertIn('--kill-after=5s 900', runner)
        self.assertIn('--kill-after=15s 1200', runner)
        self.assertIn('--kill-after=60s 1500', runner)
        self.assertIn('-e expectedAppApkSha256 "$APP_SHA" -e expectedTestApkSha256 "$TEST_SHA"', runner)
        self.assertIn('-e sourceRuntimeComparison true -e expectedRuntime 1.13.8', runner)
        self.assertIn('sha256sum -c APK-SHA256SUMS', runner)
        subprocess.run(['bash', '-n', str(SCRIPTS / 'run_source_software_sessions.sh')], check=True)

    def test_emulator_version_runs_only_after_runtime_libraries_are_installed(self):
        for route in ('software', 'runtime'):
            with self.subTest(route=route):
                workflow = (ROOT / f'.github/workflows/asr-source-{route}.yml').read_text()
                command = '"$ANDROID_HOME/emulator/emulator" -version'
                self.assertEqual(workflow.count(command), 1)
                libraries = workflow.split('      - name: Install official emulator runtime libraries\n', 1)[1].split('      - name:', 1)[0]
                install = next(line for line in libraries.splitlines() if 'apt-get install' in line)
                self.assertIn('libpulse0', install)
                self.assertIn(command, libraries)
                self.assertLess(libraries.index(install), libraries.index(command))

    def test_accelerated_route_stays_closed(self):
        workflow = (ROOT / '.github/workflows/asr-source-runtime.yml').read_text()
        self.assertEqual(workflow.count('if: ${{ false }}'), 2)


if __name__ == '__main__':
    unittest.main()
