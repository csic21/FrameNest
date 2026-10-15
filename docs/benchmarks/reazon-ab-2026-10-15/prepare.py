"""Opt-in downloads of fixed public fixtures/models; refuse changed bytes."""
import hashlib,json,urllib.request,subprocess
from pathlib import Path
P=Path(__file__).resolve().parent
sources={'emoji.webm':'https://upload.wikimedia.org/wikipedia/commons/a/a4/Emoji.webm','kishida.webm':'https://upload.wikimedia.org/wikipedia/commons/3/37/Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm','kishida-reference.srt':'https://commons.wikimedia.org/w/index.php?title=TimedText%3AFumio_Kishida%27s_press_conference_the_day_after_attacked.webm.ja.srt&action=raw'}
def download(row,folder):
 dest=P/folder/row['file'];dest.parent.mkdir(parents=True,exist_ok=True)
 if not dest.exists():
  req=urllib.request.Request(row['url'],headers={'User-Agent':'FrameNest-caption-benchmark/1.0 (public media research)'})
  with urllib.request.urlopen(req,timeout=120) as inp,dest.with_suffix('.part').open('wb') as out:
   total=0
   while chunk:=inp.read(65536):
    total+=len(chunk)
    if total>row['bytes']:raise RuntimeError('Download exceeds pinned size')
    out.write(chunk)
  dest.with_suffix('.part').replace(dest)
 assert dest.stat().st_size==row['bytes'],dest
 assert hashlib.sha256(dest.read_bytes()).hexdigest()==row['sha256'],dest
for row in json.loads((P/'model-files.json').read_text()):download(row,'models')
for row in json.loads((P/'fixtures/manifest.json').read_text()):
 if row['file'] in sources:download(dict(row,url=sources[row['file']]),'fixtures')
for name in ['emoji','kishida']:
 subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(P/f'fixtures/{name}.webm'),'-ac','1','-ar','16000','-c:a','pcm_s16le','-y',str(P/f'fixtures/{name}.wav')],check=True)
print('Verified fixed public sources and models; prepared PCM. Compare WAV hashes against manifest for exact environment reproduction.')
