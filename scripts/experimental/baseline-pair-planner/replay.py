import json,subprocess,pathlib,base64
source='/workspace/scratch/46a1f16e529d/fn77-runtime-run3/evidence/diagnosis/rolling-caption-translation-diagnosis.json'
x=json.load(open(source));lines=[];fixtures={}
for f in x['fixtures']:
 rows=f['production_3s']['rows'];fixtures[f['fixture_id']]=rows
 for i,r in enumerate(rows):
  assert r['text_src']==r['published_source_text']
  lines.append('\t'.join(map(str,[f['fixture_id'],i,r['window_start_ms'],r['window_end_ms'],r.get('cue_start_ms') if r.get('cue_start_ms') is not None else '',r.get('cue_end_ms') if r.get('cue_end_ms') is not None else '',base64.b64encode(r['text_src'].encode()).decode()])))
p=subprocess.run(['java','-cp','/tmp/baseline-pair-planner.jar:/tmp/fn70-kotlin/*:/tmp/fn70-kuromoji/*','com.framenest.feature.listen_translate.experimental.PairPlannerCliKt'],input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True)
result={k:dict(fixture=k,units=[],unhealed_outer_seams=[]) for k in fixtures}
def dec(s):return base64.b64decode(s).decode()
for line in p.stdout.splitlines():
 a=line.split('\t');f=result[a[1]]
 if a[0]=='UNIT':
  ids=list(map(int,a[3].split(',')));rows=fixtures[a[1]];f['units'].append(dict(unit_index=int(a[2]),original_window_indices=ids,window_start_ms=int(a[4]),window_end_ms=int(a[5]),speech_start_ms=int(a[6]) if a[6] else None,speech_end_ms=int(a[7]) if a[7] else None,reason=a[8],crossing_morpheme=dec(a[9]),text_src=dec(a[10]),morpheme_known=a[11],inspected_cue_indices=list(map(int,a[12].split(','))),required_audio_horizon_ms=int(a[13]),original_cues=[dict(index=i,window_start_ms=rows[i]['window_start_ms'],window_end_ms=rows[i]['window_end_ms'],speech_start_ms=rows[i].get('cue_start_ms'),speech_end_ms=rows[i].get('cue_end_ms'),text_src=rows[i]['text_src']) for i in ids]))
 else:f['unhealed_outer_seams'].append(dict(left_unit=int(a[2]),right_unit=int(a[3]),boundary_ms=int(a[4]),morpheme=dec(a[5]),known=a[6]=='true'))
for name,f in result.items():
 assert ''.join(u['text_src'] for u in f['units']).encode()==''.join(r['text_src'] for r in fixtures[name]).encode();f['exact_utf8_conservation']=True
 assert [i for u in f['units'] for i in u['original_window_indices']]==list(range(len(fixtures[name])))
 print(name,'rows',len(fixtures[name]),'units',len(f['units']),'pairs',sum(len(u['original_window_indices'])==2 for u in f['units']))
 print('selected',[(u['original_cues'][0]['window_end_ms'],u['crossing_morpheme']) for u in f['units'] if len(u['original_window_indices'])==2]);print('unhealed',f['unhealed_outer_seams'])
pathlib.Path(__file__).with_name('pair-replay.json').write_text(json.dumps(list(result.values()),ensure_ascii=False,indent=2))
