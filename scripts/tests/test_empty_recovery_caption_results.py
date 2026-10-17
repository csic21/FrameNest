"""Synthetic structural checks only; no string here is ASR, MT or runtime evidence."""
import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_empty_recovery_caption_results import digest, validate_fixture


def fixture(mode='success', end=9000):
    sources = ['😀日', ' 本', '後']
    baseline_rows = []
    for i, start in enumerate(range(0, end, 3000)):
        stop = min(start + 3000, end)
        target = '' if i == 0 and mode != 'normal' else f'target {i}'
        pcm = dict(requested_start_ms=max(0, start-750), requested_end_ms=stop+750,
                   actual_start_ms=max(0, start-750), actual_end_ms=min(end, stop+750),
                   samples=(min(end, stop+750)-max(0, start-750))*16, s16le_sha256=digest(f'pcm {i}'))
        mt = dict(input_text=sources[i], target_text=target, status='returned' if target else 'empty_target')
        baseline_rows.append(dict(source_unit_id=i, status='collected', window_start_ms=start,
                                 window_end_ms=stop, text_src=sources[i], text_tgt=target,
                                 cue_start_ms=start+min(100,(stop-start)//4), cue_end_ms=stop-min(100,(stop-start)//4), blank_reason=None,
                                 translation_retryable_failure=False, actual_pcm_read=pcm,
                                 actual_mt_calls=[mt]))

    def result(i, source_only=False):
        b = baseline_rows[i]
        keys = ('text_src', 'text_tgt', 'cue_start_ms', 'cue_end_ms', 'blank_reason', 'translation_retryable_failure')
        r = {k: b[k] for k in keys}
        r.update(processed_through_ms=None, retryable_error_message=None, stage_timings={})
        if source_only: r['text_tgt'] = ''
        return r

    def asr(i, phase, failed=False):
        pcm = baseline_rows[i]['actual_pcm_read']
        return dict(source_unit_id=i, phase=phase, status='failed' if failed else 'returned',
                    input_pcm_samples=pcm['samples'], input_pcm_s16le_sha256=pcm['s16le_sha256'], wall_ms=1.0)

    def read(i, phase, failed=False):
        r = dict(source_unit_id=i, phase=phase, status='failed' if failed else 'returned', wall_ms=0.1)
        if not failed: r['read'] = copy.deepcopy(baseline_rows[i]['actual_pcm_read'])
        return r

    rows = []
    for i in range(len(baseline_rows)):
        if mode == 'success' and i == 1: continue
        b = baseline_rows[i]; t = i*10.0
        original = result(i)
        cached = i == 1 and mode in ('no_seam', 'merged_failed', 'merged_empty')
        normal = dict(b['actual_mt_calls'][0], phase='normal', source_unit_id=i,
                      started_wall_ms=t+1, wall_ms=1.0)
        source_event = dict(result(i, True), event='source_published', source_unit_id=i,
                            observed_wall_ms=t+0.5, required_audio_horizon_ms=b['actual_pcm_read']['actual_end_ms'],
                            required_window_horizon_ms=b['window_end_ms'])
        r = dict(source_unit_id=i, window_start_ms=b['window_start_ms'], window_end_ms=b['window_end_ms'],
                 status='collected', original_result=original, output_result=copy.deepcopy(original),
                 actual_mt_calls=[normal], actual_asr_calls=[] if cached else [asr(i, 'normal')],
                 actual_pcm_reads=[] if cached else [read(i, 'normal')],
                 source_events=[source_event], normal_result_ready_wall_ms=t+2,
                 normal_required_audio_horizon_ms=b['actual_pcm_read']['actual_end_ms'],
                 normal_required_window_horizon_ms=b['window_end_ms'], normal_uses_candidate_cached_source=cached,
                 actual_empty_mt=not bool(original['text_tgt']), eligible_for_one_adjacent_recovery=False,
                 recovery_attempted=False, recovery_succeeded=False, recovery=None, output_source_unit_ids=[i],
                 output_window_end_ms=b['window_end_ms'], required_audio_horizon_ms=b['actual_pcm_read']['actual_end_ms'],
                 required_window_horizon_ms=b['window_end_ms'], target_ready_wall_ms=t+2 if original['text_tgt'] else None,
                 request_wall_ms=4.0, regression_checks={'synthetic_structural_check': True})
        if i == 0 and mode != 'normal':
            r.update(eligible_for_one_adjacent_recovery=True, recovery_attempted=True,
                     granted_next_source_unit_id=1, granted_next_end_ms=baseline_rows[1]['window_end_ms'],
                     required_window_horizon_ms=baseline_rows[1]['window_end_ms'], recovery_returned_wall_ms=t+4)
            r['actual_pcm_reads'].append(read(1, 'recovery', mode == 'read_failed'))
            if mode != 'read_failed':
                r['required_audio_horizon_ms'] = baseline_rows[1]['actual_pcm_read']['actual_end_ms']
                r['actual_asr_calls'].append(asr(1, 'recovery', mode == 'asr_failed'))
            if mode in ('success', 'merged_failed', 'merged_empty'):
                combined = sources[0]+sources[1]
                mt = dict(input_text=combined, phase='recovery', source_unit_id=1, started_wall_ms=t+3, wall_ms=1.0)
                if mode == 'merged_failed': mt.update(status='failed', failure_class='SyntheticMtFailure')
                else: mt.update(status='returned' if mode == 'success' else 'empty_target', target_text='merged target' if mode == 'success' else '')
                r['actual_mt_calls'].append(mt)
            if mode == 'success':
                output = dict(original, text_src=combined, text_tgt='merged target',
                              cue_end_ms=baseline_rows[1]['cue_end_ms'], processed_through_ms=baseline_rows[1]['window_end_ms'])
                r.update(recovery_succeeded=True, output_result=output, output_source_unit_ids=[0, 1],
                         output_window_end_ms=baseline_rows[1]['window_end_ms'], target_ready_wall_ms=t+4,
                         recovery=dict(next_source=result(1, True), result=copy.deepcopy(output),
                                       canonical_source_utf8_bytes_equal=True, canonical_source_utf8_sha256=digest(combined),
                                       recovered_source_utf8_sha256=digest(combined),
                                       post_timing_crossing_morphemes=[dict(raw_start_utf16=2, raw_end_utf16=5, surface='日本', known=True)]))
        ids = r['output_source_unit_ids']; resolved = bool(r['output_result']['text_tgt'])
        r.update(completed_source_unit_ids=ids if resolved else [], unresolved_source_unit_ids=[] if resolved else ids)
        rows.append(r)
    owned = [i for r in rows for i in r['output_source_unit_ids']]
    completed = [i for r in rows for i in r['completed_source_unit_ids']]
    unresolved = [i for r in rows for i in r['unresolved_source_unit_ids']]
    text = ''.join(r['output_result']['text_src'] for r in rows)
    counts = {}
    for r in rows:
        for call in r['actual_asr_calls']:
            key = str(call['source_unit_id']); counts[key] = counts.get(key, 0)+1
    first = next(r for r in rows if r['output_result']['text_tgt'])
    first_target = dict(source_unit_ids=first['output_source_unit_ids'],
                        text_src=first['output_result']['text_src'], text_tgt=first['output_result']['text_tgt'],
                        observed_wall_ms=first['target_ready_wall_ms'], required_audio_horizon_ms=first['required_audio_horizon_ms'],
                        required_window_horizon_ms=first['required_window_horizon_ms'])
    return dict(fixture_id='synthetic', duration_ms=end,
                production_3s=dict(status='collected', audio_traversal_complete=True, processed_through_ms=end,
                                   window_ms=3000, empty_translation_recovery_enabled=False, rows=baseline_rows),
                empty_translation_recovery=dict(status='collected', empty_translation_recovery_enabled=True,
                    session_or_room_measured=False, rendering_measured=False, wall_ms=100.0, rows=rows,
                    visited_source_unit_ids=owned, completed_source_unit_ids=completed, unresolved_source_unit_ids=unresolved,
                    source_conservation=dict(exact_utf8_bytes_equal=True, source_ids_exact_once=True,
                                             baseline_sha256=digest(text), candidate_sha256=digest(text)),
                    asr_call_count=sum(len(r['actual_asr_calls']) for r in rows),
                    mt_call_count=sum(len(r['actual_mt_calls']) for r in rows), recovered_pair_count=int(mode=='success'),
                    normal_request_count=len(rows), asr_calls_per_source_unit=counts, regression_checks_passed=True,
                    audio_grid_traversal_complete=True, first_source=rows[0]['source_events'][0], first_target=first_target,
                    maximum_observed_audio_horizon_ms=end, post_timing_crossing_audit_excluded_from_wall=True,
                    post_timing_crossing_audit_ms=1.0, process_rss_before_candidate_kib=1000, process_rss_after_candidate_kib=1500))


class EmptyRecoveryResultTest(unittest.TestCase):
    def test_actual_pair_and_utf16_crossing(self):
        result = validate_fixture(fixture())
        self.assertEqual(1, result['recovered_pairs'])
        self.assertEqual(3, result['asr_calls'])

    def test_all_normal_results_unchanged(self):
        self.assertEqual(0, validate_fixture(fixture('normal'))['recovered_pairs'])

    def test_cached_next_source_needs_zero_normal_asr_or_pcm_calls(self):
        for mode in ('no_seam', 'merged_failed', 'merged_empty'):
            with self.subTest(mode=mode):
                f = fixture(mode)
                self.assertEqual([], f['empty_translation_recovery']['rows'][1]['actual_asr_calls'])
                self.assertEqual([], f['empty_translation_recovery']['rows'][1]['actual_pcm_reads'])
                self.assertEqual(1, validate_fixture(f)['unresolved_sources'])

    def test_failed_lookahead_does_not_forbid_normal_retry(self):
        for mode in ('read_failed', 'asr_failed'):
            with self.subTest(mode=mode):
                self.assertEqual(1, validate_fixture(fixture(mode))['unresolved_sources'])
        self.assertEqual(4, validate_fixture(fixture('asr_failed'))['asr_calls'])

    def test_eof_clamped_pcm_horizon(self):
        self.assertEqual(3, validate_fixture(fixture('merged_empty', 6200))['baseline_windows'])

    def reject(self, edit, mode='success'):
        f = fixture(mode); edit(f['empty_translation_recovery'])
        with self.assertRaises((ValueError, KeyError, UnicodeError)):
            validate_fixture(f)

    def test_changed_normal_or_failed_output_rejected(self):
        self.reject(lambda c: c['rows'][1]['original_result'].update(text_tgt='changed'))
        self.reject(lambda c: c['rows'][0]['output_result'].update(processed_through_ms=6000), 'merged_failed')

    def test_nonempty_trigger_and_false_completion_rejected(self):
        self.reject(lambda c: c['rows'][0].update(recovery_attempted=True), 'normal')
        self.reject(lambda c: c['rows'][0].update(completed_source_unit_ids=[0]), 'merged_empty')

    def test_wrong_utf16_crossing_surface_or_bytes_rejected(self):
        self.reject(lambda c: c['rows'][0]['recovery']['post_timing_crossing_morphemes'][0].update(raw_start_utf16=1))
        self.reject(lambda c: c['rows'][0]['recovery']['post_timing_crossing_morphemes'][0].update(surface='wrong'))
        self.reject(lambda c: c['rows'][0]['recovery'].update(recovered_source_utf8_sha256='f'*64))

    def test_false_horizon_and_first_target_rejected(self):
        self.reject(lambda c: c['rows'][0].update(required_audio_horizon_ms=6000))
        self.reject(lambda c: c['first_target'].update(observed_wall_ms=7.0))
        self.reject(lambda c: c['rows'][0].update(target_ready_wall_ms=7.0))

    def test_repeated_successful_asr_and_changed_pcm_rejected(self):
        self.reject(lambda c: c['rows'][0]['actual_asr_calls'].append(copy.deepcopy(c['rows'][0]['actual_asr_calls'][0])))
        self.reject(lambda c: c['rows'][0]['actual_pcm_reads'][0]['read'].update(s16le_sha256='f'*64))

    def test_source_first_and_current_span_are_recomputed(self):
        self.reject(lambda c: c['rows'][0]['source_events'][0].update(observed_wall_ms=5.0))
        self.reject(lambda c: c['rows'][0]['source_events'][0].update(cue_end_ms=6000))
        self.reject(lambda c: c['rows'][0]['regression_checks'].update(synthetic_structural_check='true'))

    def test_rss_unavailable_is_allowed_and_negative_rejected(self):
        f = fixture(); f['empty_translation_recovery']['process_rss_before_candidate_kib'] = None
        validate_fixture(f)
        self.reject(lambda c: c.update(process_rss_after_candidate_kib=-1))


if __name__ == '__main__':
    unittest.main()
