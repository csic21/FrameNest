#!/usr/bin/env python3
"""Opt-in, bounded licensed fixture download. Never refresh the accepted hash."""
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/androidTest/assets"
manifest = json.loads((assets / "japanese-benchmark/manifest.json").read_text())
target = assets / manifest["asset"]
partial = target.with_suffix(".part")
request = urllib.request.Request(manifest["url"], headers={"User-Agent": "FrameNest-JapaneseBenchmark/1.0"})
try:
    digest = hashlib.sha256()
    count = 0
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
        while chunk := response.read(65536):
            count += len(chunk)
            if count > manifest["bytes"]:
                raise RuntimeError("Fixture exceeds pinned size")
            digest.update(chunk)
            output.write(chunk)
    if count != manifest["bytes"] or digest.hexdigest() != manifest["sha256"]:
        raise RuntimeError("Fixture integrity mismatch; refusing changed source bytes")
    partial.replace(target)
    print("Licensed fixture verified: exact length and SHA-256")
finally:
    partial.unlink(missing_ok=True)
