#!/usr/bin/env python3
"""Bounded two-fixture 3s/6s collection checks; no transcript or MT scoring."""
from collections import Counter
import json
import math
import re

from fixed_caption_window_schema import validate_comparison
from prepare_caption_runtime_pair import ASSETS, PROFILE, digest, require

STAGES = ('model_check_ms', 'pcm_copy_ms', 'silence_gate_ms', 'asr_ms_including_observer',
          'mt_preparation_ms_overlaps_asr', 'mt_preparation_exposed_wait_ms', 'mt_ms')


def timing(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def check_row(row, index, window_ms, identity):
    duration = identity['duration_ms']
    start, end = index * window_ms, min((index + 1) * window_ms, duration)
    require(row['status'] == 'collected' and row['window_id'] == f'w{index}' and row['window_index'] == index
            and row['window_start_ms'] == start and row['window_end_ms'] == end
            and row['window_duration_ms'] == end - start and row['core_pcm_samples'] == (end - start) * 16
            and row['original_window_start_ms'] == identity['original_media_start_ms'] + start
            and row['original_window_end_ms'] == identity['original_media_start_ms'] + end
            and row['is_final_window'] is (end == duration) and row['semantic_acceptance_passed'] is None,
            'Incomplete or reordered fixed-window row')
    read = row['actual_pcm_read']
    read_start, requested_end = max(0, start - 750), end + 750
    read_end = min(requested_end, duration)
    require(read['requested_start_ms'] == read_start and read['requested_end_ms'] == requested_end
            and read['actual_start_ms'] == read_start and read['actual_end_ms'] == read_end
            and read['samples'] == (read_end - read_start) * 16
            and re.fullmatch('[0-9a-f]{64}', read['s16le_sha256']) is not None, 'Actual PCM extent/hash mismatch')
    require(isinstance(row['text_src'], str) and isinstance(row['text_tgt'], str)
            and type(row['translation_retryable_failure']) is bool and timing(row['wall_ms']), 'Missing fixed-window text/timing result')
    stages = row['stage_timings']
    require(isinstance(stages, dict) and all(k in stages and (stages[k] is None or timing(stages[k])) for k in STAGES), 'Missing stage timing fields')
    asr, mt = row['actual_production_asr_calls'], row['actual_mt_calls']
    require(isinstance(asr, list) and isinstance(mt, list) and row['asr_call_count'] == len(asr) <= 1
            and row['mt_call_count'] == len(mt) == (1 if row['text_src'].strip() else 0), 'Missing or repeated ASR/MT calls')
    require(len(asr) == (0 if row['blank_reason'] in ('NearSilence', 'EmptyPcm') else 1), 'Missing eligible ASR call')
    for call in asr:
        require(call['status'] == 'returned' and call['input_pcm_samples'] == read['samples']
                and call['input_pcm_s16le_sha256'] == read['s16le_sha256']
                and isinstance(call['full_recognition_text'], str) and isinstance(call['mapped_tokens'], list), 'ASR identity/result mismatch')
        for token in call['mapped_tokens']:
            require(isinstance(token['text'], str) and type(token['selected_by_existing_midpoint_rule']) is bool
                    and all(type(token[k]) is int for k in ('start_ms_relative_to_decode', 'end_ms_relative_to_decode', 'start_ms_clip', 'end_ms_clip')),
                    'Missing mapped ASR token evidence')
    for call in mt:
        require(call['input_text'] == row['text_src'] and call['source_language'] == 'ja' and call['target_language'] == 'zh'
                and timing(call['wall_ms']), 'MT input/timing mismatch')
        if call['status'] == 'failed':
            require(isinstance(call.get('failure_class'), str) and bool(call['failure_class'].strip())
                    and row['text_tgt'] == '' and row['translation_retryable_failure'] is True, 'Missing MT failure evidence')
        else:
            require(call['status'] in ('returned', 'empty_target') and isinstance(call.get('target_text'), str)
                    and bool(call['target_text'].strip()) == (call['status'] == 'returned')
                    and call['target_text'] == row['text_tgt'], 'Missing MT target text/status')
    if row['text_src'].strip():
        require(timing(row['source_ready_ms']) and row['published_source_text'] == row['text_src'], 'Missing source-ready timing/text')
    return mt[0]['status'] if mt else 'skipped_empty_source'


def check_comparison(root):
    raw = (root / 'comparison/fixed-caption-window-comparison.json').read_bytes()
    report = json.loads(raw)
    pair = json.loads((root / 'input/pair-verification.json').read_text())
    comparison_bytes = (ASSETS / 'fixed-window-comparison.json').read_bytes()
    comparison = validate_comparison(json.loads(comparison_bytes))
    diagnosis_bytes = (ASSETS / 'manifest.json').read_bytes()
    diagnosis = json.loads(diagnosis_bytes)
    require(pair['status'] == 'retained-pair-verified' and report['schema_version'] == 1
            and report['status'] == 'collected' and report['collection_complete'] is True, 'Fixed-window collection incomplete')
    require(report['app_apk_sha256'] == pair['lock']['app_apk_sha256'] and report['test_apk_sha256'] == pair['lock']['test_apk_sha256']
            and report['source_aar_sha256'] == PROFILE['aar_sha256'] and report['runtime_identity'] == PROFILE['runtime_identity']
            and report['native_version'].removeprefix('v') == '1.13.8' and report['api'] == 35 and report['abi'] == 'x86_64'
            and report['verified_source_profile']['profile'] == PROFILE, 'Fixed-window runtime identity mismatch')
    require(report['comparison_manifest_sha256'] == digest(comparison_bytes) and report['comparison_manifest'] == comparison
            and report['diagnosis_manifest_sha256'] == digest(diagnosis_bytes) and report['diagnosis_manifest'] == diagnosis
            and report['prepared_inputs'] == pair['diagnosis_prepared_inputs']
            and report['comparison_prepared_inputs'] == pair['comparison_prepared_inputs'], 'Fixed-window input identity mismatch')
    require(report['window_ms'] == [3000, 6000] and report['context_padding_ms'] == 750 and report['production_default_window_ms'] == 3000
            and report['semantic_acceptance_passed'] is None and report['accuracy_accepted'] is False
            and report['hardware_performance_accepted'] is False, 'Unexpected comparison scope or acceptance claim')
    fixtures = report['fixtures']
    require(len(fixtures) == 2, 'Expected exactly the two fixed fixtures')
    counts, outcomes = {}, Counter()
    for fixture, identity in zip(fixtures, (diagnosis, comparison['emoji'])):
        duration = identity['duration_ms']
        require(fixture['fixture_id'] == identity['id'] and fixture['duration_ms'] == duration
                and fixture['original_media_start_ms'] == identity['original_media_start_ms'] and fixture['pcm'] == identity['pcm']
                and fixture['reference_is_verbatim_gold'] is False
                and fixture['reference_kind'] == (identity['reference']['kind'] if 'reference' in identity else identity['reference_kind']),
                'Full fixture identity mismatch')
        conditions = fixture['conditions']
        require([c['window_ms'] for c in conditions] == [3000, 6000], 'Only one fixed 3s and 6s condition is allowed')
        counts[identity['id']] = {}
        for condition in conditions:
            window_ms = condition['window_ms']
            count = (duration + window_ms - 1) // window_ms
            require(condition['status'] == 'collected' and condition['expected_window_count'] == count
                    and condition['semantic_acceptance_passed'] is None and timing(condition['wall_ms'])
                    and len(condition['rows']) == count, 'Incomplete fixed-window condition')
            for index, row in enumerate(condition['rows']):
                outcomes[check_row(row, index, window_ms, identity)] += 1
            expected_coverage = {'processed_through_ms': duration, 'duration_ms': duration, 'covered_core_ms': duration,
                                 'covered_core_samples': duration * 16, 'total_core_samples': duration * 16,
                                 'completed_windows': count, 'expected_windows': count, 'gap_ms': 0, 'overlap_ms': 0,
                                 'remaining_ms': 0, 'complete': True}
            require(all(type(condition['coverage'][k]) is type(v) and condition['coverage'][k] == v for k, v in expected_coverage.items()),
                    'Full fixed-window coverage missing')
            expected_eof = {'reached': True, 'core_end_ms': duration, 'actual_pcm_end_ms': duration,
                            'requested_context_end_ms': duration + 750, 'context_clipped_at_eof': True,
                            'remaining_core_samples': 0, 'final_window_duration_ms': duration - (count - 1) * window_ms}
            require(all(type(condition['final_eof'][k]) is type(v) and condition['final_eof'][k] == v for k, v in expected_eof.items()),
                    'Actual final EOF evidence missing')
            counts[identity['id']][str(window_ms)] = count
    return {'status': 'collected', 'report_sha256': digest(raw), 'windows': counts, 'mt_observations': dict(outcomes),
            'semantic_acceptance_passed': None,
            'scope': 'Complete fixed 3s/6s processing evidence only; no semantic, word-retention, or performance acceptance.'}
