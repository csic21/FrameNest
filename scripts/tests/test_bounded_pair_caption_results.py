"""Synthetic structural checks; not speech recognition or translation evidence."""
import copy
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from check_bounded_pair_caption_results import ASSETS,SOURCE_SHA256,validate_report
APP='a'*64;TEST='b'*64

def valid_report():
    identities=[json.loads((ASSETS/'manifest.json').read_text()),json.loads((ASSETS/'fixed-window-comparison.json').read_text())['emoji']]
    fixtures=[]
    for identity in identities:
        end=identity['duration_ms'];rows=[];units=[]
        for i,start in enumerate(range(0,end,3000)):
            stop=min(start+3000,end)
            rows.append(dict(source_unit_id=i,status='collected',window_start_ms=start,window_end_ms=stop,
                cue_start_ms=start,cue_end_ms=stop,text_src='源 文',text_tgt='目标',
                actual_pcm_read={'actual_start_ms':max(0,start-750),'actual_end_ms':min(stop+750,end)},
                actual_mt_calls=[dict(input_text='源 文',target_text='目标',status='returned')]))
        for i in range(0,len(rows),2):
            ids=list(range(i,min(i+2,len(rows)))); originals=[rows[j] for j in ids]
            units.append(dict(unit_id=len(units),source_unit_ids=ids,inspected_source_unit_ids=ids,
                reason='crossing_morpheme_pair' if len(ids)==2 else 'eof_single',
                crossing_morpheme=dict(raw_start_utf16=2,raw_end_utf16=4,surface='文源') if len(ids)==2 else None,
                input_text=''.join(x['text_src'] for x in originals),window_start_ms=rows[i]['window_start_ms'],
                window_end_ms=originals[-1]['window_end_ms'],source_start_ms=rows[i]['window_start_ms'],
                source_end_ms=originals[-1]['window_end_ms'],required_window_horizon_ms=originals[-1]['window_end_ms'],
                required_audio_horizon_ms=originals[-1]['actual_pcm_read']['actual_end_ms'],
                constituents=[{k:x[k] for k in ('source_unit_id','text_src','window_start_ms','window_end_ms','cue_start_ms','cue_end_ms')} for x in originals],
                target_text='译文',mt_status='returned',mt_ms=1.0))
        fixtures.append(dict(fixture_id=identity['id'],duration_ms=end,pcm=identity['pcm'],
            production_3s=dict(status='collected',audio_traversal_complete=True,processed_through_ms=end,window_ms=3000,rows=rows),
            bounded_pair_mt_replay=dict(status='collected',timing_scope='MT_ISOLATION_REPLAY_NOT_END_TO_END',rows=units)))
    return dict(schema_version=1,experiment='bounded_pair_mt',status='collected',collection_complete=True,
        app_apk_sha256=APP,test_apk_sha256=TEST,source_aar_sha256=SOURCE_SHA256,
        runtime_identity=f'1.13.8-asr-no-tts-sha256-{SOURCE_SHA256}',accuracy_accepted=False,hardware_performance_accepted=False,
        final_coverage_claimed=False,semantic_acceptance_passed=None,fixtures=fixtures)

class BoundedPairResultTest(unittest.TestCase):
    def test_collection_only(self):
        r=validate_report(valid_report(),APP,TEST)
        self.assertTrue(r['collection_verified']);self.assertFalse(r['release_approved'])
    def reject(self,change):
        r=valid_report();change(r)
        with self.assertRaises((ValueError,KeyError,TypeError)):
            validate_report(r,APP,TEST)
    def test_source_loss_duplicate_or_whitespace_change_rejected(self):
        for change in [lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0].update(input_text='源文源文'),
                       lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'].pop(),
                       lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'][1].update(source_unit_ids=[0,1])]:self.reject(change)
    def test_wrong_identity_scope_and_horizon_rejected(self):
        for change in [lambda r:r.update(app_apk_sha256='c'*64),lambda r:r.update(final_coverage_claimed=True),
                       lambda r:r['fixtures'][0]['bounded_pair_mt_replay'].update(timing_scope='END_TO_END'),
                       lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0].update(required_audio_horizon_ms=6000),
                       lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0].update(source_end_ms=9000)]:self.reject(change)
    def test_actual_empty_targets_are_retained_not_erased(self):
        r=valid_report();b=r['fixtures'][0]['production_3s']['rows'][0]
        b['text_tgt']='';b['actual_mt_calls'][0].update(status='empty_target',target_text='')
        c=r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0];c.update(mt_status='empty_target',target_text='')
        s=validate_report(r,APP,TEST)['fixtures'][0]
        self.assertEqual(1,s['baseline_failed_or_empty']);self.assertEqual(1,s['candidate_failed_or_empty'])
    def test_mt_failure_is_counted_and_collection_has_no_quality_claim(self):
        r=valid_report();r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0].update(mt_status='failed')
        self.assertEqual(1,validate_report(r,APP,TEST)['fixtures'][0]['candidate_failed_or_empty'])
    def test_consistently_removed_padding_is_rejected(self):
        def mutate(r):
            for f in r['fixtures']:
                rows=f['production_3s']['rows']
                for row in rows:row['actual_pcm_read']['actual_end_ms']=row['window_end_ms']
                for u in f['bounded_pair_mt_replay']['rows']:u['required_audio_horizon_ms']=max(rows[j]['window_end_ms'] for j in u['inspected_source_unit_ids'])
        self.reject(mutate)
    def test_pts_and_raw_provenance_cannot_change(self):
        self.reject(lambda r:r['fixtures'][0]['bounded_pair_mt_replay']['rows'][0]['constituents'][0].update(cue_start_ms=42))

if __name__=='__main__':unittest.main()
