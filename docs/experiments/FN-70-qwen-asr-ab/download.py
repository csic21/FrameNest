import urllib.request,hashlib,tarfile,json
from pathlib import Path
P=Path(__file__).resolve().parent
row={"name":"sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2","size":878702423,"digest":"sha256:393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96","url":"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"}
(P/'model-source.json').write_text(json.dumps(row,indent=2))
dest=P/row['name'];part=dest.with_suffix('.part')
if not dest.exists():
 with urllib.request.urlopen(row['url'],timeout=120) as r,part.open('wb') as f:
  n=0
  while b:=r.read(1024*1024):
   n+=len(b)
   if n>row['size']:raise RuntimeError('Exceeded fixed release size')
   f.write(b)
 part.rename(dest)
assert dest.stat().st_size==row['size']
assert hashlib.file_digest(dest.open('rb'),'sha256').hexdigest()==row['digest'].split(':')[1]
with tarfile.open(dest) as t:t.extractall(P/'models',filter='data')
manifest=[dict(path=str(f.relative_to(P)),bytes=f.stat().st_size,sha256=hashlib.file_digest(f.open('rb'),'sha256').hexdigest()) for f in (P/'models').rglob('*') if f.is_file()]
(P/'model-files.json').write_text(json.dumps(manifest,indent=2))
print('Verified archive and extracted',len(manifest),'files',flush=True)
