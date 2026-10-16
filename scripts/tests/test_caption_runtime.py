"""Offline synthetic archives/logs only. Never launch Android, network, or ACL tools."""
import copy
from functools import lru_cache
import io
import json
from pathlib import Path
import re
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_caption_runtime_pair as pair
import check_caption_runtime_results as results

ROOT = Path(__file__).resolve().parents[2]


def zipped(members):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in members.items():
            archive.writestr(name, data)
    return output.getvalue()


def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)


@lru_cache(maxsize=8)
def synthetic_png(width, height, compressed=None):
    if compressed is None:
        compressed = zlib.compress((b'\0' * (1 + width * 4)) * height, level=1)
    return (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + png_chunk(b'IDAT', compressed) + png_chunk(b'IEND', b''))


def gate(value):
    value = value.strip()
    if value.startswith('${{') and value.endswith('}}'):
        value = value[3:-2].strip()
    if value == 'false':
        return ('closed',)
    match = re.fullmatch(r'github\.run_number == ([1-9][0-9]*) && github\.run_attempt == 1', value)
    if match:
        return ('one-run', int(match.group(1)))
    raise ValueError('Only closed or exact one-run/attempt-one gates are accepted')


class RetainedCaptionPairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.destination = self.root / 'staged'
        self.archive = self.root / 'retained.zip'
        prefix = 'assets/caption-diagnosis/'
        self.test_inputs = {prefix + name: (pair.ASSETS / name).read_bytes() for name in ('manifest.json', 'source-profile.json', 'reference.json')}
        self.prepared = {'all_inputs_verified': True, 'manifest_sha256': pair.file_digest(pair.ASSETS / 'manifest.json'),
                         'source_profile_sha256': pair.file_digest(pair.ASSETS / 'source-profile.json'),
                         'reference_units': 4, 'duration_ms': 47720, 'semantic_acceptance_passed': None}
        self.record = {'schema_version': 1, 'runtime_executed': False, 'source_commit': 'a' * 40, 'run_id': '10',
                       'profile': {'observed_constants': {'SHERPA_ASR_SOURCE': True, 'HY_MT_NATIVE': False,
                           'SHERPA_RUNTIME_VERSION': '1.13.8', 'SHERPA_RUNTIME_IDENTITY': pair.PROFILE['runtime_identity'],
                           'SHERPA_AAR_SHA256': pair.PROFILE['aar_sha256']}}}
        self.lock = {'schema_version': 1, 'status': 'pinned-reviewed', 'repository': 'csic21/FrameNest',
                     'run_id': 10, 'run_attempt': 1, 'artifact_id': 20, 'commit': 'a' * 40,
                     'artifact_name': 'caption-diagnosis-apks-' + 'a' * 40 + '-1', 'input_lock_is_execution_authorization': False}
        self.seal()

    def seal(self, mutate=None, extra=None):
        self.test_inputs['assets/caption-diagnosis/generated/prepared.json'] = json.dumps(self.prepared).encode()
        members = {'app.apk': b'synthetic-app', 'test.apk': zipped(self.test_inputs)}
        self.record['apks'] = [{'path': path, 'artifact_name': name, 'bytes': len(members[name]), 'sha256': pair.digest(members[name])}
                               for name, path in pair.APK_PATHS.items()]
        if mutate:
            mutate(self.record)
        members['apk-pair.json'] = json.dumps(self.record).encode()
        if extra:
            members.update(extra)
        self.archive.write_bytes(zipped(members))
        self.lock.update(zip_size_bytes=self.archive.stat().st_size, zip_sha256=pair.file_digest(self.archive),
                         pair_manifest_sha256=pair.digest(members['apk-pair.json']),
                         app_apk_sha256=pair.digest(members['app.apk']), test_apk_sha256=pair.digest(members['test.apk']))

    def stage(self):
        return pair.stage_pair(self.archive, self.destination, self.lock)

    def test_valid_pair_stages_exact_bytes_without_execution(self):
        report = self.stage()
        self.assertEqual(report['status'], 'retained-pair-verified')
        self.assertFalse(report['runtime_tested'])
        self.assertEqual((self.destination / 'app.apk').read_bytes(), b'synthetic-app')
        self.assertEqual((self.destination / 'test.apk').read_bytes(), zipped(self.test_inputs))
        self.assertTrue((self.destination / 'APK-SHA256SUMS').is_file())

    def test_pending_lock_stops_before_any_network_or_staging(self):
        pending = json.loads(pair.DEFAULT_LOCK.read_text())
        pending['status'] = 'pending-successful-final-ordinary-ci'
        with patch.object(pair.urllib.request, 'build_opener') as network:
            with self.assertRaisesRegex(ValueError, 'not finalized'):
                pair.download(self.archive, pending)
            network.assert_not_called()
        with self.assertRaises(ValueError):
            pair.stage_pair(self.archive, self.destination, pending)
        self.assertFalse(self.destination.exists())

    def test_missing_hash_wrong_attempt_and_authorization_claim_fail(self):
        for change in ({'app_apk_sha256': None}, {'run_attempt': 2}, {'run_attempt': True},
                       {'input_lock_is_execution_authorization': True}, {'repository': 'other/repo'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                pair.validate_lock(dict(self.lock, **change))

    def test_zip_corruption_and_existing_destination_fail(self):
        self.archive.write_bytes(self.archive.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'ZIP size/hash'):
            self.stage()
        self.seal()
        self.destination.mkdir()
        with self.assertRaisesRegex(ValueError, 'existing staged'):
            self.stage()

    def test_wrong_commit_profile_members_or_hash_fail_before_staging(self):
        original = copy.deepcopy(self.record)
        for mutate in (lambda p: p.update(source_commit='b' * 40),
                       lambda p: p['profile']['observed_constants'].update(HY_MT_NATIVE=True),
                       lambda p: p['profile']['observed_constants'].update(SHERPA_ASR_SOURCE=1),
                       lambda p: p['apks'][0].update(sha256='f' * 64),
                       lambda p: p['apks'].append(p['apks'][0])):
            self.record = copy.deepcopy(original)
            self.seal(mutate)
            with self.assertRaises(ValueError):
                self.stage()
            self.assertFalse(self.destination.exists())

    def test_unlisted_member_or_changed_packaged_metadata_fail(self):
        self.seal(extra={'extra.txt': b'extra'})
        with self.assertRaisesRegex(ValueError, 'Unexpected artifact members'):
            self.stage()
        self.test_inputs['assets/caption-diagnosis/reference.json'] = b'{}'
        self.seal()
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            self.stage()

    def test_preparation_cannot_claim_semantic_acceptance(self):
        self.prepared['semantic_acceptance_passed'] = True
        self.seal()
        with self.assertRaisesRegex(ValueError, 'preparation mismatch'):
            self.stage()


class RuntimeResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('phone', 'tablet', 'diagnosis', 'input'):
            (self.root / name).mkdir()
        for name in ('phone', 'tablet', 'diagnosis'):
            (self.root / name / 'exit-code.txt').write_text('0\n')
        self.ui_events = []
        for cls, count in results.UI_COUNTS.items():
            source = (ROOT / 'app/src/androidTest/java/com/framenest/feature/listen_translate' / (cls + '.kt')).read_text()
            methods = re.findall(r'@Test\s+fun (\w+)\(', source)
            for method in methods:
                widths = (360, 840) if cls != 'CaptionDiagnosisFixtureTest' else (None,)
                for width in widths:
                    suffix = '' if width is None else f'[width {width}dp]'
                    code = -4 if cls == 'ListenTranslateDiagnosticsControlsTest' and method in results.HY_ONLY else 0
                    self.ui_events.append((results.PACKAGE + cls, method + suffix, code))
            self.assertEqual(sum(c == results.PACKAGE + cls for c, _, _ in self.ui_events), count)
        for name in ('phone', 'tablet'):
            self.write_events(name, self.ui_events)
            size, density = ('1600x2560', '240') if name == 'tablet' else ('1080x1920', '480')
            (self.root / name / 'display-size.txt').write_text('Override size: ' + size)
            (self.root / name / 'display-density.txt').write_text('Override density: ' + density)
            width, height = map(int, size.split('x'))
            (self.root / name / 'display.txt').write_text(
                f'mOverrideDisplayInfo=DisplayInfo{{"Built-in Screen", displayId 0, real {width} x {height}, rotation 0, state ON}}\n')
            for panel_width in (360, 840):
                (self.root / name / f'controls-{panel_width}.png').write_bytes(synthetic_png(width, height))
        self.write_events('diagnosis', [(results.PACKAGE + 'CaptionDiagnosisTest', 'compareReferenceRawSpanAndProductionWindows', 0)])
        manifest_bytes = (pair.ASSETS / 'manifest.json').read_bytes()
        manifest = json.loads(manifest_bytes)
        self.report = {'schema_version': 1, 'status': 'collected', 'collection_complete': True,
                       'app_apk_sha256': 'a' * 64, 'test_apk_sha256': 'b' * 64,
                       'source_aar_sha256': pair.PROFILE['aar_sha256'], 'runtime_identity': pair.PROFILE['runtime_identity'],
                       'native_version': '1.13.8', 'api': 35, 'abi': 'x86_64',
                       'manifest_sha256': pair.digest(manifest_bytes), 'manifest': manifest,
                       'semantic_acceptance_passed': None, 'accuracy_accepted': False, 'hardware_performance_accepted': False,
                       'reference_is_verbatim_gold': False, 'reference_independently_listened': False,
                       'verified_source_profile': {'profile': pair.PROFILE},
                       'reference_to_mt': [], 'raw_span_asr_to_mt': [], 'production_3s_to_mt': []}
        for unit in manifest['units']:
            row = {'reference_unit_id': unit['id'], 'span_start_ms': unit['start_ms'], 'span_end_ms': unit['end_ms'],
                   'semantic_acceptance_passed': None, 'input_text': unit['text'], 'mt_status': 'returned',
                   'target_text': 'synthetic translation'}
            self.report['reference_to_mt'].append(row)
            self.report['raw_span_asr_to_mt'].append(dict(row, raw_tokens=['synthetic'], token_start_seconds_relative_to_span=[0.0]))
        for i in range(16):
            self.report['production_3s_to_mt'].append({'window_id': f'p{i}', 'window_start_ms': i * 3000,
                'window_end_ms': min((i + 1) * 3000, 47720), 'semantic_acceptance_passed': None,
                'text_src': 'synthetic source', 'text_tgt': 'synthetic target', 'translation_retryable_failure': False})
        (self.root / 'input/pair-verification.json').write_text(json.dumps({'status': 'retained-pair-verified',
            'lock': {'app_apk_sha256': 'a' * 64, 'test_apk_sha256': 'b' * 64}}))
        self.save_report()

    def write_events(self, name, events):
        text = ''.join(f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS_CODE: {code}\n' for cls, test, code in events)
        (self.root / name / 'instrumentation.txt').write_text(text + 'INSTRUMENTATION_CODE: -1\n')

    def save_report(self):
        (self.root / 'diagnosis/caption-diagnosis.json').write_text(json.dumps(self.report))

    def test_complete_synthetic_evidence_does_not_claim_semantic_acceptance(self):
        self.assertTrue(results.record(self.root))
        summary = json.loads((self.root / 'validation-summary.json').read_text())
        self.assertEqual(summary['checks']['phone']['tests_passed'], 35)
        self.assertEqual(summary['checks']['tablet']['tests_skipped'], 4)
        self.assertIsNone(summary['semantic_acceptance_passed'])

    def test_default_skip_and_duplicate_diagnosis_cannot_pass(self):
        event = (results.PACKAGE + 'CaptionDiagnosisTest', 'compareReferenceRawSpanAndProductionWindows', -4)
        self.write_events('diagnosis', [event])
        with self.assertRaisesRegex(ValueError, 'skipped'):
            results.check_session(self.root, 'diagnosis')
        self.write_events('diagnosis', [event, event])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            results.check_session(self.root, 'diagnosis')

    def test_missing_ui_test_wrong_viewport_or_extra_skip_fails(self):
        self.write_events('phone', self.ui_events[:-1])
        with self.assertRaises(ValueError):
            results.check_session(self.root, 'phone')
        modified = self.ui_events.copy()
        cls, test, _ = modified[0]
        modified[0] = (cls, test, -4)
        self.write_events('phone', modified)
        with self.assertRaises(ValueError):
            results.check_session(self.root, 'phone')
        self.write_events('phone', self.ui_events)
        (self.root / 'phone/display-density.txt').write_text('Physical density: 480\nOverride density: 240')
        with self.assertRaisesRegex(ValueError, 'Viewport'):
            results.check_session(self.root, 'phone')

    def test_incomplete_rows_hash_profile_or_acceptance_claim_fails(self):
        original = copy.deepcopy(self.report)
        for mutate in (lambda r: r['production_3s_to_mt'].pop(), lambda r: r.update(app_apk_sha256='c' * 64),
                       lambda r: r.update(semantic_acceptance_passed=True), lambda r: r.update(native_version='1.12.1')):
            self.report = copy.deepcopy(original)
            mutate(self.report)
            self.save_report()
            with self.assertRaises(ValueError):
                results.check_diagnosis(self.root)

    def test_returned_and_empty_target_require_matching_text_in_both_paths(self):
        original = copy.deepcopy(self.report)
        for key in ('reference_to_mt', 'raw_span_asr_to_mt'):
            for status in ('returned', 'empty_target'):
                for fields in ({}, {'target_text': None}, {'target_text': 42}):
                    self.report = copy.deepcopy(original)
                    row = self.report[key][0]
                    row['mt_status'] = status
                    del row['target_text']
                    row.update(fields)
                    self.save_report()
                    with self.subTest(key=key, status=status, fields=fields), self.assertRaisesRegex(ValueError, 'Missing real MT target text'):
                        results.check_diagnosis(self.root)
            for status, text in (('returned', ''), ('returned', ' \t\n'), ('empty_target', 'actual translation')):
                self.report = copy.deepcopy(original)
                self.report[key][0].update(mt_status=status, target_text=text)
                self.save_report()
                with self.subTest(key=key, status=status, text=text), self.assertRaisesRegex(ValueError, 'MT target text/status mismatch'):
                    results.check_diagnosis(self.root)

    def test_real_empty_targets_are_retained_as_observations_in_both_paths(self):
        for key, text in (('reference_to_mt', ''), ('raw_span_asr_to_mt', ' \t\n')):
            self.report[key][0].update(mt_status='empty_target', target_text=text)
        self.save_report()
        result = results.check_diagnosis(self.root)
        for key in ('reference_to_mt', 'raw_span_asr_to_mt'):
            self.assertEqual(result['mt_observations'][key + ':empty_target'], 1)
        self.assertIsNone(result['semantic_acceptance_passed'])

    def test_real_mt_failure_is_retained_without_pretending_semantic_pass(self):
        self.report['reference_to_mt'][0].update(mt_status='failed', mt_failure_class='java.lang.IllegalStateException')
        del self.report['reference_to_mt'][0]['target_text']
        self.save_report()
        result = results.check_diagnosis(self.root)
        self.assertEqual(result['mt_observations']['reference_to_mt:failed'], 1)
        self.assertIsNone(result['semantic_acceptance_passed'])

    def test_failed_mt_requires_a_nonblank_failure_class(self):
        for key in ('reference_to_mt', 'raw_span_asr_to_mt'):
            row = self.report[key][0]
            row['mt_status'] = 'failed'
            for fields in ({}, {'mt_failure_class': None}, {'mt_failure_class': 42}, {'mt_failure_class': ' \t'}):
                row.pop('mt_failure_class', None)
                row.update(fields)
                self.save_report()
                with self.subTest(key=key, fields=fields), self.assertRaisesRegex(ValueError, 'Missing real MT failure class'):
                    results.check_diagnosis(self.root)
            row.update(mt_failure_class='java.lang.IllegalStateException')
        self.save_report()
        self.assertEqual(results.check_diagnosis(self.root)['status'], 'collected')

    def test_skipped_mt_requires_empty_source_and_target(self):
        row = self.report['raw_span_asr_to_mt'][0]
        row.update(mt_status='skipped_empty_source', input_text='', target_text='')
        self.save_report()
        self.assertEqual(results.check_diagnosis(self.root)['mt_observations']['raw_span_asr_to_mt:skipped_empty_source'], 1)
        for source, target in (('nonempty', ''), (None, ''), ('', 'not empty'), ('', None), ('', 42)):
            row.update(input_text=source, target_text=target)
            self.save_report()
            with self.subTest(source=source, target=target), self.assertRaisesRegex(ValueError, 'Skipped MT requires empty'):
                results.check_diagnosis(self.root)

    def test_missing_empty_corrupt_or_wrong_size_screenshot_fails_ui(self):
        for name, width, height in (('phone', 1080, 1920), ('tablet', 1600, 2560)):
            for panel_width in (360, 840):
                path = self.root / name / f'controls-{panel_width}.png'
                original = path.read_bytes()
                for content in (None, b'', b'not a PNG', original[:-1], original[:45] + b'X' + original[46:],
                                synthetic_png(360, 840), synthetic_png(width, height, zlib.compress(b'short'))):
                    if content is None:
                        path.unlink()
                    else:
                        path.write_bytes(content)
                    with self.subTest(name=name, panel_width=panel_width, length=None if content is None else len(content)), self.assertRaises(ValueError):
                        results.check_session(self.root, name)
                    path.write_bytes(original)

    def test_screenshot_orientation_uses_recorded_display_rotation(self):
        directory = self.root / 'phone'
        for panel_width in (360, 840):
            (directory / f'controls-{panel_width}.png').write_bytes(synthetic_png(1920, 1080))
        with self.assertRaisesRegex(ValueError, 'PNG dimensions'):
            results.check_session(self.root, 'phone')
        (directory / 'display.txt').write_text(
            'mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1080 x 1920, rotation 0, state ON}\n'
            'mOverrideDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1920 x 1080, rotation 1, state ON}\n')
        report = results.check_session(self.root, 'phone')
        self.assertEqual([(p['width'], p['height']) for p in report['screenshots']], [(1920, 1080)] * 2)
        (directory / 'display.txt').write_text('unavailable')
        with self.assertRaisesRegex(ValueError, 'Missing actual display'):
            results.check_session(self.root, 'phone')

    def test_failure_summary_still_contains_other_checks(self):
        (self.root / 'diagnosis/caption-diagnosis.json').write_text('{partial')
        self.assertFalse(results.record(self.root))
        summary = json.loads((self.root / 'validation-summary.json').read_text())
        self.assertEqual(summary['checks']['phone']['status'], 'passed')
        self.assertEqual(summary['checks']['three_path_evidence']['status'], 'failed')


class DisabledWorkflowTest(unittest.TestCase):
    def test_closed_or_exact_new_one_run_gates_and_no_rebuild(self):
        workflow = (ROOT / '.github/workflows/caption-diagnosis-runtime.yml').read_text()
        job = re.findall(r'^    if: (.+)$', workflow, re.MULTILINE)
        grant = re.findall(r'^        if: (?!always\(\))(.+)$', workflow, re.MULTILINE)
        self.assertEqual(len(job), 1)
        self.assertEqual(len(grant), 1)
        self.assertEqual(gate(job[0]), gate(grant[0]))
        if json.loads(pair.DEFAULT_LOCK.read_text())['status'] != 'pinned-reviewed':
            self.assertEqual(gate(job[0]), ('closed',))
        for forbidden in ('./gradlew', 'chmod 666', 'chmod 777', 'KERNEL==', '-accel off', '-accel tcg'):
            self.assertNotIn(forbidden, workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        self.assertIn('accept-android-sdk-licenses: false', workflow)
        self.assertLess(workflow.index('prepare_caption_runtime_pair.py --download'), workflow.index('setfacl -m'))
        self.assertLess(workflow.index('apt-get install --no-install-recommends -y acl libpulse0'), workflow.index(' -version'))
        shell = ROOT / 'scripts/run_caption_runtime_sessions.sh'
        subprocess.run(['bash', '-n', str(shell)], check=True)
        source = shell.read_text()
        self.assertIn('-accel on', source)
        self.assertIn('setfacl --restore="$ACL"', source)
        self.assertIn('for name in phone tablet diagnosis', source)
        self.assertIn('set -o noclobber', source)
        self.assertIn('rm -f cache/fn67-listen-360.png cache/fn67-listen-840.png', source)
        self.assertEqual(source.count('-e captionDiagnosis true'), 1)
        for forbidden in ('./gradlew', 'chmod 666', 'chmod 777', '-accel off', '-accel tcg'):
            self.assertNotIn(forbidden, source)

    def test_broad_repeated_or_partial_run_gates_are_rejected(self):
        for value in ('true', 'always()', 'github.run_attempt == 1', 'github.run_number == 2',
                      'github.run_number == 2 && github.run_attempt == 2',
                      'github.run_number >= 2 && github.run_attempt == 1'):
            with self.assertRaises(ValueError):
                gate(value)
        self.assertEqual(gate('github.run_number == 2 && github.run_attempt == 1'), ('one-run', 2))


if __name__ == '__main__':
    unittest.main()
