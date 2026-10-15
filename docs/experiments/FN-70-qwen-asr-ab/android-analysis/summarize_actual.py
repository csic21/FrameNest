"""Condense validated FN-74 actual artifacts without pooling overlapping samples."""
import json,hashlib,statistics,difflib,sys
from pathlib import Path
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
analysis=json.load(open(root/'android-analysis.json'));paths=[root/'extracted'/f for f in ['1-baseline.json','2-candidate.json','3-candidate.json','4-baseline.json']];xs=[json.load(open(p)) for p in paths]
s={'workflow_run':38718249045,'job':116366132840,'artifact_id':11964549455,'artifact_zip_sha256':'e858a2ccb5c805e6437229defb81e8d0a53c2706556156bb49545ad0c3743617','workflow_head':'de5377661893b74e1f4c3d42188d87ddaae78bec','tested_checkout':'6c1bdb29f2dbfe890963560985155ae0baf8dc22','checkout_note':'Engineering owner verified merge checkout differs from workflow head only in .github/release-request.json; app/test/script/workflow identical.','target':{k:xs[0][k] for k in ['api','abi','device_model','device_manufacturer']},'source_reports':[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths],'reports':[],'limitations':analysis['limitations'],'same_version_text_and_pts_stable':all(not analysis['comparisons'][n]['changed_rows'] for n in ['baseline_repeat','candidate_repeat']),'cross_runtime_token_onsets':{},'python_differences':{},'production_warm_rtf':{}}
for i,(x,r) in enumerate(zip(xs,analysis['reports'])):
 q={k:x[k] for k in ['runtime_actual_native','apk_sha256','native_git_sha1','fixture_manifest_sha256','raw_full_config','production_config']};q.update({'report_index':i,'timestamp_findings':r['timestamp_findings'],'preparation':r['preparation'],'lifecycle':r['lifecycle'],'cancellation_probe':r['cancellation_probe'],'summaries':{}})
 for b,samples in r['summary'].items():
  q['summaries'][b]={k:{'text_stable_across_passes':v['text_stable_across_passes'],'warm_rtf_full_extent_median':v['warm_rtf_full_extent_median'],'passes':[{k:w for k,w in p.items() if k!='text'} for p in v['passes']]} for k,v in samples.items()}
 s['reports'].append(q)
for sample in ['emoji','kishida','kishida114']:
 ar=[r for r in xs[0]['runs'] if r['backend']=='raw_native_f32le' and r['pass']==0 and r['sample']==sample];br=[r for r in xs[1]['runs'] if r['backend']=='raw_native_f32le' and r['pass']==0 and r['sample']==sample];d=[]
 for a,b in zip(ar,br):
  for match in difflib.SequenceMatcher(None,a['tokens'],b['tokens'],autojunk=False).get_matching_blocks():
   d.extend(b['timestamps_relative_seconds'][match.b+j]-a['timestamps_relative_seconds'][match.a+j] for j in range(match.size))
 s['cross_runtime_token_onsets'][sample]={'method':'SequenceMatcher exact token matching within same window, autojunk=False; descriptive alignment only, not ground-truth timing error','matched_tokens':len(d),'minimum_delta_seconds':min(d),'median_delta_seconds':statistics.median(d),'maximum_delta_seconds':max(d),'median_absolute_delta_seconds':statistics.median(abs(t) for t in d)}
 s['production_warm_rtf'][sample]={}
 for version,indices in [('baseline',[0,3]),('candidate',[1,2])]:
  vals=[p['rtf_full_extent'] for i in indices for p in analysis['reports'][i]['summary']['production_sherpa_asr_engine'][sample]['passes'][1:]]
  s['production_warm_rtf'][sample][version]={'median':statistics.median(vals),'min':min(vals),'max':max(vals),'count':len(vals),'note':'4 warm passes, 2 runs; descriptive, not 4 independent devices'}
 old=s['production_warm_rtf'][sample]['baseline']['median'];new=s['production_warm_rtf'][sample]['candidate']['median'];s['production_warm_rtf'][sample]['candidate_relative_change']=(new/old-1)
for report_i in [0,1]:
 s['python_differences'][str(report_i)]={}
 for sample in ['emoji','kishida','kishida114']:
  ds=[d for d in analysis['python_text_comparison'] if d['report_index']==report_i and d['backend']=='raw_native_f32le' and d['pass_index']==0 and d['sample']==sample];s['python_differences'][str(report_i)][sample]=ds
(out/'ACTUAL-SUMMARY.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
lines=['# Full Android initial-pass raw text','', 'Public fixture ASR adaptations; not official transcripts. Each version repeated exactly across same-version ABBA runs and all passes; production text matched trimmed raw text. VAD envelopes are not word alignment. See ACTUAL-REVIEW.md for attribution.','']
for i in [0,1]:
 lines+=['## '+xs[i]['runtime_actual_native']]
 for sample in ['emoji','kishida','kishida114']:
  lines+=['### '+sample]
  lines += [f"{r['window_index']} {r['start_seconds']:.3f}–{r['end_seconds']:.3f}: {r['text']}" for r in xs[i]['runs'] if r['backend']=='raw_native_f32le' and r['pass']==0 and r['sample']==sample]
(out/'ACTUAL-RAW-TEXT.md').write_text('\n'.join(lines))
print(json.dumps(s['production_warm_rtf'],indent=2))
