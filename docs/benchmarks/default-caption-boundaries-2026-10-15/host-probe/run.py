from pathlib import Path
import json,time,hashlib,os,base64,sherpa_onnx,soundfile as sf
B=Path('/workspace/scratch/46a1f16e529d/reazon-ab'); O=Path('/tmp/fn-default-boundary-audit')
cpus=sorted(os.sched_getaffinity(0))[:2];os.sched_setaffinity(0,cpus)
for n,h in [('model.int8.onnx','c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51'),('tokens.txt','f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc')]:
 assert hashlib.file_digest((B/'models/sensevoice'/n).open('rb'),'sha256').hexdigest()==h
manifest=json.loads((B/'fixtures/manifest.json').read_text())
for f in manifest:
 assert hashlib.file_digest((B/'fixtures'/f['file']).open('rb'),'sha256').hexdigest()==f['sha256']
rec=sherpa_onnx.OfflineRecognizer.from_sense_voice(str(B/'models/sensevoice/model.int8.onnx'),str(B/'models/sensevoice/tokens.txt'),num_threads=2,language='ja',use_itn=True)
rows=[];lines=[]
for fixture,limit in [('emoji',42000),('kishida',18000)]:
 x,sr=sf.read(B/f'fixtures/{fixture}.wav',dtype='float32');assert sr==16000
 for start in range(0,limit,3000):
  end=start+3000;lo=max(0,start-750);hi=end+750;s=rec.create_stream();s.accept_waveform(sr,x[lo*16:hi*16]);t=time.perf_counter();rec.decode_stream(s);r=s.result
  rows.append(dict(fixture=fixture,start_ms=start,end_ms=end,decode_start_ms=lo,decode_end_ms=hi,text=r.text,tokens=list(r.tokens),timestamps=list(r.timestamps),decode_seconds=time.perf_counter()-t))
  line=[fixture,str(start),str(end),str(lo)]
  for token,pts in zip(r.tokens,r.timestamps):line += [base64.b64encode(token.encode()).decode(),str(pts)]
  lines.append('\t'.join(line))
O.joinpath('raw.json').write_text(json.dumps(dict(runtime=sherpa_onnx.__version__,cpus=cpus,threads=2,scope='Host stock1.13.8, same pinned production model and default3s plus750ms decode schedule; no Android or MT run, no listening accuracy claim',rows=rows),ensure_ascii=False,indent=2))
O.joinpath('raw.tsv').write_text('\n'.join(lines)+'\n')
print('runtime',sherpa_onnx.__version__,'rows',len(rows),flush=True)
