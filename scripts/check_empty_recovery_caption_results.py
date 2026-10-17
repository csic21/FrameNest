#!/usr/bin/env python3
"""Structural/provenance checks, never automatic semantic or release acceptance."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from check_bounded_pair_caption_results import duration
from check_rolling_caption_runtime_results import ASSETS, SOURCE_SHA256, require


def same_source(a, b):
    return all(a.get(k) == b.get(k) for k in ('text_src', 'cue_start_ms', 'cue_end_ms', 'blank_reason'))


def same_result(a, b):
    return same_source(a, b) and all(a.get(k) == b.get(k) for k in ('text_tgt', 'translation_retryable_failure'))


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def validate_fixture(f):
    end = f['duration_ms']
    baseline = f['production_3s']
    require(baseline['status'] == 'collected' and baseline['audio_traversal_complete'] is True, 'Incomplete baseline')
    require(baseline['processed_through_ms'] == end and baseline['window_ms'] == 3000, 'Changed baseline grid')
    require(baseline['empty_translation_recovery_enabled'] is False, 'Baseline recovery enabled')
    bs = baseline['rows']
    require(len(bs) == (end + 2999)//3000, 'Missing baseline rows')
    for i, b in enumerate(bs):
        require(b['source_unit_id'] == i and b['status'] == 'collected', 'Baseline order/status')
        require(b['window_start_ms'] == i*3000 and b['window_end_ms'] == min(end,(i+1)*3000), 'Baseline grid')
        p = b['actual_pcm_read']
        require(p['actual_start_ms'] == max(0,i*3000-750) and p['actual_end_ms'] == min(end,(i+1)*3000+750), 'Baseline PCM padding')
    c = f['empty_translation_recovery']
    require(c['status'] == 'collected' and c['empty_translation_recovery_enabled'] is True, 'Candidate incomplete/disabled')
    require(c['session_or_room_measured'] is False and c['rendering_measured'] is False, 'Scope overclaim')
    duration(c['wall_ms'])
    owned, completed, unresolved, texts = [], [], [], []
    asr_count = mt_count = pairs = 0
    per_source, successful_asr, observed_horizons = {}, {}, {}
    first_source = first_target = None
    for r in c['rows']:
        i = len(owned); b = bs[i]
        require(r['source_unit_id'] == i and r['status'] == 'collected', 'Candidate ordering')
        require(r['window_start_ms'] == b['window_start_ms'] and r['window_end_ms'] == b['window_end_ms'], 'Candidate grid')
        original, output = r['original_result'], r['output_result']
        require(original['processed_through_ms'] in (None, b['window_end_ms']), 'Normal span changed')
        require(same_result(original,b), 'Changed normal baseline result')
        calls = r['actual_mt_calls']; normal = [x for x in calls if x['phase']=='normal']; recovery = [x for x in calls if x['phase']=='recovery']
        require(len(normal)<=1 and len(recovery)<=1, 'Unbounded MT calls')
        for x in calls:
            require(x['phase'] in ('normal','recovery') and x['status'] in ('returned','empty_target','failed'), 'Invalid MT outcome')
            duration(x['wall_ms'])
            if x['status'] != 'failed':
                require(bool(x['target_text'].strip()) == (x['status']=='returned'), 'Incorrect MT status')
        require([(x['input_text'],x.get('target_text',''),x['status'],x.get('failure_class','')) for x in normal] ==
                [(x['input_text'],x.get('target_text',''),x['status'],x.get('failure_class','')) for x in b['actual_mt_calls']], 'Normal MT changed')
        actual_empty = len(normal)==1 and normal[0]['status']=='empty_target'
        require(r['actual_empty_mt'] == actual_empty, 'False empty MT trigger')
        eligible = actual_empty and bool(original['text_src'].strip()) and not original['text_tgt'].strip() and not original['translation_retryable_failure'] and original['blank_reason'] is None and b['window_end_ms']-b['window_start_ms']==3000 and i+1<len(bs)
        require(r['eligible_for_one_adjacent_recovery'] == eligible and r['recovery_attempted'] == eligible, 'Incorrect recovery eligibility')
        if r['recovery_attempted']:
            require(actual_empty and original['text_src'].strip() and not original['text_tgt'].strip() and
                    not original['translation_retryable_failure'] and original['blank_reason'] is None and i+1<len(bs), 'Ineligible recovery')
            require(r['granted_next_source_unit_id']==i+1 and r['granted_next_end_ms']==bs[i+1]['window_end_ms'], 'Nonadjacent recovery')
        else:
            require(not recovery and not r['recovery_succeeded'], 'Recovery without attempt')
        ids = [i]
        if r['recovery_succeeded']:
            ids.append(i+1); pairs += 1
            next_source = r['recovery']['next_source']
            require(same_source(next_source,bs[i+1]) and not next_source['text_tgt'].strip() and not next_source['translation_retryable_failure'], 'Changed next source')
            require(r['recovery']['result'] == output, 'Typed recovery result differs from output')
            source = b['text_src']+bs[i+1]['text_src']
            require(output['text_src']==source and output['text_tgt'].strip() and not output['translation_retryable_failure'], 'Invalid merged result')
            require(output['cue_start_ms']==b['cue_start_ms'] and output['cue_end_ms']==bs[i+1]['cue_end_ms'] and output['processed_through_ms']==bs[i+1]['window_end_ms'], 'Merged PTS/coverage changed')
            require(len(recovery)==1 and recovery[0]['input_text']==source and recovery[0]['target_text']==output['text_tgt'] and recovery[0]['status']=='returned', 'Missing actual merged MT')
            seam=len(b['text_src'].encode('utf-16-le'))//2
            crossings=r['recovery']['post_timing_crossing_morphemes']
            raw = source.encode('utf-16-le')
            require(crossings and all(type(x['raw_start_utf16']) is int and type(x['raw_end_utf16']) is int and 0<=x['raw_start_utf16']<seam<x['raw_end_utf16']<=len(raw)//2 for x in crossings), 'No crossing morphology witness')
            for crossing in crossings:
                surface = raw[crossing['raw_start_utf16']*2:crossing['raw_end_utf16']*2].decode('utf-16-le')
                require(''.join(x for x in surface if not x.isspace()) == crossing['surface'], 'Crossing surface differs from source')
            require(r['recovery']['canonical_source_utf8_bytes_equal'] is True and
                    r['recovery']['canonical_source_utf8_sha256'] == digest(source) == r['recovery']['recovered_source_utf8_sha256'], 'False pair byte conservation')
        else:
            require(output == original and r['recovery'] is None, 'Failed recovery changed source/result')
        require(r['output_source_unit_ids']==ids and r['output_window_end_ms']==bs[ids[-1]]['window_end_ms'], 'Wrong source ownership')
        resolved=not output['translation_retryable_failure'] and ((bool(output['text_src'].strip()) and bool(output['text_tgt'].strip())) or output['blank_reason']=='NearSilence')
        require(r['completed_source_unit_ids']==(ids if resolved else []) and r['unresolved_source_unit_ids']==([] if resolved else ids), 'False completed coverage')
        for phase in ('normal','recovery'):
            require(sum(x['phase']==phase for x in r['actual_asr_calls'])<=1, 'Unbounded ASR calls')
        for x in r['actual_asr_calls']:
            j=x['source_unit_id']; require(j in (i,i+1) and (j==i)==(x['phase']=='normal'), 'Wrong ASR ownership')
            require(x['status'] in ('returned','failed'), 'Unfinished ASR call')
            duration(x['wall_ms'])
            require(x['input_pcm_samples'] == bs[j]['actual_pcm_read']['samples'] and
                    x['input_pcm_s16le_sha256'] == bs[j]['actual_pcm_read']['s16le_sha256'], 'Changed candidate ASR PCM')
            per_source[str(j)]=per_source.get(str(j),0)+1
            if x['status']=='returned': successful_asr[j]=successful_asr.get(j,0)+1
        for x in r['actual_pcm_reads']:
            j=x['source_unit_id']; require(j in (i,i+1) and (j==i)==(x['phase']=='normal'), 'Wrong PCM ownership')
            require(x['status'] in ('returned','failed'), 'Unfinished PCM call')
            duration(x['wall_ms'])
            if x['status']=='returned':
                p=x['read']; require(p == bs[j]['actual_pcm_read'], 'Candidate PCM padding/bytes changed')
                observed_horizons[j] = p['actual_end_ms']
        require(not original['text_src'].strip() or successful_asr.get(i)==1, 'Normal source lacks actual candidate inference')
        require(r['normal_uses_candidate_cached_source'] == (bool(original['text_src'].strip()) and not any(x['phase']=='normal' for x in r['actual_asr_calls'])), 'False cached source claim')
        expected_horizon=max(observed_horizons.get(i,0), observed_horizons.get(i+1,0) if r['recovery_attempted'] else 0)
        require(r['required_audio_horizon_ms']==expected_horizon, 'Invalid media horizon')
        require(r['normal_required_audio_horizon_ms']==observed_horizons.get(i,0) and r['normal_required_window_horizon_ms']==b['window_end_ms'], 'Invalid normal horizon')
        require(r['required_window_horizon_ms']==bs[i+1 if r['recovery_attempted'] else i]['window_end_ms'], 'Invalid window horizon')
        source_events=r['source_events']
        require(len(source_events)==(1 if original['text_src'].strip() else 0), 'Changed source publication count')
        if source_events:
            source_event=source_events[0]
            require(same_source(source_event,original) and source_event['text_tgt']=='' and
                    source_event['source_unit_id']==i and source_event['processed_through_ms'] in (None,b['window_end_ms']), 'Changed source publication')
            require(b['window_start_ms']<=source_event['cue_start_ms']<source_event['cue_end_ms']<=b['window_end_ms'], 'Source escapes current span')
            duration(source_event['observed_wall_ms'])
            require(source_event['observed_wall_ms']<=r['normal_result_ready_wall_ms'] and
                    (not normal or source_event['observed_wall_ms']<=normal[0]['started_wall_ms']), 'Source was not first')
            if first_source is None: first_source=source_event
        if output['text_tgt'].strip():
            duration(r['target_ready_wall_ms'])
            require(r['target_ready_wall_ms']==r['recovery_returned_wall_ms' if r['recovery_succeeded'] else 'normal_result_ready_wall_ms'], 'Fabricated target timing')
            if first_target is None:
                first_target=dict(source_unit_ids=ids,text_src=output['text_src'],text_tgt=output['text_tgt'],observed_wall_ms=r['target_ready_wall_ms'],required_audio_horizon_ms=r['required_audio_horizon_ms'],required_window_horizon_ms=r['required_window_horizon_ms'])
        else: require(r['target_ready_wall_ms'] is None, 'Empty target got ready time')
        require(all(value is True for value in r['regression_checks'].values()), 'Reported regression failure')
        duration(r['request_wall_ms'])
        owned+=ids; completed+=r['completed_source_unit_ids']; unresolved+=r['unresolved_source_unit_ids'];texts.append(output['text_src'])
        asr_count+=len(r['actual_asr_calls']);mt_count+=len(calls)
    require(owned==list(range(len(bs)))==c['visited_source_unit_ids'], 'Missing/repeated source')
    require(c['completed_source_unit_ids']==completed and c['unresolved_source_unit_ids']==unresolved, 'Coverage summary mismatch')
    require(''.join(texts)==''.join(b['text_src'] for b in bs), 'Source bytes lost/duplicated')
    h=digest(''.join(texts)); conservation=c['source_conservation']
    require(conservation['baseline_sha256']==h==conservation['candidate_sha256'] and conservation['exact_utf8_bytes_equal'] is True and conservation['source_ids_exact_once'] is True, 'False conservation')
    require(c['asr_call_count']==asr_count and c['mt_call_count']==mt_count and c['recovered_pair_count']==pairs and c['normal_request_count']==len(c['rows']), 'Counter mismatch')
    require(c['asr_calls_per_source_unit']==per_source and all(n==1 for n in successful_asr.values()), 'Repeated successful ASR source inference')
    require(c['first_source']==first_source and c['first_target']==first_target, 'Incorrect first-source/target timing')
    require(c['maximum_observed_audio_horizon_ms']==max(observed_horizons.values(),default=0), 'Incorrect maximum horizon')
    require(c['post_timing_crossing_audit_excluded_from_wall'] is True, 'Post-run audit leaked into latency claim')
    duration(c['post_timing_crossing_audit_ms'])
    for key in ('process_rss_before_candidate_kib','process_rss_after_candidate_kib'):
        require(c[key] is None or (type(c[key]) is int and c[key]>0), 'Invalid process RSS snapshot')
    require(c['regression_checks_passed'] is True and c['audio_grid_traversal_complete'] is True, 'Candidate regression failure')
    return dict(fixture_id=f['fixture_id'],baseline_windows=len(bs),candidate_units=len(c['rows']),recovered_pairs=pairs,unresolved_sources=len(unresolved),asr_calls=asr_count,mt_calls=mt_count)


def validate_report(report,app_sha,test_sha):
    require(all(re.fullmatch('[0-9a-f]{64}',x) for x in (app_sha,test_sha)), 'Invalid APK pin')
    require(report['schema_version']==1 and report['experiment']=='empty_translation_recovery' and report['status']=='collected' and report['collection_complete'] is True, 'Incomplete/wrong diagnostic')
    require(report['app_apk_sha256']==app_sha and report['test_apk_sha256']==test_sha, 'Wrong APK pair')
    require(report['source_aar_sha256']==SOURCE_SHA256 and report['runtime_identity']==f'1.13.8-asr-no-tts-sha256-{SOURCE_SHA256}', 'Wrong runtime')
    require(all(report[k] is False for k in ('accuracy_accepted','hardware_performance_accepted','final_coverage_claimed')) and report['semantic_acceptance_passed'] is None, 'Premature acceptance')
    identities=[json.loads((ASSETS/'manifest.json').read_text()),json.loads((ASSETS/'fixed-window-comparison.json').read_text())['emoji']]
    expected={x['id']:x for x in identities}; fixtures=report['fixtures']
    require(len(fixtures)==2 and {f['fixture_id'] for f in fixtures}==set(expected), 'Changed fixtures')
    for f in fixtures:
        identity=expected[f['fixture_id']];require(f['duration_ms']==identity['duration_ms'] and f['pcm']==identity['pcm'], 'Changed PCM fixture')
    rows=[validate_fixture(f) for f in fixtures]
    require(report['regression_checks_passed'] is True, 'Global regression failure')
    for key,field in [('candidate_asr_call_count','asr_calls'),('candidate_mt_call_count','mt_calls'),('candidate_recovered_pair_count','recovered_pairs')]:
        require(report[key]==sum(r[field] for r in rows), 'Global counter mismatch')
    return dict(collection_verified=True,semantic_accuracy_accepted=False,product_latency_accepted=False,release_approved=False,fixtures=rows)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--expected-app-sha256',required=True);p.add_argument('--expected-test-sha256',required=True)
    a=p.parse_args();print(json.dumps(validate_report(json.loads(a.report.read_text()),a.expected_app_sha256,a.expected_test_sha256),indent=2))
