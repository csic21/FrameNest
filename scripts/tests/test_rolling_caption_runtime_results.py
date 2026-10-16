"""Synthetic schema checks only: these strings are not ASR or ML Kit evidence."""
import copy
import json
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_rolling_caption_runtime_results import ASSETS, SOURCE_SHA256, validate_report

APP = 'a' * 64
TEST = 'b' * 64


def translated():
    return {'input_text': 'synthetic source', 'target_text': 'synthetic target', 'mt_status': 'returned', 'mt_ms': 1.0}


def fixture_report(identity):
    duration = identity['duration_ms']
    windows = []
    requests = []
    for index, start in enumerate(range(0, duration, 3000)):
        end = min(start + 3000, duration)
        windows.append({'status': 'collected', 'window_start_ms': start, 'window_end_ms': end,
                        'text_src': 'synthetic source', 'text_tgt': 'synthetic target', 'translation_retryable_failure': False,
                        'actual_mt_calls': [{'input_text': 'synthetic source', 'target_text': 'synthetic target', 'status': 'returned'}]})
        requests.append({'request_id': index + 1, 'kind': 'BOOTSTRAP', 'context_start_sample': start * 16,
                         'observed_end_sample': end * 16, 'pcm_samples': (end - start) * 16, 'status': 'collected',
                         'final_coverage': False, 'tokens_exactly_reconstruct_raw': True,
                         'raw_text': 'synthetic source', 'raw_tokens': ['synthetic source'],
                         'controller_status': 'FINISHED' if end == duration else 'NEED_HYPOTHESIS'})
    last = requests[-1]
    preview = dict(translated(), unit_id=1, type='REVISABLE_PREVIEW', hypothesis_request_id=last['request_id'],
                   context_start_sample=last['context_start_sample'], observed_end_sample=last['observed_end_sample'],
                   raw_start_utf16=0, raw_end_utf16=len(last['raw_text']), final_coverage=False,
                   consumes_controller_tail=False, cross_origin_overlap_possible=True,
                   supersedes_preview_unit_id=None, is_same_end_correction=False, new_media_latency_sample=True)
    ceiling = dict(translated(), purpose='QUALITY_CEILING_ONLY', bounded_latency_unit=False, final_coverage=False)
    return {'fixture_id': identity['id'], 'duration_ms': duration, 'pcm': identity['pcm'],
            'production_3s': {'status': 'collected', 'window_ms': 3000, 'audio_traversal_complete': True,
                              'processed_through_ms': duration, 'expected_window_count': len(windows),
                              'completed_window_count': len(windows), 'rows': windows},
            'rolling': {'status': 'collected', 'audio_traversal_complete': True, 'eof_observed': True,
                        'final_coverage': False, 'source_boundary_implemented': False, 'renderer_implemented': False,
                        'asr_requests': requests, 'asr_request_count': len(requests), 'bounded_mt_units': [preview],
                        'bounded_mt_request_count': 1, 'bounded_mt_failed_or_empty_count': 0, 'preview_request_count': 1,
                        'stable_sentence_request_count': 0, 'same_end_correction_count': 0, 'candidate_revisions': [],
                        'candidate_revision_count': 0, 'quality_ceiling_groups': [ceiling], 'quality_ceiling_group_count': 1,
                        'quality_ceiling_mt_request_count': 1, 'quality_ceiling_unavailable_count': 0,
                        'quality_ceiling_mt_failed_or_empty_count': 0}}


def valid_report():
    diagnosis = json.loads((ASSETS / 'manifest.json').read_text())
    emoji = json.loads((ASSETS / 'fixed-window-comparison.json').read_text())['emoji']
    controls = [dict(translated(), reference_unit_id=unit['id']) for unit in diagnosis['units']]
    return {'schema_version': 1, 'status': 'collected', 'collection_complete': True, 'app_apk_sha256': APP,
            'test_apk_sha256': TEST, 'source_aar_sha256': SOURCE_SHA256,
            'runtime_identity': f'1.13.8-asr-no-tts-sha256-{SOURCE_SHA256}', 'accuracy_accepted': False,
            'hardware_performance_accepted': False, 'semantic_acceptance_passed': None, 'final_coverage_claimed': False,
            'production_default_window_ms': 3000, 'preview_media_deadline_ms': 6000, 'bounded_hypothesis_max_ms': 18000,
            'expected_fixture_count': 2, 'collected_fixture_count': 2, 'expected_reference_unit_count': len(controls),
            'reference_to_mt': controls, 'reference_span_native_asr_to_mt': copy.deepcopy(controls),
            'reference_mt_attempt_count': len(controls), 'reference_span_mt_attempt_count': len(controls),
            'reference_mt_failed_or_empty_count': 0, 'reference_span_mt_failed_or_empty_count': 0,
            'fixtures': [fixture_report(diagnosis), fixture_report(emoji)]}


