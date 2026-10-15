#!/usr/bin/env python3
"""Collect only native candidates, notices and small audit evidence, including failed-build logs."""
import argparse,json,shutil
from pathlib import Path
from inputs import ROOT,digest

def collect(work,evidence,tools,out):
    if out.exists():raise ValueError('Refusing existing artifact directory')
    out.mkdir(parents=True)
    rows=[]
    def copy(src,relative):
        if not src.is_file():return
        target=out/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,target)
        rows.append({'path':str(relative),'size_bytes':target.stat().st_size,'sha256':digest(target)})
    for name in ['manifest.json','toolchain-lock.json','NOTICE.template','README.md','CI-NATIVE.md']:
        copy(ROOT/name,Path('recipe')/name)
    for name in ['stage.json','toolchain.json','native-output-manifest.json','native-audit.json']:
        copy(work/name,Path('native')/name)
    for abi in ['arm64-v8a','x86_64']:
        for lib in ['libsherpa-onnx-jni.so','libonnxruntime.so']:copy(work/'jniLibs'/abi/lib,Path('jniLibs')/abi/lib)
        for name in ['configure.log','build.log','CMakeCache.txt','compile_commands.json','requested-options.json','jni-link.map','jni-dynamic-symbols.txt']:
            copy(work/abi/'build'/name,Path('build-evidence')/abi/name)
        # Preserve source changes made by official Android CMake without uploading huge build trees.
        source=work/abi/'dependencies/simple-sentencepiece/simple-sentencepiece-0.7/ssentencepiece/csrc'
        for name in ['threadpool.h','ssentencepiece.h','ssentencepiece.cc']:copy(source/name,Path('configured-source')/abi/'simple-sentencepiece'/name)
    for name in ['host-provenance.json','toolchain-download-provenance.json','preparation-tests.log','fetch.log','native-driver.log']:
        copy(evidence/name,Path('evidence')/name)
    for item in ['ndk-runtime-NOTICE.txt']:copy(tools/item,Path('ndk-notices')/item)
    for src in (tools/'notices').rglob('*') if (tools/'notices').is_dir() else []:
        if src.is_file():copy(src,Path('ndk-notices/original')/src.relative_to(tools/'notices'))
    for src in (ROOT/'dependency-audit/licenses').rglob('*'):
        if src.is_file():copy(src,Path('dependency-notices')/src.relative_to(ROOT/'dependency-audit/licenses'))
    for name in ['dependency-inventory.json','license-file-manifest.json','NOTICE-CHECKLIST.md']:
        copy(ROOT/'dependency-audit'/name,Path('dependency-notices')/name)
    status={'status':'exploratory-ci-evidence-not-a-release','aar_built':False,'android_runtime_tested':False,'reproducibility_proven':False,'legal_clearance':False,'files':rows}
    (out/'artifact-manifest.json').write_text(json.dumps(status,indent=2)+'\n')
    print('Collected',len(rows),'files; no archive cache/model/full build tree included:',out)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['work','evidence','tools','out']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();collect(a.work,a.evidence,a.tools,a.out)
if __name__=='__main__':main()
