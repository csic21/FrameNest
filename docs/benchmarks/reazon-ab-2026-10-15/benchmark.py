"""Controlled same-PCM/same-window offline ASR comparison. No production changes."""
import os,time,json,resource,platform,hashlib,unicodedata,re,sys
from pathlib import Path
import numpy as np,soundfile as sf,sherpa_onnx
P=Path(__file__).resolve().parent
name=sys.argv[1];profile=sys.argv[2] if len(sys.argv)>2 else 'fixed15';cpus=sorted(os.sched_getaffinity(0))[:2];os.sched_setaffinity(0,cpus)
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s).lower() if not c.isspace() and unicodedata.category(c)[0] not in 'PS')
def distance(a,b):
 row=list(range(len(b)+1))
 for i,c in enumerate(a,1):
  z=[i]
  for j,d in enumerate(b,1):z.append(min(z[-1]+1,row[j]+1,row[j-1]+(c!=d)))
  row=z
 return row[-1]
ref=re.sub('（[^）]*）','',''.join(''.join(b.splitlines()[2:]) for b in (P/'fixtures/kishida-reference.srt').read_text().strip().split('\n\n')[:8]));assert len(norm(ref))==436
started=time.perf_counter();m=P/'models'
if name=='sensevoice':rec=sherpa_onnx.OfflineRecognizer.from_sense_voice(str(m/'sensevoice/model.int8.onnx'),str(m/'sensevoice/tokens.txt'),num_threads=2,language='ja',use_itn=True)
else:rec=sherpa_onnx.OfflineRecognizer.from_transducer(encoder=str(m/'encoder-epoch-99-avg-1.int8.onnx'),decoder=str(m/'decoder-epoch-99-avg-1.onnx'),joiner=str(m/'joiner-epoch-99-avg-1.int8.onnx'),tokens=str(m/'tokens.txt'),num_threads=2,model_type='transducer',decoding_method='greedy_search')
load=time.perf_counter()-started
out=dict(model=name,profile=profile,runtime=sherpa_onnx.__version__,cpus=cpus,threads=2,host=platform.platform(),load_seconds=load,runs=[],reference_chars=len(norm(ref)),reference_caveat='Independent edited Commons captions, omitted fillers; not verbatim gold. NFKC lowercase, remove whitespace and Unicode P/S only. First 8 cues; parenthesized unspoken question removed.')
for run in range(3):
 for fixture in ['emoji','kishida']:
  x,sr=sf.read(P/f'fixtures/{fixture}.wav',dtype='float32');dur=len(x)/sr
  # Explicit boundary at 114 seconds for independent reference scoring.
  bounds=sorted(set([0.,dur]+[float(i) for i in range(15,int(dur),15)]+([114.] if fixture=='kishida' else [])))
  if profile=='fixed3': bounds=sorted(set([0.,dur]+[float(i) for i in range(3,int(dur),3)]))
  elif profile=='oracle': bounds=([0.,12.,36.,49.,63.,76.,90.,97.,114.] if fixture=='kishida' else [0.,5.41,9.65,17.08,28.30,42.22]);dur=bounds[-1]
  rows=[];t0=time.perf_counter()
  for lo,hi in zip(bounds,bounds[1:]):
   t=time.perf_counter();s=rec.create_stream();s.accept_waveform(sr,x[round(lo*sr):round(hi*sr)]);rec.decode_stream(s);r=s.result;elapsed=time.perf_counter()-t
   rows.append(dict(start_seconds=lo,end_seconds=hi,text=r.text,tokens=list(r.tokens),timestamps_relative_seconds=list(r.timestamps),decode_seconds=elapsed,token_pts_seconds=[lo+v for v in r.timestamps]))
  dt=time.perf_counter()-t0;scored=''.join(r['text'] for r in rows if r['end_seconds']<=114)
  result=dict(run=run,fixture=fixture,duration_seconds=dur,compute_seconds=dt,rtf=dt/dur,first_text_decode_seconds=rows[0]['decode_seconds'],first_text_with_load_seconds=load+rows[0]['decode_seconds'] if run==0 and fixture=='emoji' else None,rows=rows,cer_first114=distance(norm(ref),norm(scored))/len(norm(ref)) if fixture=='kishida' else None,peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
  out['runs'].append(result);(P/f'results/{name}-{profile}.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(name,run,fixture,'RTF',round(dt/dur,4),'CER',result['cer_first114'],'RSS',result['peak_rss_kib'],flush=True)
