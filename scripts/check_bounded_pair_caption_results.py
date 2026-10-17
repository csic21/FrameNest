#!/usr/bin/env python3
"""Verify bounded-pair diagnostic provenance; MT failures remain measured outcomes."""
import argparse
import json
import math
import re
from pathlib import Path
from check_rolling_caption_runtime_results import ASSETS, SOURCE_SHA256, require


def duration(value):
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, 'Invalid measured duration')


def validate_report(report, app_sha, test_sha):
    require(all(re.fullmatch('[0-9a-f]{64}', x) for x in (app_sha,test_sha)), 'Expected APK hashes must be pinned')
    require(report['schema_version'] == 1 and report['experiment'] == 'bounded_pair_mt', 'Wrong diagnostic')
    require(report['status'] == 'collected' and report['collection_complete'] is True, 'Incomplete collection')
    require(report['app_apk_sha256'] == app_sha and report['test_apk_sha256'] == test_sha, 'Wrong retained APK pair')
    require(report['source_aar_sha256'] == SOURCE_SHA256, 'Wrong source runtime')
    require(report['runtime_identity'] == f'1.13.8-asr-no-tts-sha256-{SOURCE_SHA256}', 'Wrong runtime identity')
    for key in ('accuracy_accepted', 'hardware_performance_accepted', 'final_coverage_claimed'):
        require(report[key] is False, 'Collection cannot claim acceptance')
    require(report['semantic_acceptance_passed'] is None, 'Semantic review must remain separate')
    identities = [json.loads((ASSETS/'manifest.json').read_text()),
                  json.loads((ASSETS/'fixed-window-comparison.json').read_text())['emoji']]
    expected = {x['id']: x for x in identities}
    fixtures = report['fixtures']
    require(len(fixtures) == 2 and {x['fixture_id'] for x in fixtures} == set(expected), 'Missing or duplicate fixture')
    summary = []
    for fixture in fixtures:
        identity = expected[fixture['fixture_id']]
        end = identity['duration_ms']
        require(fixture['duration_ms'] == end and fixture['pcm'] == identity['pcm'], 'Changed fixture PCM')
        baseline = fixture['production_3s']
        require(baseline['status'] == 'collected' and baseline['audio_traversal_complete'] is True
                and baseline['processed_through_ms'] == end and baseline['window_ms'] == 3000, 'Incomplete baseline')
        rows = baseline['rows']
        require(len(rows) == (end + 2999)//3000, 'Missing baseline windows')
        baseline_issues = 0
        for i, row in enumerate(rows):
            require(row['source_unit_id'] == i and row['status'] == 'collected', 'Changed baseline ordering')
            require(row['window_start_ms'] == i*3000 and row['window_end_ms'] == min((i+1)*3000,end), 'Changed ASR grid')
            require(isinstance(row['text_src'], str) and isinstance(row['text_tgt'], str), 'Missing source/target')
            pcm_read = row['actual_pcm_read']
            require(pcm_read['actual_start_ms'] == max(0,row['window_start_ms']-750)
                    and pcm_read['actual_end_ms'] == min(end,row['window_end_ms']+750), 'Changed baseline PCM padding')
            calls = row['actual_mt_calls']
            if row['text_src'].strip():
                require(len(calls) == 1 and calls[0]['input_text'] == row['text_src'], 'Changed baseline MT input')
                call = calls[0]
                require(call['status'] in ('returned','empty_target','failed'), 'Unfinished baseline MT')
                if call['status'] != 'failed':
                    require(call['target_text'] == row['text_tgt'], 'Changed baseline MT output')
                    require(bool(row['text_tgt'].strip()) == (call['status'] == 'returned'), 'Mislabeled baseline output')
                baseline_issues += call['status'] != 'returned'
            else:
                require(not calls and not row['text_tgt'].strip(), 'Empty source unexpectedly translated')
        replay = fixture['bounded_pair_mt_replay']
        require(replay['status'] == 'collected' and replay['timing_scope'] == 'MT_ISOLATION_REPLAY_NOT_END_TO_END', 'Incorrect replay timing claim')
        units = replay['rows']; owned = []; candidate_issues = 0; merged = 0
        for i, unit in enumerate(units):
            ids = unit['source_unit_ids']
            require(unit['unit_id'] == i and len(ids) in (1,2), 'Invalid bounded unit')
            require(all(type(j) is int and 0 <= j < len(rows) for j in ids), 'Unknown baseline owner')
            require(ids == list(range(ids[0],ids[0]+len(ids))), 'Nonadjacent owners')
            originals = [rows[j] for j in ids]
            if len(ids) == 2:
                require(unit['reason'] == 'crossing_morpheme_pair', 'Pair lacks boundary reason')
                word = unit['crossing_morpheme']
                seam = len(originals[0]['text_src'].encode('utf-16-le'))//2
                raw = ''.join(x['text_src'] for x in originals).encode('utf-16-le')
                a,b = word['raw_start_utf16'],word['raw_end_utf16']
                require(type(a) is int and type(b) is int and 0 <= a < seam < b <= len(raw)//2, 'Morpheme does not cross seam')
                require(''.join(c for c in raw[a*2:b*2].decode('utf-16-le') if not c.isspace()) == word['surface'], 'Morpheme differs from source')
            require(unit['input_text'] == ''.join(x['text_src'] for x in originals), 'Text changed at ownership seam')
            require(unit['window_start_ms'] == originals[0]['window_start_ms']
                    and unit['window_end_ms'] == originals[-1]['window_end_ms']
                    and 0 < unit['window_end_ms']-unit['window_start_ms'] <= 6000, 'Changed or oversized owned window span')
            starts = [x['cue_start_ms'] for x in originals if x.get('cue_start_ms') is not None]
            ends = [x['cue_end_ms'] for x in originals if x.get('cue_end_ms') is not None]
            require(unit['source_start_ms'] == (min(starts) if starts else None)
                    and unit['source_end_ms'] == (max(ends) if ends else None), 'Changed original speech PTS')
            inspected = unit['inspected_source_unit_ids']
            require(inspected == list(range(ids[0], min(ids[0]+2,len(rows)))), 'Changed fixed lookahead')
            require(unit['required_window_horizon_ms'] == max(rows[j]['window_end_ms'] for j in inspected), 'Incorrect required window horizon')
            require(unit['required_audio_horizon_ms'] == max(rows[j]['actual_pcm_read']['actual_end_ms'] for j in inspected), 'Incorrect required audio horizon')
            constituents = unit['constituents']
            require(len(constituents) == len(ids), 'Missing ownership provenance')
            for j, item, original in zip(ids,constituents,originals):
                require(item['source_unit_id'] == j, 'Wrong constituent ID')
                for key in ('text_src','window_start_ms','window_end_ms','cue_start_ms','cue_end_ms'):
                    require(item.get(key) == original.get(key), 'Changed constituent source/PTS')
            status = unit['mt_status']
            require(status in ('returned','empty_target','failed','skipped_empty_source'), 'Unfinished candidate MT')
            if not unit['input_text'].strip():
                require(status == 'skipped_empty_source' and unit.get('target_text','') == '', 'Empty source misclassified')
            else:
                require(status != 'skipped_empty_source', 'Nonempty source skipped')
                duration(unit['mt_ms'])
                if status != 'failed':
                    require(isinstance(unit['target_text'],str) and bool(unit['target_text'].strip()) == (status=='returned'), 'Mislabeled candidate output')
                candidate_issues += status != 'returned'
            owned.extend(ids); merged += len(ids)==2
        require(owned == list(range(len(rows))), 'Missing, repeated, or reordered owned text')
        require(''.join(u['input_text'] for u in units).encode() == ''.join(r['text_src'] for r in rows).encode(), 'Raw UTF-8 not conserved')
        summary.append(dict(fixture_id=fixture['fixture_id'],baseline_windows=len(rows),candidate_units=len(units),merged_pairs=merged,
                            baseline_failed_or_empty=baseline_issues,candidate_failed_or_empty=candidate_issues,raw_source_bytes_conserved=True))
    return dict(collection_verified=True,semantic_accuracy_accepted=False,product_latency_accepted=False,release_approved=False,fixtures=summary)


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True)
    p.add_argument('--expected-app-sha256',required=True);p.add_argument('--expected-test-sha256',required=True)
    a=p.parse_args();print(json.dumps(validate_report(json.loads(a.report.read_text()),a.expected_app_sha256,a.expected_test_sha256),indent=2))
