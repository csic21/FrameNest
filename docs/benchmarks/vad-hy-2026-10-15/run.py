import os,time,json,hashlib,subprocess,selectors,resource
from pathlib import Path
P=Path(__file__).parent.resolve();ROOT=P.parent;INPUT=ROOT/'reazon-ab/results/sensevoice-official-vad-deduplicated.json';MODEL=ROOT/'hymt-native-perf-audit/Hy-MT2-1.8B-Q4_K_M.gguf';BIN=P/'runtime-build/bin/llama-completion'
os.sched_setaffinity(0,{0,1})
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
assert sha(MODEL)=='dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'
data=json.loads(INPUT.read_text());out={'input_sha256':sha(INPUT),'input_commit':'8dc2130000cff5fd588db326f34022992ca706b2','model_sha256':sha(MODEL),'runtime_commit':'d81235049384534c167caea52b85a694f6103d14','runtime_binary_sha256':sha(BIN),'prompt_template':'将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n{source}','cpu_affinity':[0,1],'cpu_threads':2,'policy':'Every full-video VAD segment unchanged, including fragments/punctuation. No stitching/context wrapper, no FINAL status assigned, no app cache/coverage mutation.','status':'running','samples':{}}
for name in ['emoji','kishida']:
 sample=data['samples'][name];out['samples'][name]={'rows':[],'vad_excluded_gaps':sample['gaps'],'asr_passes':[p for p in data['passes'] if p['fixture']==name]}
 for i,row in enumerate(sample['rows']):
  d=P/name/f'{i:02}';d.mkdir(parents=True,exist_ok=True);prompt=d/'prompt.txt';prompt.write_text(out['prompt_template'].format(source=row['text']));cmd=[str(BIN),'-m',str(MODEL),'-ngl','0','-t','2','-tb','2','-c','1024','-n','256','--jinja','-st','--simple-io','--no-display-prompt','--color','off','--temp','0.7','--top-p','0.6','--top-k','20','--repeat-penalty','1.05','--seed','42','-f',str(prompt)]
  start=time.perf_counter();p=subprocess.Popen(cmd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE);sel=selectors.DefaultSelector();sel.register(p.stdout,selectors.EVENT_READ,'stdout');sel.register(p.stderr,selectors.EVENT_READ,'stderr');capture={'stdout':bytearray(),'stderr':bytearray()};first=None
  while sel.get_map():
   if time.perf_counter()-start>180:p.kill();raise RuntimeError('bounded MT timeout')
   for key,_ in sel.select(.05):
    b=os.read(key.fd,65536)
    if not b:sel.unregister(key.fileobj);continue
    capture[key.data]+=b
    if key.data=='stdout' and b.strip() and first is None:first=time.perf_counter()-start
  code=p.wait();wall=time.perf_counter()-start;stdout=capture['stdout'].decode();stderr=capture['stderr'].decode();(d/'stdout.txt').write_text(stdout);(d/'stderr.txt').write_text(stderr);result={**row,'index':i,'segment_duration_seconds':row['end_seconds']-row['start_seconds'],'translation':stdout.split(' [end of text]')[0].strip(),'exit_code':code,'eog':' [end of text]'in stdout,'mt_process_seconds':wall,'first_stdout_seconds':first,'command':cmd,'prompt':prompt.read_text(),'raw_stdout':stdout,'raw_stderr':stderr};out['samples'][name]['rows'].append(result);(P/'results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(name,i,row['text'],'=>',result['translation'],round(wall,3),flush=True)
out['status']='finished';out['child_process_high_water_rss_kib']=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss;(P/'results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