class RollingCaptionRuntimeResultTest(unittest.TestCase):
    def test_success_is_counts_only_collection_never_acceptance(self):
        result = validate_report(valid_report(), APP, TEST)
        self.assertTrue(result['collection_verified'])
        self.assertFalse(result['semantic_accuracy_accepted'])
        self.assertFalse(result['final_coverage_accepted'])
        self.assertNotIn('synthetic', json.dumps(result))

    def reject(self, mutate):
        report = valid_report()
        mutate(report)
        with self.assertRaises((ValueError, KeyError, TypeError, UnicodeError)):
            validate_report(report, APP, TEST)

    def test_wrong_hash_partial_status_missing_fixture_or_counts_reject(self):
        for mutate in [lambda r: r.update(app_apk_sha256='c' * 64), lambda r: r.update(status='running'),
                       lambda r: r.update(collection_complete=False), lambda r: r['fixtures'].pop(),
                       lambda r: r.update(collected_fixture_count=1), lambda r: r.update(reference_mt_attempt_count=3)]:
            self.reject(mutate)

    def test_false_acceptance_or_coverage_rejects(self):
        for key in ('accuracy_accepted', 'hardware_performance_accepted', 'semantic_acceptance_passed', 'final_coverage_claimed'):
            self.reject(lambda r, key=key: r.update({key: True}))
        self.reject(lambda r: r['fixtures'][0]['rolling'].update(final_coverage=True))
        self.reject(lambda r: r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(final_coverage=True))
        self.reject(lambda r: r['fixtures'][0]['rolling']['quality_ceiling_groups'][0].update(bounded_latency_unit=True))

    def test_failed_empty_or_unreported_mt_cannot_pass(self):
        self.reject(lambda r: r['reference_to_mt'][0].update(mt_status='failed'))
        self.reject(lambda r: r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(target_text=''))
        self.reject(lambda r: r['fixtures'][0]['rolling']['quality_ceiling_groups'][0].update(target_text=''))
        def honestly_failed(r):
            r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(mt_status='failed')
            r['fixtures'][0]['rolling']['bounded_mt_failed_or_empty_count'] = 1
        self.reject(honestly_failed)
        self.reject(lambda r: r['fixtures'][0]['production_3s']['rows'][0].update(text_tgt=''))

    def test_missing_eof_oversize_skipped_request_or_token_mismatch_reject(self):
        self.reject(lambda r: r['fixtures'][0]['rolling'].update(eof_observed=False))
        self.reject(lambda r: r['fixtures'][0]['rolling']['asr_requests'][0].update(observed_end_sample=19 * 16000, pcm_samples=19 * 16000))
        self.reject(lambda r: r['fixtures'][0]['rolling']['asr_requests'].pop(0))
        self.reject(lambda r: r['fixtures'][0]['rolling']['asr_requests'][0].update(raw_tokens=['different']))
        self.reject(lambda r: r['fixtures'][0]['rolling']['asr_requests'][-1].update(controller_status='NEED_HYPOTHESIS'))

    def test_empty_mt_collection_cannot_pass_with_zero_counts(self):
        def remove_mt(r):
            r['fixtures'][0]['rolling'].update(bounded_mt_units=[], bounded_mt_request_count=0,
                                               preview_request_count=0, stable_sentence_request_count=0)
        self.reject(remove_mt)
        def remove_ceilings(r):
            r['fixtures'][0]['rolling'].update(quality_ceiling_groups=[], quality_ceiling_group_count=0,
                                               quality_ceiling_mt_request_count=0)
        self.reject(remove_ceilings)

    def test_prefix_fragment_cannot_masquerade_as_preview(self):
        def fragment(r):
            unit = r['fixtures'][0]['rolling']['bounded_mt_units'][0]
            unit.update(input_text='synthetic', raw_end_utf16=9)
        self.reject(fragment)
        self.reject(lambda r: r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(consumes_controller_tail=True))

    def test_unavailable_ownership_ceiling_is_explicit_not_fake_mt(self):
        report = valid_report(); rolling = report['fixtures'][0]['rolling']
        rolling['quality_ceiling_groups'][0].update(input_text=None, status='unavailable_ambiguous_ownership')
        rolling['quality_ceiling_groups'][0].pop('mt_status')
        rolling['quality_ceiling_groups'][0].pop('target_text')
        rolling.update(quality_ceiling_mt_request_count=0, quality_ceiling_unavailable_count=1)
        self.assertTrue(validate_report(report, APP, TEST)['collection_verified'])

    def test_valid_same_end_correction_and_utf16_preview(self):
        report = valid_report(); rolling = report['fixtures'][0]['rolling']
        prior = rolling['asr_requests'][-1]
        prior['controller_status'] = 'RECOVERY_REQUESTED'
        recovered = copy.deepcopy(prior)
        text = '😀、合成入力'
        recovered.update(request_id=prior['request_id'] + 1, kind='RECOVERY', raw_text=text,
                         raw_tokens=[text], controller_status='FINISHED')
        rolling['asr_requests'].append(recovered)
        correction = copy.deepcopy(rolling['bounded_mt_units'][-1])
        correction.update(unit_id=2, hypothesis_request_id=recovered['request_id'], input_text=text,
                          raw_end_utf16=len(text.encode('utf-16-le')) // 2, supersedes_preview_unit_id=1,
                          is_same_end_correction=True, new_media_latency_sample=False)
        rolling['bounded_mt_units'].append(correction)
        rolling.update(asr_request_count=len(rolling['asr_requests']), bounded_mt_request_count=2,
                       preview_request_count=2, same_end_correction_count=1)
        self.assertTrue(validate_report(report, APP, TEST)['collection_verified'])
        correction['new_media_latency_sample'] = True
        with self.assertRaises(ValueError):
            validate_report(report, APP, TEST)

    def test_correction_cannot_count_as_new_media_sample(self):
        self.reject(lambda r: r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(is_same_end_correction=True))
        self.reject(lambda r: r['fixtures'][0]['rolling']['bounded_mt_units'][0].update(supersedes_preview_unit_id=999))


if __name__ == '__main__':
    unittest.main()
