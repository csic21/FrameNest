#!/usr/bin/env python3
"""Import only the exact successful, independently reviewed native CI artifact."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
from inputs import ROOT,LOCK,digest,prepare
from package_aar import unique_zip_entries

NATIVE_ARTIFACT=json.loads((ROOT/'native-artifact-lock.json').read_text())

def import_native(archive,work):
    if digest(archive)!=NATIVE_ARTIFACT['zip_sha256']:raise ValueError('Native artifact ZIP digest mismatch')
    with zipfile.ZipFile(archive) as z: files=unique_zip_entries(z)
    manifest=json.loads(files['artifact-manifest.json']);rows=manifest['files']
    if len({r['path'] for r in rows})!=len(rows):raise ValueError('Duplicate native artifact manifest path')
    if {r['path'] for r in rows}|{'artifact-manifest.json'}!=set(files):raise ValueError('Native artifact inventory mismatch')
    for row in rows:
        data=files[row['path']]
        if len(data)!=row['size_bytes'] or hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Native artifact entry mismatch: '+row['path'])
    old=json.loads(files['recipe/manifest.json'])
    for key in ['source','onnxruntime','dependencies','abis','cmake_options','kotlin_sources','packaged_native_libraries','license_file_manifest_sha256','notice_template_sha256']:
        if old[key]!=LOCK[key]:raise ValueError('Native inputs differ from imported candidate: '+key)
    host=json.loads(files['evidence/host-provenance.json'])
    expected={'GITHUB_REPOSITORY':NATIVE_ARTIFACT['repository'],'GITHUB_SHA':NATIVE_ARTIFACT['commit'],'GITHUB_RUN_ID':str(NATIVE_ARTIFACT['run_id'])}
    if any(host['github'].get(k)!=v for k,v in expected.items()):raise ValueError('Native run provenance mismatch')
    outputs=json.loads(files['native/native-output-manifest.json'])
    if outputs.get('static_audit_passed') is not True:raise ValueError('Imported native audit did not pass')
    expected_paths={f'jniLibs/{abi}/{lib}' for abi in LOCK['abis'] for lib in LOCK['packaged_native_libraries']}
    if len(outputs['outputs'])!=len(expected_paths) or {r['path'] for r in outputs['outputs']}!=expected_paths:raise ValueError('Native output whitelist mismatch')
    for row in outputs['outputs']:
        if hashlib.sha256(files[row['path']]).hexdigest()!=row['sha256']:raise ValueError('Native output digest mismatch')
    prepare(work)
    for name in ['toolchain.json','native-output-manifest.json','native-audit.json']:(work/name).write_bytes(files['native/'+name])
    for name in expected_paths:
        path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(files[name])
    (work/'licenses').mkdir();(work/'licenses/ndk-runtime-NOTICE.txt').write_bytes(files['ndk-notices/ndk-runtime-NOTICE.txt'])
    for name,data in files.items():
        if name.startswith('ndk-notices/original/'):
            path=work/'licenses/original'/name.removeprefix('ndk-notices/original/');path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    (work/'imported-native-artifact.json').write_text(json.dumps(NATIVE_ARTIFACT,indent=2)+'\n')
    print('Imported exact native candidate, pending explicit strip and new static audit:',work)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--work',type=Path,required=True);a=p.parse_args();import_native(a.artifact,a.work)
if __name__=='__main__':main()
