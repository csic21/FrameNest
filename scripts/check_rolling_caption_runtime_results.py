#!/usr/bin/env python3
"""Check FN-77 collection integrity, never semantic accuracy or release readiness.

Reads only a retained private runtime report. Output contains counts, not source
or translated text. No networking, Android execution, or evidence upload.
"""
import argparse
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/androidTest/assets/caption-diagnosis'
SOURCE_SHA256 = '6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def integer(value, message):
    require(type(value) is int, message)
    return value


def check_count(value, expected, name):
    require(type(value) is int and value == expected, f'{name}: count mismatch')


def mt_issue(row):
    return row.get('mt_status') != 'returned' or not isinstance(row.get('target_text'), str) or not row['target_text'].strip()


def check_mt(rows, reported_issues, name):
    issues = sum(mt_issue(row) for row in rows)
    check_count(reported_issues, issues, name + ' failed/empty')
    require(issues == 0, name + ': failed or empty actual MT output')
    for row in rows:
        require(isinstance(row.get('input_text'), str) and row['input_text'].strip(), name + ': missing MT input')
        duration = row.get('mt_ms')
        require(type(duration) in (int, float) and math.isfinite(duration) and duration >= 0, name + ': invalid MT timing')


def validate_report(report, expected_app_sha256, expected_test_sha256):
    for expected in (expected_app_sha256, expected_test_sha256):
        require(isinstance(expected, str) and re.fullmatch('[0-9a-f]{64}', expected), 'Expected APK SHA-256 must be pinned')
    require(report['schema_version'] == 1 and report['status'] == 'collected' and report['collection_complete'] is True,
            'Runtime collection is not complete')
    require(report['app_apk_sha256'] == expected_app_sha256 and report['test_apk_sha256'] == expected_test_sha256,
            'Runtime APK pair does not match retained hashes')
    require(report['source_aar_sha256'] == SOURCE_SHA256 and report['runtime_identity'] == f'1.13.8-asr-no-tts-sha256-{SOURCE_SHA256}',
            'Wrong source runtime identity')
    require(report['accuracy_accepted'] is False and report['hardware_performance_accepted'] is False
            and report['semantic_acceptance_passed'] is None and report['final_coverage_claimed'] is False,
            'Collection must not claim semantic acceptance, final coverage, or hardware performance acceptance')
    require(report['production_default_window_ms'] == 3000 and report['preview_media_deadline_ms'] == 6000
            and report['bounded_hypothesis_max_ms'] == 18000, 'Unexpected fixed diagnostic plan')
    diagnosis = json.loads((ASSETS / 'manifest.json').read_text())
    emoji = json.loads((ASSETS / 'fixed-window-comparison.json').read_text())['emoji']
    expected = {item['id']: item for item in (diagnosis, emoji)}
    fixtures = report['fixtures']
    check_count(report['expected_fixture_count'], 2, 'expected fixtures')
    check_count(report['collected_fixture_count'], 2, 'collected fixtures')
    require(len(fixtures) == 2 and {row['fixture_id'] for row in fixtures} == set(expected), 'Missing, duplicate or unexpected fixture')
    reference_count = len(diagnosis['units'])
    check_count(report['expected_reference_unit_count'], reference_count, 'expected reference units')
    for rows_key, count_key, issues_key in (
        ('reference_to_mt', 'reference_mt_attempt_count', 'reference_mt_failed_or_empty_count'),
        ('reference_span_native_asr_to_mt', 'reference_span_mt_attempt_count', 'reference_span_mt_failed_or_empty_count'),
    ):
        rows = report[rows_key]
        check_count(report[count_key], reference_count, count_key)
        require(len(rows) == reference_count and {r['reference_unit_id'] for r in rows} == {r['id'] for r in diagnosis['units']},
                rows_key + ': missing or duplicate reference control')
        check_mt(rows, report[issues_key], rows_key)

    summary = []
    for fixture in fixtures:
        identity = expected[fixture['fixture_id']]
        duration = identity['duration_ms']
        require(fixture['duration_ms'] == duration and fixture['pcm'] == identity['pcm'], 'Fixture duration/PCM identity mismatch')
        baseline = fixture['production_3s']
        baseline_rows = baseline['rows']
        count = (duration + 2999) // 3000
        require(baseline['status'] == 'collected' and baseline['window_ms'] == 3000 and baseline['audio_traversal_complete'] is True
                and baseline['processed_through_ms'] == duration, 'Baseline traversal incomplete')
        check_count(baseline['expected_window_count'], count, 'baseline expected windows')
        check_count(baseline['completed_window_count'], count, 'baseline completed windows')
        require(len(baseline_rows) == count, 'Baseline row count mismatch')
        for index, row in enumerate(baseline_rows):
            require(row['status'] == 'collected' and row['window_start_ms'] == index * 3000
                    and row['window_end_ms'] == min((index + 1) * 3000, duration), 'Baseline has missing/reordered/changed window')
            calls = row['actual_mt_calls']
            require(isinstance(row['text_src'], str) and isinstance(row['text_tgt'], str), 'Baseline missing source/target')
            if row['text_src'].strip():
                require(len(calls) == 1 and calls[0]['input_text'] == row['text_src'] and calls[0]['status'] == 'returned'
                        and calls[0]['target_text'] == row['text_tgt'] and row['text_tgt'].strip(), 'Baseline failed/empty or mismatched actual MT')
            else:
                require(not calls and not row['text_tgt'].strip(), 'Blank baseline unexpectedly translated')
            require(row['translation_retryable_failure'] is False, 'Baseline translation failure')

        rolling = fixture['rolling']
        require(rolling['status'] == 'collected' and rolling['audio_traversal_complete'] is True and rolling['eof_observed'] is True,
                'Rolling traversal/EOF incomplete')
        require(rolling['final_coverage'] is False and rolling['source_boundary_implemented'] is False and rolling['renderer_implemented'] is False,
                'Rolling collection must not claim final coverage or implemented rendering')
        requests = rolling['asr_requests']
        check_count(rolling['asr_request_count'], len(requests), 'ASR requests')
        require(bool(requests), 'No real rolling ASR requests')
        by_id = {}
        previous_end = 0
        previous_id = 0
        for row in requests:
            request_id = integer(row['request_id'], 'ASR request ID must be an integer')
            start = integer(row['context_start_sample'], 'ASR context start must be an integer')
            end = integer(row['observed_end_sample'], 'ASR end must be an integer')
            require(request_id == previous_id + 1 and 0 <= start <= previous_end and start < end <= duration * 16 and previous_end <= end <= previous_end + 3000 * 16
                    and end - start <= 18000 * 16 and row['pcm_samples'] == end - start,
                    'ASR request is stale, out of fixture, or exceeds 18 seconds')
            require(row['status'] == 'collected' and row['final_coverage'] is False and row['tokens_exactly_reconstruct_raw'] is True
                    and ''.join(row['raw_tokens']) == row['raw_text'], 'ASR reply missing or token/raw reconstruction differs')
            by_id[request_id] = dict(row, new_media_horizon=end > previous_end)
            previous_end, previous_id = end, request_id
        require(previous_end == duration * 16 and requests[-1]['controller_status'] == 'FINISHED', 'Final request did not reach real EOF')
        units = rolling['bounded_mt_units']
        check_count(rolling['bounded_mt_request_count'], len(units), 'bounded MT requests')
        check_mt(units, rolling['bounded_mt_failed_or_empty_count'], 'bounded MT')
        previews = 0
        sentences = 0
        corrections = 0
        unit_ids = set()
        corrected_ends = set()
        previous_preview = None
        previous_preview_end = None
        for unit in units:
            uid = integer(unit['unit_id'], 'Unit ID must be an integer')
            require(uid not in unit_ids, 'Duplicate unit ID')
            unit_ids.add(uid)
            source = by_id[unit['hypothesis_request_id']]
            require(unit['context_start_sample'] == source['context_start_sample'] and unit['observed_end_sample'] == source['observed_end_sample']
                    and unit['final_coverage'] is False and unit['consumes_controller_tail'] is False
                    and unit['cross_origin_overlap_possible'] is True, 'MT request changed source context or claimed ownership')
            raw = source['raw_text'].encode('utf-16-le')
            begin = integer(unit['raw_start_utf16'], 'Raw start must be an integer')
            end = integer(unit['raw_end_utf16'], 'Raw end must be an integer')
            require(0 <= begin < end <= len(raw) // 2 and raw[begin * 2:end * 2].decode('utf-16-le') == unit['input_text'],
                    'MT input is not an exact raw-source slice')
            preceding_preview_end = previous_preview_end
            require(unit['new_media_latency_sample'] is (source['new_media_horizon'] and not unit['is_same_end_correction']),
                    'Same-end compute is not a new media latency sample')
            if unit['type'] == 'REVISABLE_PREVIEW':
                previews += 1
                require(begin == 0 and end * 2 == len(raw) and unit['input_text'] == source['raw_text'], 'Preview is not the complete actual hypothesis')
                require(unit['supersedes_preview_unit_id'] == previous_preview, 'Preview supersession chain mismatch')
                previous_preview = uid
                previous_preview_end = unit['observed_end_sample']
            else:
                require(unit['type'] == 'STABLE_SENTENCE_CANDIDATE' and unit['input_text'][-1] in '。！？!?'
                        and unit['stable_lexical_right_context_code_points'] >= 1, 'Invalid stable sentence candidate')
                sentences += 1
            if unit['is_same_end_correction']:
                corrections += 1
                horizon = unit['observed_end_sample']
                require(unit['type'] == 'REVISABLE_PREVIEW' and source['kind'] == 'RECOVERY'
                        and unit['new_media_latency_sample'] is False and horizon == preceding_preview_end and horizon not in corrected_ends,
                        'Correction counted as new media latency or repeated')
                corrected_ends.add(horizon)
        check_count(rolling['preview_request_count'], previews, 'preview requests')
        check_count(rolling['stable_sentence_request_count'], sentences, 'sentence requests')
        check_count(rolling['same_end_correction_count'], corrections, 'corrections')
        revisions = rolling['candidate_revisions']
        check_count(rolling['candidate_revision_count'], len(revisions), 'revisions')
        require(all(row['unit_id'] in unit_ids for row in revisions), 'Revision refers to a missing unit')
        ceilings = rolling['quality_ceiling_groups']
        check_count(rolling['quality_ceiling_group_count'], len(ceilings), 'quality ceiling groups')
        for group in ceilings:
            require(group['purpose'] == 'QUALITY_CEILING_ONLY' and group['bounded_latency_unit'] is False and group['final_coverage'] is False,
                    'Closed group misrepresented as bounded/final caption')
        available = [row for row in ceilings if row['input_text'] is not None]
        unavailable = [row for row in ceilings if row['input_text'] is None]
        check_count(rolling['quality_ceiling_mt_request_count'], len(available), 'quality ceiling MT requests')
        check_count(rolling['quality_ceiling_unavailable_count'], len(unavailable), 'unavailable quality ceilings')
        require(all(row['status'] == 'unavailable_ambiguous_ownership' and 'mt_status' not in row and not row.get('target_text') for row in unavailable), 'Unavailable ceiling must not claim an actual MT result')
        check_mt(available, rolling['quality_ceiling_mt_failed_or_empty_count'], 'quality ceiling MT')
        summary.append({'fixture_id': fixture['fixture_id'], 'baseline_windows': count, 'asr_requests': len(requests),
                        'sentence_candidates': sentences, 'previews': previews, 'corrections': corrections})
    return {'collection_verified': True, 'semantic_accuracy_accepted': False, 'final_coverage_accepted': False,
            'hardware_performance_accepted': False, 'fixtures': summary}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--expected-app-sha256', required=True)
    parser.add_argument('--expected-test-sha256', required=True)
    args = parser.parse_args()
    try:
        result = validate_report(json.loads(args.report.read_text()), args.expected_app_sha256, args.expected_test_sha256)
    except (ValueError, KeyError, TypeError, UnicodeError) as failure:
        parser.exit(1, f'Rolling caption collection check failed: {failure}\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
