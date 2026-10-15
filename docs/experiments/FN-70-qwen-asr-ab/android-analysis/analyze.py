"""Read-only FN-74 ABBA artifact validator/comparator. No inference or app changes."""
import argparse,hashlib,json,math,re,statistics,unicodedata
from pathlib import Path
BACKENDS=('raw_native_f32le','production_sherpa_asr_engine')
VERSIONS=('1.12.32','1.13.8','1.13.8','1.12.32')
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s).lower() if not c.isspace() and unicodedata.category(c)[0] not in 'PS')
def distance(a,b):
 row=list(range(len(b)+1))
 for i,c in enumerate(a,1):
  z=[i]
  for j,d in enumerate(b,1):z.append(min(z[-1]+1,row[j]+1,row[j-1]+(c!=d)))
  row=z
 return row[-1]
def reference(path):
 b=Path(path).read_bytes()
 assert hashlib.sha256(b).hexdigest()=='ce02aff6fe86dc09f632f490786c4a159fc3387aaf0b10061018882a3ce56635','reference hash mismatch'
 s=re.sub('（[^）]*）','',''.join(''.join(q.splitlines()[2:]) for q in b.decode().strip().split('\n\n')[:8]));s=norm(s);assert len(s)==436;return s
def text(r):return r['text'] if r['backend']==BACKENDS[0] else r['result']['text']
def timing(r):return {k:r[k] for k in ('timestamps_relative_seconds','timestamps_absolute_seconds','durations_seconds')} if r['backend']==BACKENDS[0] else r['result']['words']
def pts_findings(r):
 findings=[];duration=r['end_seconds']-r['start_seconds']
 if r['backend']==BACKENDS[0]:
  ts=r['timestamps_relative_seconds']
  if len(ts)!=len(r['tokens']):findings.append('token_timestamp_count_mismatch')
  if any(t<0 or t>duration+.1 for t in ts):findings.append('relative_timestamp_outside_window')
  if any(b<a for a,b in zip(ts,ts[1:])):findings.append('nonmonotonic_onsets')
  if any(d<0 for d in r['durations_seconds']):findings.append('negative_duration')
 else:
  words=r['result']['words']
  if any(w['start_ms']<0 or w['end_ms']<w['start_ms'] or w['end_ms']>duration*1000+100 for w in words):findings.append('production_word_bounds_suspicious')
  if any(b['start_ms']<a['start_ms'] for a,b in zip(words,words[1:])):findings.append('nonmonotonic_word_starts')
 return findings
