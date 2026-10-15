"""Controlled same-PCM/same-window offline ASR comparison. No production changes."""
import os,time,json,resource,platform,hashlib,unicodedata,re,sys
from pathlib import Path
import numpy as np,soundfile as sf,sherpa_onnx
P=Path(__file__).resolve().parent
name=sys.argv[1];profile='official-vad';cpus=sorted(os.sched_getaffinity(0))[:2];os.sched_setaffinity(0,cpus)
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
# Fixed v1.12.32 official example config; no tuning. Preserve final partial frame.
c=sherpa_onnx.VadModelConfig();c.silero_vad.model=str(m/'silero_vad.onnx');c.silero_vad.min_silence_duration=.25;c.sample_rate=16000
out['vad_config']=str(c);out['vad_model_sha256']=hashlib.sha256((m/'silero_vad.onnx').read_bytes()).hexdigest()
for run in range(3):
 for fixture in ['emoji','kishida','kishida114']:
  x,sr=sf.read(P/f'fixtures/{"kishida" if fixture=="kishida114" else fixture}.wav',dtype='float32')
  if fixture=='kishida114':x=x[:114*sr]
  dur=len(x)/sr;t=time.perf_counter();vad=sherpa_onnx.VoiceActivityDetector(c,buffer_size_in_seconds=100);vload=time.perf_counter()-t
  intervals=[];vtime=0.;chunk=c.silero_vad.window_size
  def drain(observed):
   while not vad.empty():
    v=vad.front;lo=max(0,int(v.start));hi=min(len(x),int(v.start)+len(v.samples));vad.pop()
    if hi>lo:intervals.append(dict(start=lo,end=hi,ready_input_seconds=observed))
  for i in range(0,len(x),chunk):
   b=x[i:i+chunk]
   if len(b)<chunk:b=np.pad(b,(0,chunk-len(b)))
   t=time.perf_counter();vad.accept_waveform(b);vtime+=time.perf_counter()-t;drain(min(i+chunk,len(x))/sr)
  t=time.perf_counter();vad.flush();vtime+=time.perf_counter()-t;drain(dur)
  gaps=[];end=0
  for q in intervals:
   assert q['start']>=end
   if q['start']>end:gaps.append(dict(start_seconds=end/sr,end_seconds=q['start']/sr,rms_dbfs=float(20*np.log10(np.sqrt(np.mean(x[end:q['start']]**2))+1e-9))))
   end=q['end']
  if end<len(x):gaps.append(dict(start_seconds=end/sr,end_seconds=dur,rms_dbfs=float(20*np.log10(np.sqrt(np.mean(x[end:]**2))+1e-9))))
  rows=[];t0=time.perf_counter()
  for q in intervals:
   lo,hi=q['start'],q['end'];t=time.perf_counter();s=rec.create_stream();s.accept_waveform(sr,x[lo:hi]);rec.decode_stream(s);r=s.result;dt=time.perf_counter()-t
   rows.append(dict(start_seconds=lo/sr,end_seconds=hi/sr,ready_input_seconds=q['ready_input_seconds'],text=r.text,tokens=list(r.tokens),timestamps_relative_seconds=list(r.timestamps),token_pts_seconds=[lo/sr+v for v in r.timestamps],decode_seconds=dt))
  dt=time.perf_counter()-t0;hyp=''.join(q['text'] for q in rows);selected=sum(q['end']-q['start'] for q in intervals)/sr
  out['runs'].append(dict(run=run,fixture=fixture,duration_seconds=dur,rows=rows,gaps=gaps,vad_load_seconds=vload,vad_compute_seconds=vtime,asr_compute_seconds=dt,asr_rtf=dt/dur,combined_rtf=(dt+vtime)/dur,selected_seconds=selected,excluded_seconds=dur-selected,complete_input_accounted=True,cer_first114=distance(norm(ref),norm(hyp))/len(norm(ref)) if fixture=='kishida114' else None,peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss))
  (P/f'results/{name}-official-vad.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(name,run,fixture,'segments',len(rows),'selected',round(selected,3),'vad',round(vtime,3),'RTF',round((dt+vtime)/dur,4),'CER',out['runs'][-1]['cer_first114'],flush=True)
