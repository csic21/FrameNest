import json,statistics,hashlib
from pathlib import Path
P=Path(__file__).resolve().parent
out=['# Qwen3-ASR versus current SenseVoice, controlled Japanese captions','', 'Experimental cloud CPU evidence only; no production change or Android speed claim. See PROTOCOL.md for fixed source, runtime, segmentation and scoring limits.','']
summary={}
for name in ['sensevoice','qwen']:
 p=P/'results'/f'{name}.json'
 if not p.exists():continue
 x=json.loads(p.read_text());summary[name]={'runtime':x['runtime'],'load_seconds':x['load_seconds'],'peak_rss_mib':max(a['peak_rss_kib'] for a in x['runs'])/1024,'fixtures':{}}
 out += [f'## {name}',f'Runtime {x["runtime"]}; model load {x["load_seconds"]:.3f}s; process peakRSS {summary[name]["peak_rss_mib"]:.1f}MiB.']
 for fixture in ['emoji','kishida','kishida114']:
  runs=[a for a in x['runs'] if a['fixture']==fixture]
  if not runs:continue
  a=runs[0];all_text=[''.join(q['text'] for q in r['rows']) for r in runs]
  v=dict(runs=len(runs),first_asr_rtf=a['asr_rtf'],warm_asr_rtf_median=statistics.median(r['asr_rtf'] for r in runs[1:]) if len(runs)>1 else None,cer=a['cer_first114'],first_segment_decode_s=a['first_nonempty_decode_seconds'],first_visible_estimate_warm_s=a['first_nonempty_input_ready_seconds']+a['first_nonempty_decode_seconds'],first_visible_estimate_cold_s=x['load_seconds']+a['first_nonempty_input_ready_seconds']+a['first_nonempty_decode_seconds'],max_output_tokens=max(len(q['tokens']) for q in a['rows']),first_segment_input_ready_s=a['first_nonempty_input_ready_seconds'],same_text_across_repeats=len(set(all_text))==1,segments=len(a['rows']),timestamp_count=sum(len(q['timestamps_relative_seconds']) for q in a['rows']))
  summary[name]['fixtures'][fixture]=v
  out += [f'### {fixture}',json.dumps(v,ensure_ascii=False),'','Full initial-pass raw output (VAD envelope, not word-aligned subtitle timing):','']
  out += [f'{q["start_seconds"]:.3f}–{q["end_seconds"]:.3f}: {q["text"]}' for q in a['rows']]
  out += ['',f'Excluded VAD gaps: {json.dumps(a["gaps"],ensure_ascii=False)}','']
(P/'SUMMARY.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));(P/'REPORT.md').write_text('\n'.join(out));print(json.dumps(summary,ensure_ascii=False,indent=2))