def finite(n):return isinstance(n,(float,int)) and not isinstance(n,bool) and math.isfinite(n)
def validate(x,manifest,manifest_hash,version):
 assert x['schema_version']==1 and x['status']=='passed','incomplete/failed report'
 assert x['runtime_expected']==version==x['runtime_actual_native'].removeprefix('v')==x['runtime_build_label'],'runtime mismatch'
 assert x['fixture_manifest_sha256']==manifest_hash and x['fixture_manifest']==manifest,'fixture manifest mismatch'
 for k in ('gap_coverage_accepted','accuracy_accepted','hardware_performance_accepted','translation_tested'):assert x[k] is False,k
 assert x['production_float_input_bit_identical_to_raw'] is True and x['production_float_roundtrip_max_abs_error']==0
 windows={(s['id'],r['index']):r for s in manifest['samples'] for r in s['rows']};n=len(windows)
 assert x['windows_per_pass']==n
 expected=[(b,p,s,i) for b in BACKENDS for p in range(3) for s,i in windows]
 actual=[(r['backend'],r['pass'],r['sample'],r['window_index']) for r in x['runs']]
 assert actual==expected,'missing, duplicate or reordered result rows'
 for r in x['runs']:
  w=windows[r['sample'],r['window_index']]
  for k in ('start_sample','end_sample','f32le_sha256'):assert r[k]==w[k],k
  assert abs(r['start_seconds']-w['start_sample']/16000)<1e-8
  assert abs(r['end_seconds']-w['end_sample']/16000)<1e-8
  assert r['ready_input_seconds_from_host_vad']==w['ready_input_seconds']
  assert finite(r['decode_ms']) and r['decode_ms']>=0
  assert isinstance(text(r),str)
  if r['backend']==BACKENDS[0]:
   ts=r['timestamps_relative_seconds'];ab=r['timestamps_absolute_seconds']
   assert len(ts)==len(ab) and all(finite(v) for v in ts+ab+r['durations_seconds'])
   assert all(abs(a-r['start_seconds']-t)<1e-5 for a,t in zip(ab,ts)),'absolute timestamp offset mismatch'
  else:
   assert r['text_equals_same_pass_raw'] is True
   for w in r['result']['words']:assert isinstance(w['text'],str) and finite(w['start_ms']) and finite(w['end_ms'])
 indexed={(r['backend'],r['pass'],r['sample'],r['window_index']):r for r in x['runs']}
 for p in range(3):
  for s,i in windows:assert text(indexed[BACKENDS[0],p,s,i]).strip()==text(indexed[BACKENDS[1],p,s,i]),'adapter raw equality flag contradicts text'
 lc=x['lifecycle'];assert lc['raw_recognizer_created']==lc['raw_recognizer_released']==1
 assert lc['raw_stream_created']==lc['raw_stream_released']==n*3 and x['raw_stream_counts_balanced'] is True
 assert lc['production_ensure_calls']==3 and lc['production_close_calls']==3 and lc['production_recognize_calls']==n*3+3
 assert lc['production_native_allocations_observable'] is False and x['production_close_reload_result_equal'] is True
 c=x['cancellation_probe'];assert c['pre_dispatch_cancel_skipped_body'] is True and c['native_decode_interrupted_proven'] is False
 if c['cancellation_observed_by_caller']:assert c['result_publication_suppressed'] is True
 return indexed

def summarize(x,manifest,ref):
 out={}
 for b in BACKENDS:
  out[b]={}
  for s in manifest['samples']:
   passes=[]
   for p in range(3):
    rows=[r for r in x['runs'] if r['backend']==b and r['sample']==s['id'] and r['pass']==p]
    hyp=''.join(text(r) for r in rows);ms=sum(r['decode_ms'] for r in rows)
    selected=sum(r['end_sample']-r['start_sample'] for r in rows)/16000
    nonempty=next((r for r in rows if text(r).strip()),None)
    passes.append(dict(pass_index=p,text=hyp,cer_edited_reference=distance(ref,norm(hyp))/len(ref) if s['id']=='kishida114' else None,decode_ms=ms,rtf_full_extent=ms/1000/(s['extent_samples']/16000),rtf_selected_speech=ms/1000/selected,selected_seconds=selected,excluded_seconds=s['extent_samples']/16000-selected,first_nonempty_decode_ms=nonempty['decode_ms'] if nonempty else None,first_nonempty_vad_ready_seconds=nonempty['ready_input_seconds_from_host_vad'] if nonempty else None))
   out[b][s['id']]={'passes':passes,'text_stable_across_passes':len({p['text'] for p in passes})==1,'warm_rtf_full_extent_median':statistics.median(p['rtf_full_extent'] for p in passes[1:])}
 return out

def compare(a,b):
 changed=[]
 for k,r in a.items():
  q=b[k];txt=text(r)!=text(q);pts=timing(r)!=timing(q)
  if txt or pts:changed.append(dict(backend=k[0],pass_index=k[1],sample=k[2],window_index=k[3],text_changed=txt,timing_changed=pts,before_text=text(r),after_text=text(q),before_timing=timing(r),after_timing=timing(q)))
 return changed

