#!/usr/bin/env python3
"""No listener. Fresh bounded official llama-completion process for each case."""
import argparse
import codecs
import datetime
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import threading
import time
from benchmark_hy import ROOT, MODEL, FIXTURE, EXPECTED_SHA, PROMPT, sha256, write_json, proc_state

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--threads',type=int,default=2)
    ap.add_argument('--prompt-mode',choices=['official','fragment_guard'],default='official')
    ap.add_argument('--cases',nargs='+',default=['asr_27000_42000','language_entities','emoji_answer'])
    args=ap.parse_args()
    runtime=ROOT/'runtime/llama-b11429/llama-completion'
    suffix='' if args.prompt_mode=='official' else '_fragment_guard'
    out=ROOT/'results'/f'hy_cli_cpu{args.threads}{suffix}';out.mkdir(parents=True,exist_ok=True)
    prompt_template=PROMPT if args.prompt_mode=='official' else '将下面日语翻译成简体中文，只输出译文。输入可能是未完成的口语片段；只翻译已有内容，不推测或补写缺失词句，不添加解释；原文未完成时译文也保持未完成。\n{source}'
    assert MODEL.stat().st_size==1133080448 and sha256(MODEL)==EXPECTED_SHA
    f=json.loads(FIXTURE.read_text());allcases={x['id']:dict(x,group='authored_control') for x in f['controlled_text_diagnostic']}
    allcases.update({f"asr_{x['window_start_ms']}_{x['window_end_ms']}":dict(x,group='real_asr_text') for x in f['whole_block_diagnostic']})
    cpus=sorted(os.sched_getaffinity(0))[:args.threads]
    results=[]
    for cid in args.cases:
        case=allcases[cid]
        prompt=out/(cid+'.prompt.txt');prompt.write_text(prompt_template.format(source=case['textSrc']))
        cmd=[str(runtime),'-m',str(MODEL),'-ngl','0','-t',str(args.threads),'-tb',str(args.threads),'-c','1024','-n','256','--jinja','-st','--simple-io','--no-display-prompt','--color','off','--temp','0.7','--top-p','0.6','--top-k','20','--repeat-penalty','1.05','--seed','42','-f',str(prompt)]
        start=time.perf_counter()
        proc=subprocess.Popen(cmd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,preexec_fn=lambda:os.sched_setaffinity(0,cpus))
        timer=threading.Timer(180,proc.kill);timer.start()
        sel=selectors.DefaultSelector();sel.register(proc.stdout,selectors.EVENT_READ,'stdout');sel.register(proc.stderr,selectors.EVENT_READ,'stderr')
        captures={'stdout':bytearray(),'stderr':bytearray()};events=[];samples=[];first_stdout=None
        while sel.get_map():
            samples.append({'elapsed_ms':(time.perf_counter()-start)*1000,**proc_state(proc.pid)})
            for key,_ in sel.select(timeout=0.05):
                chunk=os.read(key.fd,65536)
                if not chunk:sel.unregister(key.fileobj);continue
                ms=(time.perf_counter()-start)*1000;captures[key.data].extend(chunk)
                events.append({'elapsed_ms':ms,'channel':key.data,'text':chunk.decode('utf-8',errors='replace')})
                if key.data=='stdout' and chunk.strip() and first_stdout is None:first_stdout=ms
        _,status,usage=os.wait4(proc.pid,0);proc.returncode=os.waitstatus_to_exitcode(status);timer.cancel()
        wall=(time.perf_counter()-start)*1000
        stdout=captures['stdout'].decode();stderr=captures['stderr'].decode()
        (out/(cid+'.stdout.txt')).write_text(stdout);(out/(cid+'.stderr.log')).write_text(stderr)
        write_json(out/(cid+'.events.json'),events);write_json(out/(cid+'.process-samples.json'),samples)
        load=re.search(r'load time\s*=\s*([\d.]+) ms',stderr)
        pp=re.search(r'prompt eval time\s*=\s*([\d.]+) ms /\s*(\d+) tokens',stderr)
        dec=re.search(r'(?<!prompt )eval time\s*=\s*([\d.]+) ms /\s*(\d+) runs',stderr)
        warnings=[line for line in stderr.splitlines() if ' W ' in line or 'warn' in line.lower() or 'special_eos_id' in line]
        result={'case_id':cid,'group':case['group'],'source':case['textSrc'],'translation_raw_stdout':stdout,'mlkit_baseline':case['textTgt'],'mode':'fresh llama-completion process, no listener','threads':args.threads,'affinity_cpus':cpus,'command':cmd,'process_wall_ms':wall,'first_stdout_from_process_start_ms':first_stdout,'runtime_reported_load_ms':float(load.group(1)) if load else None,'prompt_eval_ms':float(pp.group(1)) if pp else None,'prompt_tokens':int(pp.group(2)) if pp else None,'generation_eval_ms':float(dec.group(1)) if dec else None,'generation_eval_runs':int(dec.group(2)) if dec else None,'maxrss_kib':usage.ru_maxrss,'user_cpu_seconds':usage.ru_utime,'system_cpu_seconds':usage.ru_stime,'exit_code':proc.returncode,'warnings':warnings,'semantic_review':None}
        if pp and dec:result['reported_inference_ms']=float(pp.group(1))+float(dec.group(1))
        result['prompt_mode']=args.prompt_mode
        result['translation']=stdout.removesuffix(' [end of text]\n\n\n').strip()
        result['eog_marker_seen']=' [end of text]' in stdout
        generate_event=next((x for x in events if x['channel']=='stderr' and 'generate: n_ctx' in x['text']),None)
        if generate_event and first_stdout is not None:
            result['time_to_first_content_after_generate_log_ms']=first_stdout-generate_event['elapsed_ms']
            result['generate_log_from_process_start_ms']=generate_event['elapsed_ms']
        results.append(result);write_json(out/'results.json',results)
        print(json.dumps(result,ensure_ascii=False),flush=True)
        if proc.returncode!=0:raise RuntimeError(f'{cid}: CLI failed {proc.returncode}; stop')
        if dec and int(dec.group(2))>=256:raise RuntimeError(f'{cid}: generation cap reached; stop')
        if not result['eog_marker_seen']:raise RuntimeError(f'{cid}: no EOG marker; stop')
    write_json(out/'manifest.json',{'completed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'completed':True,'cases':args.cases,'model_sha256':EXPECTED_SHA,'runtime_binary_sha256':sha256(runtime),'runtime_commit':'d81235049384534c167caea52b85a694f6103d14','model_license':'Apache-2.0','runtime_license':'MIT','threads':args.threads,'affinity_cpus':cpus,'process_load_definition':'Fresh process each case; OS page cache not cleared. Process total includes load, warmup, inference and shutdown; reported engine load and inference recorded separately.','first_output_definition':'First nonblank stdout byte timestamp from process launch includes cold process load. Additional generate-log-relative first content timestamp excludes startup using the observed generate log receipt, an instrumentation proxy rather than an engine token timing API.','max_new_tokens':256,'context':1024,'no_network_listener':True,'prompt_template':prompt_template,'prompt_mode':args.prompt_mode,'sampling_note':'Official recommended temp=.7 top_p=.6 top_k=20 repeat_penalty=1.05, fixed seed42, runtime defaults otherwise (including min_p=.05). Same settings as initial server probe.'})

if __name__=='__main__':main()
