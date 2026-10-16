"""Synthetic fixed 3s/6s reports only; no Android or model execution."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_caption_runtime_results as sessions
import check_fixed_caption_runtime_results as target
from prepare_caption_runtime_pair import ASSETS, PROFILE, digest


class FixedCaptionRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'comparison').mkdir()
        (self.root / 'input').mkdir()
        diagnosis_bytes = (ASSETS / 'manifest.json').read_bytes()
        comparison_bytes = (ASSETS / 'fixed-window-comparison.json').read_bytes()
        diagnosis, comparison = json.loads(diagnosis_bytes), json.loads(comparison_bytes)
        self.report = {'schema_version': 1, 'status': 'collected', 'collection_complete': True,
            'app_apk_sha256': 'a' * 64, 'test_apk_sha256': 'b' * 64, 'source_aar_sha256': PROFILE['aar_sha256'],
            'runtime_identity': PROFILE['runtime_identity'], 'native_version': '1.13.8', 'api': 35, 'abi': 'x86_64',
            'verified_source_profile': {'profile': PROFILE}, 'comparison_manifest': comparison,
            'comparison_manifest_sha256': digest(comparison_bytes), 'diagnosis_manifest': diagnosis,
            'diagnosis_manifest_sha256': digest(diagnosis_bytes), 'prepared_inputs': {'synthetic_diagnosis': True},
            'comparison_prepared_inputs': {'synthetic_comparison': True}, 'window_ms': [3000, 6000],
            'context_padding_ms': 750, 'production_default_window_ms': 3000, 'semantic_acceptance_passed': None,
            'accuracy_accepted': False, 'hardware_performance_accepted': False, 'fixtures': []}
        for identity in (diagnosis, comparison['emoji']):
            duration, origin = identity['duration_ms'], identity['original_media_start_ms']
            fixture = {'fixture_id': identity['id'], 'duration_ms': duration, 'original_media_start_ms': origin,
                       'pcm': identity['pcm'], 'reference_is_verbatim_gold': False,
                       'reference_kind': identity['reference']['kind'] if 'reference' in identity else identity['reference_kind'],
                       'conditions': []}
            for window_ms in (3000, 6000):
                count = (duration + window_ms - 1) // window_ms
                rows = []
                for i in range(count):
                    start, end = i * window_ms, min((i + 1) * window_ms, duration)
                    left, right = max(0, start - 750), min(end + 750, duration)
                    read = {'requested_start_ms': left, 'requested_end_ms': end + 750, 'actual_start_ms': left,
                            'actual_end_ms': right, 'samples': (right - left) * 16, 's16le_sha256': 'c' * 64}
                    asr = {'status': 'returned', 'input_pcm_samples': read['samples'], 'input_pcm_s16le_sha256': read['s16le_sha256'],
                           'full_recognition_text': 'synthetic source', 'mapped_tokens': []}
                    mt = {'status': 'returned', 'input_text': 'synthetic source', 'target_text': 'synthetic target',
                          'source_language': 'ja', 'target_language': 'zh', 'wall_ms': 1.0}
                    rows.append({'status': 'collected', 'window_id': f'w{i}', 'window_index': i,
                        'window_start_ms': start, 'window_end_ms': end, 'window_duration_ms': end - start,
                        'core_pcm_samples': (end - start) * 16, 'original_window_start_ms': origin + start,
                        'original_window_end_ms': origin + end, 'is_final_window': end == duration,
                        'semantic_acceptance_passed': None, 'actual_pcm_read': read,
                        'text_src': 'synthetic source', 'text_tgt': 'synthetic target', 'blank_reason': None,
                        'translation_retryable_failure': False, 'wall_ms': 2.0, 'source_ready_ms': 0.5,
                        'published_source_text': 'synthetic source', 'stage_timings': dict.fromkeys(target.STAGES, 0),
                        'asr_call_count': 1, 'mt_call_count': 1, 'actual_production_asr_calls': [asr], 'actual_mt_calls': [mt]})
                fixture['conditions'].append({'window_ms': window_ms, 'status': 'collected', 'expected_window_count': count,
                    'semantic_acceptance_passed': None, 'wall_ms': 3.0, 'rows': rows,
                    'coverage': {'processed_through_ms': duration, 'duration_ms': duration, 'covered_core_ms': duration,
                        'covered_core_samples': duration * 16, 'total_core_samples': duration * 16, 'completed_windows': count,
                        'expected_windows': count, 'gap_ms': 0, 'overlap_ms': 0, 'remaining_ms': 0, 'complete': True},
                    'final_eof': {'reached': True, 'core_end_ms': duration, 'actual_pcm_end_ms': duration,
                        'requested_context_end_ms': duration + 750, 'context_clipped_at_eof': True,
                        'remaining_core_samples': 0, 'final_window_duration_ms': duration - (count - 1) * window_ms}})
            self.report['fixtures'].append(fixture)
        pair = {'status': 'retained-pair-verified', 'lock': {'app_apk_sha256': 'a' * 64, 'test_apk_sha256': 'b' * 64},
                'diagnosis_prepared_inputs': self.report['prepared_inputs'],
                'comparison_prepared_inputs': self.report['comparison_prepared_inputs']}
        (self.root / 'input/pair-verification.json').write_text(json.dumps(pair))
        self.save()

    def save(self):
        (self.root / 'comparison/fixed-caption-window-comparison.json').write_text(json.dumps(self.report))

    def test_exact_two_fixtures_two_conditions_and_complete_eof_collects(self):
        result = target.check_comparison(self.root)
        self.assertEqual(list(result['windows'].values()), [{'3000': 16, '6000': 8}, {'3000': 38, '6000': 19}])
        self.assertEqual(result['mt_observations'], {'returned': 81})
        self.assertIsNone(result['semantic_acceptance_passed'])

    def test_extra_condition_missing_row_coverage_eof_or_identity_fails(self):
        original = copy.deepcopy(self.report)
        changes = [lambda r: r['fixtures'].append(copy.deepcopy(r['fixtures'][0])),
                   lambda r: r['fixtures'][0]['conditions'].append(copy.deepcopy(r['fixtures'][0]['conditions'][0])),
                   lambda r: r['fixtures'][1]['conditions'][1]['rows'].pop(),
                   lambda r: r['fixtures'][0]['conditions'][0]['coverage'].update(complete=False),
                   lambda r: r['fixtures'][1]['conditions'][1]['final_eof'].update(remaining_core_samples=1),
                   lambda r: r.update(app_apk_sha256='d' * 64),
                   lambda r: r.update(semantic_acceptance_passed=True),
                   lambda r: r.update(production_default_window_ms=6000),
                   lambda r: r['comparison_prepared_inputs'].update(changed=True)]
        for change in changes:
            self.report = copy.deepcopy(original)
            change(self.report)
            self.save()
            with self.assertRaises(ValueError):
                target.check_comparison(self.root)

    def test_bad_row_actual_pcm_missing_calls_and_fake_mt_result_fail(self):
        original = copy.deepcopy(self.report)
        changes = [lambda row: row.update(window_start_ms=1),
                   lambda row: row['actual_pcm_read'].update(actual_end_ms=2999),
                   lambda row: row['actual_production_asr_calls'].append(copy.deepcopy(row['actual_production_asr_calls'][0])),
                   lambda row: row['actual_mt_calls'].clear(),
                   lambda row: row['actual_mt_calls'][0].pop('target_text'),
                   lambda row: row['actual_mt_calls'][0].update(target_text=''),
                   lambda row: row['actual_mt_calls'][0].update(target_text='different target'),
                   lambda row: row['stage_timings'].pop('mt_ms'),
                   lambda row: row.update(wall_ms=-1)]
        for change in changes:
            self.report = copy.deepcopy(original)
            change(self.report['fixtures'][0]['conditions'][0]['rows'][0])
            self.save()
            with self.assertRaises(ValueError):
                target.check_comparison(self.root)

    def test_blank_source_and_real_mt_failure_preserve_coverage_without_semantic_pass(self):
        rows = self.report['fixtures'][0]['conditions'][0]['rows']
        rows[0].update(text_src='', text_tgt='', blank_reason='NearSilence', asr_call_count=0, mt_call_count=0,
                       actual_production_asr_calls=[], actual_mt_calls=[])
        rows[1]['actual_mt_calls'][0] = {'status': 'failed', 'failure_class': 'java.lang.IllegalStateException',
            'input_text': 'synthetic source', 'source_language': 'ja', 'target_language': 'zh', 'wall_ms': 1.0}
        rows[1].update(text_tgt='', translation_retryable_failure=True)
        self.save()
        result = target.check_comparison(self.root)
        self.assertEqual(result['mt_observations'], {'skipped_empty_source': 1, 'failed': 1, 'returned': 79})
        self.assertIsNone(result['semantic_acceptance_passed'])
        del rows[1]['actual_mt_calls'][0]['failure_class']
        self.save()
        with self.assertRaisesRegex(ValueError, 'MT failure evidence'):
            target.check_comparison(self.root)

    def test_opt_in_summary_requires_comparison_session_and_report(self):
        with patch.object(sessions, 'check_session', return_value={'status': 'passed'}), patch.object(sessions, 'check_diagnosis', return_value={'status': 'collected'}):
            self.assertTrue(sessions.record(self.root, include_comparison=True))
            (self.root / 'comparison/fixed-caption-window-comparison.json').unlink()
            self.assertFalse(sessions.record(self.root, include_comparison=True))
        summary = json.loads((self.root / 'validation-summary.json').read_text())
        self.assertTrue(summary['fixed_window_comparison_requested'])
        self.assertEqual(summary['checks']['fixed_window_evidence']['status'], 'failed')

    def test_comparison_skips_and_repeated_instrumentation_are_rejected(self):
        directory = self.root / 'comparison'
        (directory / 'exit-code.txt').write_text('0\n')
        event = ('INSTRUMENTATION_STATUS: class=com.framenest.feature.listen_translate.FixedCaptionWindowComparisonTest\n'
                 'INSTRUMENTATION_STATUS: test=compareThreeAndSixSecondWindows\nINSTRUMENTATION_STATUS_CODE: {}\n')
        (directory / 'instrumentation.txt').write_text(event.format(0) + 'INSTRUMENTATION_CODE: -1\n')
        self.assertEqual(sessions.check_session(self.root, 'comparison')['tests_passed'], 1)
        for events in (event.format(-4), event.format(0) * 2):
            (directory / 'instrumentation.txt').write_text(events + 'INSTRUMENTATION_CODE: -1\n')
            with self.assertRaises(ValueError):
                sessions.check_session(self.root, 'comparison')


if __name__ == '__main__':
    unittest.main()
