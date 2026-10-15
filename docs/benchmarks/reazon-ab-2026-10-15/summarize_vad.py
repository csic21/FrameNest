import json,pathlib,statistics
P=pathlib.Path(__file__).resolve().parent
summaries=[];reference_intervals={}
for model in ['sensevoice','reazon']:
 d=json.loads((P/f'results/{model}-official-vad.json').read_text());assert len(d['runs'])==9
 out={k:v for k,v in d.items() if k!='runs'};out.update(samples={},passes=[])
 for r in d['runs']:
  rows=[{k:v for k,v in q.items() if k!='decode_seconds'} for q in r['rows']];fix=r['fixture']
  if fix in out['samples']:assert rows==out['samples'][fix]['rows'];assert r['gaps']==out['samples'][fix]['gaps']
  else:out['samples'][fix]=dict(rows=rows,gaps=r['gaps'])
  intervals=[(q['start_seconds'],q['end_seconds']) for q in rows]
  if fix in reference_intervals:assert intervals==reference_intervals[fix]
  else:reference_intervals[fix]=intervals
  assert abs(sum(b-a for a,b in intervals)+sum(g['end_seconds']-g['start_seconds'] for g in r['gaps'])-r['duration_seconds'])<1e-6
  for q in rows:
   ts=q['timestamps_relative_seconds'];assert len(ts)==len(q['tokens']);assert ts==sorted(ts);assert all(-.001<=v<=q['end_seconds']-q['start_seconds']+.1 for v in ts)
  out['passes'].append(dict({k:v for k,v in r.items() if k not in ['rows','gaps']},decode_seconds=[q['decode_seconds'] for q in r['rows']]))
 (P/f'results/{model}-official-vad-deduplicated.json').write_text(json.dumps(out,ensure_ascii=False,separators=(',',':')))
 summary=dict(model=model,load_seconds=d['load_seconds'],peak_rss_kib=max(r['peak_rss_kib'] for r in d['runs']),fixtures={})
 for f in ['emoji','kishida','kishida114']:
  rr=[r for r in d['runs'] if r['fixture']==f];summary['fixtures'][f]=dict(cer=rr[0]['cer_first114'],segments=len(rr[0]['rows']),max_segment_seconds=max(q['end_seconds']-q['start_seconds'] for q in rr[0]['rows']),selected_seconds=rr[0]['selected_seconds'],excluded_seconds=rr[0]['excluded_seconds'],median_vad_seconds=statistics.median(r['vad_compute_seconds'] for r in rr),median_asr_rtf=statistics.median(r['asr_rtf'] for r in rr),median_combined_rtf=statistics.median(r['combined_rtf'] for r in rr),first_segment_ready_input_seconds=rr[0]['rows'][0]['ready_input_seconds'],first_decode_seconds=rr[0]['rows'][0]['decode_seconds'])
 summaries.append(summary)
(P/'VAD-SUMMARY.json').write_text(json.dumps(summaries,indent=2));print(json.dumps(summaries,indent=2));print('PASS: 18 runs, identical intervals across models, deterministic outputs, exact selected+excluded sample coverage, valid token timestamps.')
