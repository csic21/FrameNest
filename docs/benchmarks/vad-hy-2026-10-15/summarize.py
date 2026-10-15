import json,hashlib
from pathlib import Path
P=Path(__file__).parent;r=json.loads((P/'results.json').read_text());summary={};review_notes={
'emoji':[
'基本忠实；首个完整问题。',
'失败：问题/候选语言保留，但错误地把英语、西班牙语、汉语指为商务文书不使用的语言；指代关系仍错。',
'基本忠实；正确答案与表情符号均保留。',
'明显改善：流行程度与好莱坞电影关系保留，不再接成日本起源/表达方式诞生的因果。',
'失败：絵文字变为涂鸦，核心对象错。',
'基本忠实；像文字一样使用图像，未变成图片转换文字。',
'失败：源パート损坏导致部分；脸部表情亦不够准确。不能当作正确完整清单。',
'失败：留下日语なる，中日混合；本身也是名词碎段。',
'失败：新增因此，把简洁表达情感和古代使用强行连成因果；古埃及到原始时代时序也不当。',
'轻度泛化：絵图画变成艺术，跨语言意思保留，但范围扩大。',
'基本忠实；识别关系保留，但指代仍依赖前段。',
'失败：禁止狗变成禁止养狗；软盘图标变成软盘上的图标；末句问号源自ASR。',
'失败：手しなき错误日文原样残留；末尾增加表情符号带来的影响，不能表示具体年度表情。'],
'kishida':[
'忠实保留未完成的关于事件话题；不是可独立验收完整句。',
'只有问号，不构成识别或翻译成功；仍照原段运行以免挑选。',
'失败：损坏的逮捕源文被补成嫌疑人态度可疑；警察变检察；有事实新增/丢失。',
'失败：协助控制嫌疑人变成确认身份，致谢电话意义损坏；句尾感激被切到下一段。',
'失败：民主主义根基被摧毁这一重大错误仍在；开始和结尾都是跨段残句。',
'这个？碎段；未获得可翻译完整命题。',
'主体意义基本忠实：完成选举、让选民表达声音、对民主重要；前段这个被孤立，非理想分句。',
'基本忠实；坚持选举和警方确保各党竞选安全关系保留。',
'明显改善：朝日新闻、广岛峰会和安保是否足够的提问正确，原长窗损坏已改善。',
'部分改善：各国要人聚集恢复，不再被救出；但いずれ…源损坏变将来，句尾仍被截断。',
'失败：原承诺与下一段尽力被拆开，译文转成向民众的请共同努力指令，主体/语气改变。',
'部分失败：保持想尽力但新增正在努力；与上一外交安全句分离。']}
md=['# 全25段源译审查','', '未改变VAD源文，未补标点或拼接；以下每段均真实Hy推理。基本忠实是文本审阅，不是独立听写金标准。','']
for n,s in r['samples'].items():
 a=s['asr_passes'][0];rows=s['rows'];assert len(rows)==len(review_notes[n]);assert all(x['exit_code']==0 and x['eog'] for x in rows)
 summary[n]={'segments':len(rows),'duration_seconds':a['duration_seconds'],'selected_seconds':a['selected_seconds'],'selected_audio_fraction':a['selected_seconds']/a['duration_seconds'],'excluded_seconds':a['excluded_seconds'],'mt_seconds':sum(x['mt_process_seconds'] for x in rows),'first_segment_input_ready_seconds':rows[0]['ready_input_seconds'],'first_segment_mt_seconds':rows[0]['mt_process_seconds'],'first_segment_first_stdout_seconds':rows[0]['first_stdout_seconds'],'first_segment_text':rows[0]['text'],'asr_compute_range_seconds':[min(x['asr_compute_seconds'] for x in s['asr_passes']),max(x['asr_compute_seconds'] for x in s['asr_passes'])],'vad_compute_range_seconds':[min(x['vad_compute_seconds'] for x in s['asr_passes']),max(x['vad_compute_seconds'] for x in s['asr_passes'])],'completed_app_coverage_added_ms':0}
 md+=['## '+n,'']
 for i,x in enumerate(rows):
  x['review']=review_notes[n][i]
  for k in ['tokens','timestamps_relative_seconds','token_pts_seconds']:x.pop(k,None)
  md += [f'### {i} · {x["start_seconds"]:.3f}–{x["end_seconds"]:.3f}s · MT {x["mt_process_seconds"]:.3f}s','',x['text'],'',x['translation'],'',x['review'],'']
(P/'evidence-results.json').write_text(json.dumps(r,ensure_ascii=False,indent=2));(P/'SUMMARY.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));(P/'SENTENCE-REVIEW.md').write_text('\n'.join(md));print(json.dumps(summary,indent=2,ensure_ascii=False))
