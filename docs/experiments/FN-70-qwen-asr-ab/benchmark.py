"""Fixed official VAD-window Qwen/SenseVoice comparison, no production mutations."""
import os,time,json,resource,platform,hashlib,unicodedata,re,sys
from pathlib import Path
import numpy as np,soundfile as sf,sherpa_onnx
P=Path(__file__).resolve().parent;B=P.parent/'reazon-ab';name=sys.argv[1];label=os.environ.get('RESULT_LABEL',name)
cpus=sorted(os.sched_getaffinity(0))[:2];os.sched_setaffinity(0,cpus)
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s).lower() if not c.isspace() and unicodedata.category(c)[0] not in 'PS')
def distance(a,b):
 row=list(range(len(b)+1))
 for i,c in enumerate(a,1):
  z=[i]
  for j,d in enumerate(b,1):z.append(min(z[-1]+1,row[j]+1,row[j-1]+(c!=d)))
  row=z
 return row[-1]
ref=re.sub('（[^）]*）','',''.join(''.join(b.splitlines()[2:]) for b in (B/'fixtures/kishida-reference.srt').read_text().strip().split('\n\n')[:8]));assert len(norm(ref))==436
for f in json.loads((B/'fixtures/manifest.json').read_text()):
 p=B/'fixtures'/f['file'];assert hashlib.file_digest(p.open('rb'),'sha256').hexdigest()==f['sha256']
source=B/'results/sensevoice-official-vad-deduplicated.json';windows=json.loads(source.read_text())
started=time.perf_counter()
if name=='sensevoice':rec=sherpa_onnx.OfflineRecognizer.from_sense_voice(str(B/'models/sensevoice/model.int8.onnx'),str(B/'models/sensevoice/tokens.txt'),num_threads=2,language='ja',use_itn=True)
else:
 m=P/'models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25'
 rec=sherpa_onnx.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(m/'conv_frontend.onnx'),encoder=str(m/'encoder.int8.onnx'),decoder=str(m/'decoder.int8.onnx'),tokenizer=str(m/'tokenizer'),num_threads=2)
load=time.perf_counter()-started
out=dict(model=name,runtime=sherpa_onnx.__version__,cpus=cpus,threads=2,host=platform.platform(),load_seconds=load,config=str(rec.config),reference_chars=436,language="Japanese" if name!="sensevoice" else "ja",reference_caveat=windows['reference_caveat'],window_source_sha256=hashlib.file_digest(source.open('rb'),'sha256').hexdigest(),vad_config=windows['vad_config'],runs=[])
(P/'results').mkdir(exist_ok=True)
for run in range(3):
 for fixture in ['emoji','kishida','kishida114']:
  x,sr=sf.read(B/f'fixtures/{"kishida" if fixture=="kishida114" else fixture}.wav',dtype='float32')
  if fixture=='kishida114':x=x[:114*sr]
  rows=[];t0=time.perf_counter()
  for q in windows['samples'][fixture]['rows']:
   lo=round(q['start_seconds']*sr);hi=round(q['end_seconds']*sr);t=time.perf_counter();s=rec.create_stream();s.set_option('language','Japanese') if name!='sensevoice' else None;s.accept_waveform(sr,x[lo:hi]);rec.decode_stream(s);r=s.result;dt=time.perf_counter()-t
   row=dict(start_seconds=lo/sr,end_seconds=hi/sr,ready_input_seconds=q['ready_input_seconds'],text=r.text,tokens=list(r.tokens),timestamps_relative_seconds=list(r.timestamps),decode_seconds=dt)
   rows.append(row)
   print(name,run,fixture,len(rows),round(dt,3),r.text,flush=True)
   (P/'results'/f'{label}-partial.json').write_text(json.dumps(dict(out,current_fixture=fixture,current_run=run,current_rows=rows),ensure_ascii=False,indent=2))
  dt=time.perf_counter()-t0;hyp=''.join(q['text'] for q in rows);first=next((q for q in rows if q['text']),None)
  out['runs'].append(dict(run=run,fixture=fixture,duration_seconds=len(x)/sr,rows=rows,gaps=windows['samples'][fixture]['gaps'],asr_seconds=dt,asr_rtf=dt/(len(x)/sr),first_nonempty_decode_seconds=first['decode_seconds'] if first else None,first_nonempty_input_ready_seconds=first['ready_input_seconds'] if first else None,cer_first114=distance(norm(ref),norm(hyp))/436 if fixture=='kishida114' else None,peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss))
  (P/'results'/f'{label}.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
