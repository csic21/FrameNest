#!/usr/bin/env python3
"""Preserve quarantine/strict logs and small provenance; never upload Gradle cache or raw capture AAR."""
import argparse,json,shutil
from pathlib import Path
from inputs import ROOT,digest

def collect(project,work,evidence,out):
    if out.exists():raise ValueError('Refusing existing evidence directory')
    out.mkdir(parents=True);rows=[]
    def copy(src,name):
        if not src.is_file():return
        dst=out/name;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
        rows.append({'path':str(name),'size_bytes':dst.stat().st_size,'sha256':digest(dst)})
    for name in ['gradle-lock-capture.log','aar-build.log','LOCK-CAPTURE-QUARANTINE.txt','gradle/verification-metadata.xml','gradle-cache-inventory.json','recipe-project.json','packaging-environment.json','settings.gradle.kts','build.gradle.kts','asr/build.gradle.kts','gradle.properties','prepackage-native-audit.json']:
        copy(project/name,Path('packaging')/name)
    for name in ['native-output-manifest.json','native-output-manifest.prestrip.json','native-audit.json','strip-evidence.json','imported-native-artifact.json']:copy(work/name,Path('native')/name)
    for name in ['host-provenance.json','toolchain-provenance.json','native-download.json','tests.log']:copy(evidence/name,Path('evidence')/name)
    for name in ['manifest.json','toolchain-lock.json','native-artifact-lock.json','gradle-lock/approved-lock.json','gradle-lock/verification-metadata.xml','gradle-lock/independent-review.json']:copy(ROOT/name,Path('recipe')/name)
    for src in sorted((project/'verified-candidate').glob('*')):copy(src,Path('candidate')/src.name)
    status={'status':'exploratory-packaging-evidence-not-a-release','aar_built':(out/'candidate/sherpa-onnx-1.13.8-asr-no-tts.aar').is_file(),'android_runtime_tested':False,'reproducibility_proven':False,'legal_clearance':False,'files':rows}
    (out/'artifact-manifest.json').write_text(json.dumps(status,indent=2)+'\n');print('Collected',len(rows),'packaging evidence files')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['project','work','evidence','out']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();collect(a.project,a.work,a.evidence,a.out)