def analyze(paths,manifest_path,reference_path,python_paths=()):
 mb=Path(manifest_path).read_bytes();m=json.loads(mb);mh=hashlib.sha256(mb).hexdigest();ref=reference(reference_path)
 xs=[json.loads(Path(p).read_text()) for p in paths];assert len(xs)==4
 idx=[validate(x,m,mh,v) for x,v in zip(xs,VERSIONS)]
 for key in ('api','abi','device_model','device_manufacturer'):assert len({x[key] for x in xs})==1,'target changed: '+key
 assert xs[0]['apk_sha256']==xs[3]['apk_sha256'] and xs[1]['apk_sha256']==xs[2]['apk_sha256'],'within-version APK changed'
 result={'artifact_status':'validated_actual_inputs','source_files':[str(p) for p in paths],'sequence':list(VERSIONS),'fixture_manifest_sha256':mh,'limitations':['33 windows per pass are NOT 33 independent samples: Kishida114 overlaps full Kishida; report each sample separately.','Raw precedes production and staged model bytes warm filesystem cache. Compare same backend only; no disk-cold claim.','RTF is Android target wall time, not OPPO, streamed playback, or bilingual latency.','Edited Commons reference omits fillers; CER only Kishida114; no Emoji CER.','Lifecycle counts prove raw balance and API calls only; production native allocations unobserved.','Cancellation tests caller publication/reuse, not JNI interruption, seek, or player lifecycle.','VAD gaps remain excluded and unrecognized.'],'reports':[],'comparisons':{}}
 for p,x in zip(paths,xs):
  result['reports'].append({'file':str(p),'runtime':x['runtime_actual_native'],'apk_sha256':x['apk_sha256'],'native_git_sha1':x['native_git_sha1'],'summary':summarize(x,m,ref),'preparation':{k:v for k,v in x.items() if 'preparation' in k or 'warm_ensure' in k or 'asset_copy' in k},'timestamp_findings':[{'backend':r['backend'],'sample':r['sample'],'pass_index':r['pass'],'window_index':r['window_index'],'findings':pts_findings(r)} for r in x['runs'] if pts_findings(r)],'lifecycle':x['lifecycle'],'cancellation_probe':x['cancellation_probe']})
 for name,i,j in [('forward_baseline_candidate',0,1),('reverse_baseline_candidate',3,2),('baseline_repeat',0,3),('candidate_repeat',1,2)]:
  result['comparisons'][name]={'from_report':i,'to_report':j,'changed_rows':compare(idx[i],idx[j]),'same_backend_sample_pass_timing':[{ 'backend':b,'sample':s['id'],'pass_index':p,'before_decode_ms':sum(r['decode_ms'] for k,r in idx[i].items() if k[:3]==(b,p,s['id'])),'after_decode_ms':sum(r['decode_ms'] for k,r in idx[j].items() if k[:3]==(b,p,s['id']))} for b in BACKENDS for s in m['samples'] for p in range(3)]}
 if python_paths:
  py={json.loads(Path(p).read_text())['runtime']:json.loads(Path(p).read_text()) for p in python_paths}
  result['python_text_comparison']=[]
  for report_i,x in enumerate(xs):
   v=x['runtime_expected']
   if v not in py:continue
   for b in BACKENDS:
    for run in py[v]['runs']:
     ar=[r for r in x['runs'] if r['backend']==b and r['pass']==run['run'] and r['sample']==run['fixture']]
     assert len(ar)==len(run['rows'])
     for android,host in zip(ar,run['rows']):
      assert abs(android['start_seconds']-host['start_seconds'])<1e-8 and abs(android['end_seconds']-host['end_seconds'])<1e-8
      if text(android)!=host['text']:result['python_text_comparison'].append({'report_index':report_i,'backend':b,'sample':run['fixture'],'pass_index':run['run'],'window_index':android['window_index'],'android':text(android),'python':host['text']})
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('reports',nargs=4);p.add_argument('--manifest',required=True);p.add_argument('--reference',required=True);p.add_argument('--python-results',nargs='*',default=[]);p.add_argument('--output',required=True);a=p.parse_args();r=analyze(a.reports,a.manifest,a.reference,a.python_results);Path(a.output).write_text(json.dumps(r,ensure_ascii=False,indent=2));print('Validated four artifacts; wrote',a.output)
