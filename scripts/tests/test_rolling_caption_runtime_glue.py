"""Offline source guards only: parse text and bash -n; never run Android or ACL tools."""
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/rolling-caption-diagnosis-runtime.yml'
RUNNER = ROOT / 'scripts/run_rolling_caption_runtime_session.sh'


def step(workflow, name):
    chunks = workflow.split('      - name: ')
    matches = [chunk.split('\n', 1)[1] for chunk in chunks[1:] if chunk.split('\n', 1)[0] == name]
    if len(matches) != 1:
        raise ValueError('Missing or duplicate step: ' + name)
    return matches[0]


def require_single_run_gates(workflow):
    job = re.findall(r'^    if:\s*(.+)$', workflow, re.MULTILINE)
    grant = step(workflow, 'Approved current-UID one-run KVM access')
    grant_gate = re.findall(r'^        if:\s*(.+)$', grant, re.MULTILINE)
    if job != ['${{ github.run_number == 2 && github.run_attempt == 1 }}'] or grant_gate != job:
        raise ValueError('Both runtime and KVM gates must name the same approved run and first attempt')


class RollingCaptionRuntimeGlueTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.runner = RUNNER.read_text()

    def test_bash_syntax_only_without_executing_script(self):
        subprocess.run(['bash', '-n', str(RUNNER)], check=True, capture_output=True, text=True)

    def test_job_and_current_uid_grant_are_exact_one_run(self):
        require_single_run_gates(self.workflow)
        self.assertIn('  workflow_dispatch:', self.workflow)
        self.assertIn("branches: ['experiment/FN-77-local-agreement']", self.workflow)
        self.assertNotRegex(self.workflow, re.compile(r'^  (pull_request|schedule):', re.MULTILINE))
        for mutated in (
            self.workflow.replace('    if: ${{ github.run_number == 2 && github.run_attempt == 1 }}', '    if: ${{ true }}', 1),
            self.workflow.replace('        if: ${{ github.run_number == 2 && github.run_attempt == 1 }}', '        if: ${{ true }}', 1),
            self.workflow.replace('        if: ${{ github.run_number == 2 && github.run_attempt == 1 }}\n', '', 1),
        ):
            with self.assertRaises(ValueError):
                require_single_run_gates(mutated)

    def test_only_one_exact_class_method_and_two_explicit_opt_ins(self):
        self.assertIn('TEST_SELECTION=com.framenest.feature.listen_translate.'
                      'RollingCaptionTranslationDiagnosisTest#compareRollingRequestsWithThreeSecondBaseline', self.runner)
        self.assertEqual(1, self.runner.count('shell am instrument -w -r'))
        self.assertIn('-e class "$TEST_SELECTION"', self.runner)
        self.assertIn('-e rollingCaptionDiagnosis true -e captionDiagnosisNetwork true', self.runner)
        self.assertIn('-e expectedAppApkSha256 "$APP_SHA" -e expectedTestApkSha256 "$TEST_SHA"', self.runner)
        for old in ('FixedCaptionWindowComparisonTest', 'ListenTranslateOverlayTest',
                    'ListenTranslateDiagnosticsControlsTest', 'CaptionDiagnosisTest#', 'for name in phone'):
            self.assertNotIn(old, self.runner)

    def test_retained_pair_is_verified_and_installed_once_without_rebuilding(self):
        self.assertIn('prepare_rolling_caption_runtime_pair.py --download', self.workflow)
        self.assertIn('--lock docs/experiments/FN-77-local-agreement/runtime-input-lock.json', self.workflow)
        self.assertIn('test -s docs/experiments/FN-77-local-agreement/runtime-input-lock.json', self.workflow)
        self.assertIn('sha256sum -c APK-SHA256SUMS', self.runner)
        self.assertEqual(1, self.runner.count('install "$INPUT/app.apk"'))
        self.assertEqual(1, self.runner.count('install "$INPUT/test.apk"'))
        run = self.runner.split('\nrun)\n', 1)[1]
        self.assertLess(run.index('sha256sum -c APK-SHA256SUMS'), run.index('  start_emulator'))
        for forbidden in ('./gradlew', 'assembleDebug', 'prepare-caption-diagnosis.py', 'prepare_source_asr.py'):
            self.assertNotIn(forbidden, self.workflow + self.runner)
        # Future lock is referenced, never fabricated in this disabled glue.
        self.assertNotRegex(self.workflow, r'\b[0-9a-f]{64}\b')

    def test_api35_unique_avd_result_paths_and_no_software_acceleration_fallback(self):
        self.assertIn('FN77_RESULTS:', self.workflow)
        self.assertIn('RESULTS="${FN77_RESULTS:', self.runner)
        self.assertIn('ANDROID_AVD_HOME="$RUNNER_TEMP/fn77-rolling-caption-avd"', self.runner)
        self.assertIn('test ! -e "$ANDROID_AVD_HOME"', self.runner)
        self.assertIn('SERIAL=emulator-5556', self.runner)
        self.assertIn("system-images;android-35;google_apis;x86_64", self.workflow)
        self.assertIn("system-images;android-35;google_apis;x86_64", self.runner)
        self.assertIn('-accel on', self.runner)
        self.assertNotIn('-accel off', self.runner)
        self.assertNotIn('FN76_RESULTS', self.workflow + self.runner)
        self.assertIn('REPORT_NAME=rolling-caption-translation-diagnosis.json', self.runner)
        self.assertIn('cat "files/$REPORT_NAME"', self.runner)

    def test_watchdogs_bound_instrumentation_and_session_cleanup_to_35_minutes(self):
        instrument = re.search(r'timeout --signal=TERM --kill-after=(\d+)s (\d+) adb .* shell am instrument', self.runner)
        session = re.search(r'timeout --signal=TERM --kill-after=(\d+)s (\d+) bash "\$0" session', self.runner)
        self.assertIsNotNone(instrument)
        self.assertIsNotNone(session)
        self.assertEqual(1995, sum(map(int, instrument.groups())))
        self.assertEqual(2100, sum(map(int, session.groups())))
        cleanup = step(self.workflow, 'Stop the owned emulator and restore the exact original KVM ACL')
        self.assertIn('        if: always()', cleanup)
        self.assertIn('        timeout-minutes: 3', cleanup)
        self.assertIn('trap \'status=$?; trap - EXIT; cleanup || status=1; exit "$status"\' EXIT', self.runner)
        self.assertIn('(set -o noclobber;', self.runner)
        self.assertIn('instrumentation-attempted.txt', self.runner)
        self.assertIn('runtime-attempted.txt', self.runner)

    def test_acl_scope_is_current_uid_and_original_acl_is_saved_and_restored(self):
        grant = step(self.workflow, 'Approved current-UID one-run KVM access')
        self.assertIn('test ! -e "$FN77_RESULTS/kvm-original.acl"', grant)
        self.assertIn('sudo -n setfacl -m "u:$(id -u):rw" /dev/kvm', grant)
        self.assertLess(grant.index('getfacl -p /dev/kvm > "$FN77_RESULTS/kvm-original.acl"'),
                        grant.index('sudo -n setfacl -m'))
        self.assertIn('sudo -n setfacl --restore="$ACL"', self.runner)
        self.assertIn('diff -u "$ACL" "$RESULTS/kvm-restored.acl"', self.runner)
        self.assertIn('stop_emulator || status=1', self.runner)
        self.assertNotRegex(self.workflow + self.runner, r'\b(?:chmod\s+666|usermod|chown)\b')
        self.assertIn('cancel-in-progress: false', self.workflow)

    def test_actions_are_pinned_read_only_and_failures_are_retained(self):
        self.assertIn('  contents: read', self.workflow)
        self.assertIn('  actions: read', self.workflow)
        self.assertNotRegex(self.workflow, re.compile(r'^\s+(?:contents|actions): write$', re.MULTILINE))
        self.assertIn('persist-credentials: false', self.workflow)
        self.assertIn('accept-android-sdk-licenses: false', self.workflow)
        actions = re.findall(r'uses: (\S+)', self.workflow)
        self.assertTrue(actions)
        self.assertTrue(all(re.fullmatch(r'[^@]+@[0-9a-f]{40}', action) for action in actions))
        evidence = step(self.workflow, 'Retain the exact pair and all collection or failure evidence')
        self.assertIn('        if: always()', evidence)
        self.assertIn('path: rolling-caption-runtime-results/', evidence)

    def test_owner_result_checker_receives_exact_retained_hashes(self):
        self.assertIn('python3 scripts/check_rolling_caption_runtime_results.py', self.runner)
        self.assertIn('--report "$RESULTS/diagnosis/$REPORT_NAME"', self.runner)
        self.assertIn('--expected-app-sha256 "$app_sha" --expected-test-sha256 "$test_sha"', self.runner)
        self.assertIn("'compareRollingRequestsWithThreeSecondBaseline', 0)", self.runner)
        self.assertIn('instrumentation-summary.json" || return $?', self.runner)


if __name__ == '__main__':
    unittest.main()
