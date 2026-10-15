#!/usr/bin/env bash
# Official, pinned, verified artifacts only. No third-party runtime/forks.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p models runtime sources results
MODEL_REV=a0c709d9fac510f2c807aa3af52872340dc37a4a
MODEL_NAME=Hy-MT2-1.8B-Q4_K_M.gguf
MODEL_SHA=dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699
RUNTIME_ARCHIVE=llama-b11429-bin-ubuntu-x64.tar.gz
RUNTIME_SHA=f6d25dde8f51133143d1453da4fd5f73b145127177612a283bf7995957af3392
curl --proto '=https' -fLSs --max-time 60 "https://huggingface.co/api/models/tencent/Hy-MT2-1.8B-GGUF/revision/${MODEL_REV}?blobs=true" -o sources/hf-manifest-recheck.json
python3 - <<'PY'
import json
j=json.load(open('sources/hf-manifest-recheck.json'))
assert j['sha']=='a0c709d9fac510f2c807aa3af52872340dc37a4a'
x=next(x for x in j['siblings'] if x['rfilename']=='Hy-MT2-1.8B-Q4_K_M.gguf')
assert x['size']==1133080448
assert x['lfs']['sha256']=='dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'
PY
if ! printf '%s  %s\n' "$MODEL_SHA" "models/$MODEL_NAME" | sha256sum -c -; then
  curl --proto '=https' -fLSs --max-time 1200 "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/$MODEL_REV/$MODEL_NAME" -o "models/$MODEL_NAME.part"
  printf '%s  %s\n' "$MODEL_SHA" "models/$MODEL_NAME.part" | sha256sum -c -
  mv "models/$MODEL_NAME.part" "models/$MODEL_NAME"
fi
curl --proto '=https' -fLSs --max-time 60 https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/b11429 -o sources/llama-binary-release-recheck.json
python3 - <<'PY'
import json
j=json.load(open('sources/llama-binary-release-recheck.json'))
assert j['target_commitish']=='d81235049384534c167caea52b85a694f6103d14'
x=next(x for x in j['assets'] if x['name']=='llama-b11429-bin-ubuntu-x64.tar.gz')
assert x['digest']=='sha256:f6d25dde8f51133143d1453da4fd5f73b145127177612a283bf7995957af3392'
PY
if ! printf '%s  %s\n' "$RUNTIME_SHA" "runtime/$RUNTIME_ARCHIVE" | sha256sum -c -; then
  curl --proto '=https' -fLSs --max-time 180 "https://github.com/ggml-org/llama.cpp/releases/download/b11429/$RUNTIME_ARCHIVE" -o "runtime/$RUNTIME_ARCHIVE.part"
  printf '%s  %s\n' "$RUNTIME_SHA" "runtime/$RUNTIME_ARCHIVE.part" | sha256sum -c -
  mv "runtime/$RUNTIME_ARCHIVE.part" "runtime/$RUNTIME_ARCHIVE"
fi
tar --no-same-owner -xzf "runtime/$RUNTIME_ARCHIVE" -C runtime
printf 'Verified official model and runtime. Run benchmark_hy_cli.py; do not restart the archived server probe.\n'
