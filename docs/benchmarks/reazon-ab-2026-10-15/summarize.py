import json,pathlib,statistics,hashlib
P=pathlib.Path(__file__).resolve().parent;summary=[]
for model in ['sensevoice','reazon']:
 for profile in ['fixed15','fixed3','oracle']:
  f=P/'results'/f'{model}{"" if profile=="fixed15" else "-"+profile}.json';f=f if f.exists() else P/'results'/f'{model}-{profile}.json';d=json.loads(f.read_text());record=dict(model=model,profile=profile,load_seconds=d['load_seconds'],runtime=d['runtime'],cpus=d['cpus'],threads=d['threads'],reference_caveat=d['reference_caveat'],samples={},passes=[])
  for r in d['runs']:
   rows=[{k:v for k,v in q.items() if k!='decode_seconds'} for q in r['rows']]
   if r['fixture'] in record['samples']:assert rows==record['samples'][r['fixture']], 'Nondeterministic raw outputs'
   else:record['samples'][r['fixture']]=rows
   for q in rows:
    ts=q['timestamps_relative_seconds'];assert len(ts)==len(q['tokens']);assert ts==sorted(ts);assert all(-.001<=v<=q['end_seconds']-q['start_seconds']+.1 for v in ts)
   record['passes'].append(dict({k:v for k,v in r.items() if k!='rows'},decode_seconds=[q['decode_seconds'] for q in r['rows']]))
  (P/'results'/f'{model}-{profile}-deduplicated.json').write_text(json.dumps(record,ensure_ascii=False,separators=(',',':')))
  summary.append(dict(model=model,profile=profile,load_seconds=d['load_seconds'],peak_rss_kib=max(r['peak_rss_kib'] for r in d['runs']),fixtures={fixture:dict(median_rtf=statistics.median(r['rtf'] for r in d['runs'] if r['fixture']==fixture),first_decode_seconds=next(r['first_text_decode_seconds'] for r in d['runs'] if r['fixture']==fixture),cer_first114=next(r['cer_first114'] for r in d['runs'] if r['fixture']==fixture)) for fixture in ['emoji','kishida']}))
(P/'SUMMARY.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print('Verified deterministic raw outputs for 36 fixture passes; token counts, monotonicity and range pass.')
