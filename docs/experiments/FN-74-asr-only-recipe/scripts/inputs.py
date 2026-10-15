#!/usr/bin/env python3
"""Fetch only locked official archives, verify them, or stage a pristine build workspace."""
import argparse, hashlib, json, os, shutil, stat, tarfile, urllib.request, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'manifest.json').read_text())
def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for data in iter(lambda: f.read(1024*1024), b''): h.update(data)
    return h.hexdigest()
def records(): return [LOCK['source'], LOCK['onnxruntime'], *LOCK['dependencies']]
def verify(download=False):
    for item in records():
        p = ROOT / item['path']
        if not p.exists() and download:
            p.parent.mkdir(parents=True, exist_ok=True)
            tmp = p.with_suffix(p.suffix + '.partial')
            if tmp.exists(): raise SystemExit(f'Remove or inspect incomplete download first: {tmp}')
            request=urllib.request.Request(item['url'], headers={'User-Agent':'sherpa-asr-only-recipe/1'})
            with urllib.request.urlopen(request, timeout=180) as response, tmp.open('xb') as out:
                shutil.copyfileobj(response, out)
            if tmp.stat().st_size != item['size_bytes'] or digest(tmp) != item['sha256']:
                raise SystemExit(f'Archive mismatch; quarantined file kept at {tmp}')
            tmp.rename(p)
        if not p.is_file(): raise SystemExit(f'Missing locked input: {p}; use fetch explicitly')
        if p.stat().st_size != item['size_bytes'] or digest(p) != item['sha256']:
            raise SystemExit(f'Locked input mismatch: {p}')
        print('VERIFIED', item['name'], item['sha256'])
def license_records():
    inventory=ROOT/'dependency-audit/license-file-manifest.json'
    if digest(inventory)!=LOCK['license_file_manifest_sha256']:raise SystemExit('License inventory checksum mismatch')
    rows=json.loads(inventory.read_text())
    for item in rows:
        path=ROOT/'dependency-audit'/item['file']
        if not path.is_file() or path.stat().st_size!=item['size_bytes'] or digest(path)!=item['sha256']:
            raise SystemExit('License file missing or checksum mismatch: '+str(path))
    if digest(ROOT/'NOTICE.template')!=LOCK['notice_template_sha256']:raise SystemExit('NOTICE template checksum mismatch')
    return [item for item in rows if not Path(item['file']).name.startswith(('EXCLUDED-','BUILD-ONLY-','UPSTREAM-'))]

def extract(item, target):
    target.mkdir(parents=True, exist_ok=False)
    path = ROOT / item['path']
    if path.name.endswith('.zip'):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                destination = (target / info.filename).resolve()
                if not destination.is_relative_to(target.resolve()): raise SystemExit('Unsafe zip path')
                if stat.S_ISLNK(info.external_attr >> 16): raise SystemExit('Zip symlink not allowed')
            z.extractall(target)
    else:
        # data filter rejects device files and escaping symlinks/paths. Python >=3.12 required.
        excluded={x['path']:x['target'] for x in item.get('excluded_nonbuild_symlinks',[])}
        def safe_filter(member,destination):
            if member.name in excluded:
                if not member.issym() or member.linkname!=excluded[member.name]:raise ValueError('Excluded link differs from pinned record')
                return None
            return tarfile.data_filter(member,destination)
        with tarfile.open(path, 'r:*') as t: t.extractall(target, filter=safe_filter)
    return target / item['archive_root'] if item['archive_root'] else target

def prepare(work):
    verify()
    work = work.resolve()
    if work.exists(): raise SystemExit(f'Refusing to reuse/mutate existing workspace: {work}')
    work.mkdir(parents=True)
    stage={'workspace':str(work),'abis':{},'source_sha256':LOCK['source']['sha256']}
    stage['ort']=str(extract(LOCK['onnxruntime'], work/'ort'))
    # The upstream Android CMake modifies simple-sentencepiece in place. Give each ABI private sources.
    for abi in LOCK['abis']:
        base=work/abi; base.mkdir()
        sources=extract(LOCK['source'],base/'source')
        deps={d['fetchcontent_override']:str(extract(d,base/'dependencies'/d['name'])) for d in LOCK['dependencies']}
        stage['abis'][abi]={'source':str(sources),'dependencies':deps,'build':str(base/'build')}
    (work/'stage.json').write_text(json.dumps(stage,indent=2)+'\n')
    print('PREPARED', work)
    return stage

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['verify','fetch','prepare'])
    parser.add_argument('--work',type=Path,help='New isolated directory, required for prepare')
    args=parser.parse_args()
    if args.action=='prepare':
        if args.work is None: parser.error('--work is required')
        prepare(args.work)
    else: verify(download=args.action=='fetch')
if __name__=='__main__': main()
