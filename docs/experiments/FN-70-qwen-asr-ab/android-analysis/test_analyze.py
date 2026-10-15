"""Synthetic schema tests only. No measured Android data is generated or saved."""
import copy,hashlib,json,tempfile,unittest,os
from pathlib import Path
import analyze as a
ROOT=Path(__file__).resolve().parents[2]
MANIFEST=Path(os.environ.get('FN74_MANIFEST',str(ROOT/'framenest-sherpa-runtime-ab/app/src/androidTest/assets/runtime-comparison/manifest.json')))
REF=Path(os.environ.get('FN74_REFERENCE',str(ROOT/'reazon-ab/fixtures/kishida-reference.srt')))
M=json.loads(MANIFEST.read_bytes());MH=hashlib.sha256(MANIFEST.read_bytes()).hexdigest()
def fake(v):
 n=sum(len(s['rows']) for s in M['samples'])
 x=dict(schema_version=1,status='passed',runtime_expected=v,runtime_actual_native='v'+v,runtime_build_label=v,fixture_manifest_sha256=MH,fixture_manifest=M,gap_coverage_accepted=False,accuracy_accepted=False,hardware_performance_accepted=False,translation_tested=False,production_float_input_bit_identical_to_raw=True,production_float_roundtrip_max_abs_error=0,windows_per_pass=n,raw_stream_counts_balanced=True,production_close_reload_result_equal=True,api=35,abi='synthetic-test',device_model='SYNTHETIC',device_manufacturer='TEST',apk_sha256=v,native_git_sha1='SYNTHETIC',runs=[],lifecycle=dict(raw_recognizer_created=1,raw_recognizer_released=1,raw_stream_created=n*3,raw_stream_released=n*3,production_ensure_calls=3,production_close_calls=3,production_recognize_calls=n*3+3,production_native_allocations_observable=False),cancellation_probe=dict(pre_dispatch_cancel_skipped_body=True,native_decode_interrupted_proven=False,cancellation_observed_by_caller=True,result_publication_suppressed=True))
 for b in a.BACKENDS:
  for p in range(3):
   for s in M['samples']:
    for w in s['rows']:
     r=dict(backend=b,pass_index=p,sample=s['id'],window_index=w['index'],start_sample=w['start_sample'],end_sample=w['end_sample'],start_seconds=w['start_sample']/16000,end_seconds=w['end_sample']/16000,ready_input_seconds_from_host_vad=w['ready_input_seconds'],f32le_sha256=w['f32le_sha256'],decode_ms=1.)
     r['pass']=r.pop('pass_index')
     if b==a.BACKENDS[0]:r.update(text='合成入力',tokens=['合成入力'],timestamps_relative_seconds=[0.],timestamps_absolute_seconds=[r['start_seconds']],durations_seconds=[])
     else:r.update(result={'text':'合成入力','text_separator':'','words':[{'text':'合成入力','start_ms':0,'end_ms':1}]},text_equals_same_pass_raw=True)
     x['runs'].append(r)
 return x
class Tests(unittest.TestCase):
 def test_valid_and_sample_separation(self):
  x=fake('1.12.32');a.validate(x,M,MH,'1.12.32');z=a.summarize(x,M,a.reference(REF));self.assertEqual(set(z[a.BACKENDS[0]]),{'emoji','kishida','kishida114'});self.assertIsNone(z[a.BACKENDS[0]]['emoji']['passes'][0]['cer_edited_reference']);self.assertEqual(len(x['runs']),198)
 def test_four_artifacts(self):
  with tempfile.TemporaryDirectory() as d:
   paths=[]
   for i,v in enumerate(a.VERSIONS):
    p=Path(d)/f'synthetic-{i}.json';p.write_text(json.dumps(fake(v)));paths.append(p)
   z=a.analyze(paths,MANIFEST,REF);self.assertEqual(len(z['reports']),4);self.assertFalse(z['comparisons']['baseline_repeat']['changed_rows'])
 def test_reject_invalid(self):
  for mut in [lambda x:x['runs'].pop(),lambda x:x['runs'].__setitem__(1,copy.deepcopy(x['runs'][0])),lambda x:x.__setitem__('status','running'),lambda x:x.__setitem__('runtime_actual_native','1.13.8'),lambda x:x['lifecycle'].__setitem__('raw_stream_released',0),lambda x:x['runs'][0].__setitem__('decode_ms',float('nan')),lambda x:x['runs'][0].__setitem__('timestamps_absolute_seconds',[0.]),lambda x:x['cancellation_probe'].__setitem__('result_publication_suppressed',False)]:
   x=fake('1.12.32');mut(x)
   with self.assertRaises(AssertionError):a.validate(x,M,MH,'1.12.32')
 def test_norm_matches_old(self):self.assertEqual(a.norm('Ａ B、（試験）！'), 'ab試験');self.assertEqual(len(a.reference(REF)),436)
 def test_changed_text_and_pts(self):
  x=fake('1.12.32');y=copy.deepcopy(x);y['runs'][0]['text']='変更';y['runs'][0]['timestamps_relative_seconds']=[.1]
  ai={(r['backend'],r['pass'],r['sample'],r['window_index']):r for r in x['runs']};bi={(r['backend'],r['pass'],r['sample'],r['window_index']):r for r in y['runs']};z=a.compare(ai,bi);self.assertEqual(len(z),1);self.assertTrue(z[0]['text_changed'] and z[0]['timing_changed'])
if __name__=='__main__':unittest.main()
