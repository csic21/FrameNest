import json,hashlib
from pathlib import Path
P=Path(__file__).resolve().parent;B=P.parent/'reazon-ab'
rows=[]
for q in json.load(open(B/'model-files.json')):
 if q['file'].startswith('sensevoice/') or q['file']=='silero_vad.onnx':
  h=hashlib.file_digest((B/'models'/q['file']).open('rb'),'sha256').hexdigest();assert h==q['sha256'];rows.append(dict(q,verified_sha256=h))
old=json.load(open(B/'results/sensevoice-official-vad-deduplicated.json'));new=json.load(open(P/'results/sensevoice.json'))
windows={}
for f,prior in old['samples'].items():
 n=next(x for x in new['runs'] if x['fixture']==f)
 a=[(r['start_seconds'],r['end_seconds'],r['ready_input_seconds']) for r in prior['rows']];b=[(r['start_seconds'],r['end_seconds'],r['ready_input_seconds']) for r in n['rows']]
 assert a==b
 windows[f]=hashlib.sha256(json.dumps(a,separators=(',',':')).encode()).hexdigest()
assert old['cpus']==new['cpus'] and old['threads']==new['threads']
json.dump(dict(models=rows,window_hashes=windows,pcm_and_reference_manifest=json.load(open(B/'fixtures/manifest.json')),same_affinity_and_threads=True,old_script=str(B/'benchmark_vad.py'),new_script=str(P/'benchmark.py')),open(P/'CONTROL-VERIFICATION.json','w'),indent=2)
print('Verified same model/tokenizer/VAD bytes, exact windows and ready-input positions, threads/affinity. PCM checks enforced by benchmark script.')
