from pathlib import Path
import json
p=Path(__file__).parent;r=json.loads((p/'evidence-results.json').read_text())
asr={'emoji':{6,11,12},'kishida':{2,3,4,9,10,11}}
mt={'emoji':{4,7,11},'kishida':set()}
boundary={'emoji':{1,7,8},'kishida':{0,1,3,4,5,9,10,11}}
minor={'emoji':{9},'kishida':set()}
rows=[]
for n,s in r['samples'].items():
 for x in s['rows']:
  i=x['index'];flags=[key for key,group in [('asr_damage',asr),('clear_mt_error',mt),('boundary_or_discourse_failure',boundary)] if i in group[n]];outcome='fail_or_incomplete' if flags else ('minor_scope_warning' if i in minor[n] else 'substantially_faithful')
  rows.append({'clip':n,'index':i,'source':x['text'],'translation':x['translation'],'flags':flags,'outcome':outcome,'note':x['review']})
out={'definition':'Manual text-level diagnosis; flags overlap and are observations, not proven isolated causal effects. No full human listening audit. Zero-based segment indices.','counts':{k:sum(k in x['flags'] for x in rows) for k in ['asr_damage','clear_mt_error','boundary_or_discourse_failure']},'outcomes':{k:sum(x['outcome']==k for x in rows) for k in ['substantially_faithful','minor_scope_warning','fail_or_incomplete']},'pure_complete_clean_source_mt_failure':{'count':1,'ids':['emoji:4']},'other_clear_local_mt_failure':{'count':2,'ids':['emoji:7','emoji:11'],'note':'7 is grammatical fragment with mixed-language output;11 has clean dog/icon relations but ASR damage elsewhere, so not wholly clean-source controls.'},'rows':rows};(p/'failure-classification.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(out['counts'],out['outcomes'])
