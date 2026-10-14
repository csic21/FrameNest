"""Causal energy-pause experiment. Text/reference is never used to choose endpoints."""
import os,time,json,unicodedata,csv
from pathlib import Path
import numpy as np
import soundfile as sf
import sherpa_onnx
os.sched_setaffinity(0,{0,1});P=Path(__file__).parent
x,sr=sf.read(P/'kishida-114s.wav',dtype='float32');duration=len(x)/sr
FRAME=.02;N=320
energy=20*np.log10(np.sqrt(np.mean(x[:len(x)//N*N].reshape(-1,N)**2,axis=1))+1e-9)
with (P/'endpoint-energy.csv').open('w') as f:
 w=csv.writer(f);w.writerow(['start_seconds','end_seconds','rms_dbfs']);w.writerows((i*FRAME,(i+1)*FRAME,float(d)) for i,d in enumerate(energy))
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s).lower() if not c.isspace() and not unicodedata.category(c).startswith(('P','S')))
def dist(a,b):
 row=list(range(len(b)+1))
 for i,c in enumerate(a,1):
  z=[i]
  for j,d in enumerate(b,1):z.append(min(z[-1]+1,row[j]+1,row[j-1]+(c!=d)))
  row=z
 return row[-1]
ref=norm((P/'kishida-reference-114s.txt').read_text())
def endpoints(threshold):
 out=[];start=0;quiet=0;active=0
 for i,db in enumerate(energy):
  quiet=quiet+1 if db<threshold else 0
  if db>=threshold:active+=1
  elapsed=(i+1-start)*FRAME
  pause=elapsed>=2 and quiet>=16 and active>=10
  forced=elapsed>=9-1e-7
  if pause or forced:
   out.append({'start':start*FRAME,'end':(i+1)*FRAME,'kind':'energy_pause' if pause else 'forced_9s','quiet_tail_seconds':quiet*FRAME,'active_seconds':active*FRAME})
   start=i+1;quiet=0;active=0
 if start<len(energy):out.append({'start':start*FRAME,'end':duration,'kind':'eof','quiet_tail_seconds':quiet*FRAME,'active_seconds':active*FRAME})
 return out
profiles={str(v):endpoints(v) for v in [-38,-35,-41]}
t=time.perf_counter();rec=sherpa_onnx.OfflineRecognizer.from_sense_voice(str(P/'models/sensevoice/model.int8.onnx'),str(P/'models/sensevoice/tokens.txt'),num_threads=2,language='ja',use_itn=True);load=time.perf_counter()-t
out={'clip':'kishida-114s.wav','sha256':'ce2ddb47957ff9d515e2b9226e02f5ecab5fb311f0af1dbc487b6b7651db919f','duration_seconds':duration,'model':'SenseVoice int8 fixed app revision2365baeacb507f821a0c8120fcee3d484dba7a07','hardware':'cloud AMD EPYC9V74; process affinity CPUs0,1; runtime2threads','load_seconds':load,'endpoint_policy':{'frame_seconds':FRAME,'primary_threshold_dbfs':-38,'pause_confirmation_seconds':.32,'min_segment_seconds':2,'minimum_active_seconds':.2,'max_segment_seconds':9,'cut':'At end of confirmed quiet interval; all PCM retained in consecutive nonoverlap segments. No SRT/text input to segmentation.','sensitivity_thresholds_dbfs':[-35,-41]},'reference_caveat':'Edited community reference,436 normalizedchars; omits fillers. Not adjudicated verbatim gold.','profiles':{},'baseline_runs':[]}
def decode(lo,hi):
 t=time.perf_counter();s=rec.create_stream();s.accept_waveform(sr,x[round(lo*sr):round(hi*sr)]);rec.decode_stream(s);r=s.result
 return r,time.perf_counter()-t
for key,segments in profiles.items():
 runs=[]
 for run in range(3):
  t=time.perf_counter();rows=[];text=[];live_finish=0
  for seg in segments:
   r,dt=decode(seg['start'],seg['end']);live_finish=max(live_finish,seg['end'])+dt
   rows.append(dict(seg,text=r.text,decode_seconds=dt,nas_compute_elapsed_seconds=time.perf_counter()-t,simulated_live_output_seconds=live_finish,tokens=list(r.tokens),timestamps=list(r.timestamps)));text.append(r.text)
  elapsed=time.perf_counter()-t;joined=''.join(text)
  runs.append({'run':run,'compute_seconds':elapsed,'rtf':elapsed/duration,'CER':dist(ref,norm(joined))/len(ref),'segments':rows,'transcript':joined})
 out['profiles'][key]={'segment_count':len(segments),'forced_count':sum(s['kind']=='forced_9s' for s in segments),'pause_count':sum(s['kind']=='energy_pause' for s in segments),'runs':runs}
 (P/'endpoint-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(key,'done',flush=True)
for run in range(3):
 t=time.perf_counter();rows=[];text=[]
 for center in range(0,114,3):
  lo=max(0,center-.75);hi=min(114,center+3.75);r,dt=decode(lo,hi);toks=list(r.tokens);ts=list(r.timestamps);keep=[]
  for i,(tok,sec) in enumerate(zip(toks,ts)):
   end=ts[i+1] if i+1<len(ts) else sec
   if center<=lo+(sec+end)/2<min(center+3,114):keep.append(tok.replace('▁',' '))
  cue=''.join(keep).strip();text.append(cue);rows.append({'start':center,'end':min(center+3,114),'input_start':lo,'input_end':hi,'text':cue,'raw_text':r.text,'decode_seconds':dt})
 elapsed=time.perf_counter()-t;joined=''.join(text);out['baseline_runs'].append({'run':run,'compute_seconds':elapsed,'rtf':elapsed/duration,'CER':dist(ref,norm(joined))/len(ref),'segments':rows,'transcript':joined})
(P/'endpoint-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
# Actual paced replay of early endpoints; no synthetic MT. Endpoints were derived causally.
out['live_paced_probe']=[]
for run in range(2):
 start=time.perf_counter();rows=[]
 for seg in profiles['-38']:
  if seg['end']>20:break
  time.sleep(max(0,start+seg['end']-time.perf_counter()));r,dt=decode(seg['start'],seg['end'])
  rows.append(dict(seg,text=r.text,decode_seconds=dt,actual_wall_output_seconds=time.perf_counter()-start))
 out['live_paced_probe'].append({'run':run,'events':rows})
 (P/'endpoint-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print('live',run,'done',flush=True)
