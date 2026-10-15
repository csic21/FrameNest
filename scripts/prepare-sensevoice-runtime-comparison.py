#!/usr/bin/env python3
"""Prepare only the reviewed public FN-74 inputs; never refresh accepted hashes.

Local reuse:
  python3 scripts/prepare-sensevoice-runtime-comparison.py --source-dir /path/to/reazon-ab
Explicit public downloads (about 312 MB plus generated assets):
  python3 scripts/prepare-sensevoice-runtime-comparison.py --download --source-dir /tmp/fn74

The source directory contains fixtures/{emoji,kishida}.{webm,wav} and
models/sensevoice/{model.int8.onnx,tokens.txt}. Download mode needs ffmpeg on PATH.
Any different source, WAV container, sample bytes, model or window hash fails
closed. A different ffmpeg build can produce a different WAV: use the accepted
WAVs instead of updating the manifest. No inference or emulator is launched.
Both APKs must package this same generated directory. Generated assets are
ignored by git and are deliberately absent from ordinary instrumentation APKs.
"""

import argparse
import array
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import wave


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/androidTest/assets"
MANIFEST = ASSETS / "runtime-comparison/manifest.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked_path(root, relative):
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Only relative paths inside the selected directory are allowed")
    destination = (root / path).resolve()
    if not destination.is_relative_to(root.resolve()):
        raise ValueError("Input path escapes the selected directory")
    return destination


def verify_file(path, spec):
    if not path.is_file() or path.stat().st_size != spec["bytes"]:
        raise ValueError(f"Pinned length mismatch: {path.name}; do not refresh the manifest")
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != spec["sha256"]:
        raise ValueError(f"Pinned SHA-256 mismatch: {path.name}; do not refresh the manifest")


def download_verified(url, path, spec):
    """Never replace a corrupt existing input or publish unverified bytes."""
    if path.exists():
        verify_file(path, spec)
        return
    if not url.startswith("https://"):
        raise ValueError("Only pinned HTTPS inputs are supported")
    path.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "FrameNest-FN74/1.0"})
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".part", delete=False) as stream:
        partial = Path(stream.name)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                count = 0
                while chunk := response.read(65536):
                    count += len(chunk)
                    if count > spec["bytes"]:
                        raise ValueError(f"Download exceeds pinned size: {path.name}")
                    stream.write(chunk)
            stream.flush()
            verify_file(partial, spec)
            partial.replace(path)
        finally:
            partial.unlink(missing_ok=True)


def extract_wav(video, wav_path, spec):
    if wav_path.exists():
        verify_file(wav_path, spec)
        return
    partial = wav_path.with_suffix(".partial.wav")
    try:
        subprocess.run([
            "ffmpeg", "-nostdin", "-v", "error", "-i", str(video),
            "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-y", str(partial),
        ], check=True)
        verify_file(partial, spec)
        partial.replace(wav_path)
    finally:
        partial.unlink(missing_ok=True)


def read_exact_pcm(wav_path, spec):
    """Equivalent to soundfile.read(dtype='float32') for the accepted PCM16 WAVs."""
    with wave.open(str(wav_path), "rb") as stream:
        if (stream.getnchannels(), stream.getsampwidth(), stream.getframerate(),
                stream.getcomptype()) != (1, 2, 16000, "NONE"):
            raise ValueError("Expected mono 16 kHz signed PCM16 WAV")
        if stream.getnframes() != spec["samples"]:
            raise ValueError("PCM frame count mismatch")
        s16le = stream.readframes(stream.getnframes())
    if len(s16le) != spec["samples"] * 2 or digest(s16le) != spec["s16le_sha256"]:
        raise ValueError("Signed PCM16 content hash mismatch")
    shorts = array.array("h", s16le)
    if shorts.itemsize != 2:
        raise ValueError("Host must support 16-bit signed short")
    if sys.byteorder != "little":
        shorts.byteswap()
    floats = array.array("f", (value / 32768 for value in shorts))
    if floats.itemsize != 4:
        raise ValueError("Host must support 32-bit float")
    if sys.byteorder != "little":
        floats.byteswap()
    f32le = floats.tobytes()
    if len(f32le) != spec["bytes"] or digest(f32le) != spec["sha256"]:
        raise ValueError("Float32 PCM content hash mismatch")
    return f32le


def verify_windows(manifest, pcm):
    """Check exactly the pinned bounded rows, leaving VAD gaps explicitly untested."""
    if manifest["schema_version"] != 1:
        raise ValueError("Unsupported manifest schema")
    seen = set()
    for sample in manifest["samples"]:
        if sample["id"] in seen:
            raise ValueError("Duplicate sample ID")
        seen.add(sample["id"])
        data = pcm[sample["fixture_id"]]
        extent = sample["extent_samples"]
        if not 0 < extent <= len(data) // 4:
            raise ValueError("Sample extent exceeds pinned PCM")
        previous_end = 0
        for index, row in enumerate(sample["rows"]):
            lo, hi = row["start_sample"], row["end_sample"]
            if (type(lo) is not int or type(hi) is not int or row["index"] != index
                    or not previous_end <= lo < hi <= extent or hi - lo > 25 * 16000):
                raise ValueError("Window must be ordered, bounded, and nonoverlapping")
            if digest(data[lo * 4:hi * 4]) != row["f32le_sha256"]:
                raise ValueError("Window float32 SHA-256 mismatch")
            previous_end = hi


def prepare(source_dir, assets_dir=ASSETS, download=False, manifest_path=MANIFEST):
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest.get("schema_version") != 1:
        raise ValueError("Unsupported manifest schema")
    pcm = {}
    for fixture in manifest["fixtures"]:
        video = checked_path(source_dir / "fixtures", fixture["video"]["file"])
        wav_path = checked_path(source_dir / "fixtures", fixture["wav"]["file"])
        if download:
            download_verified(fixture["media_url"], video, fixture["video"])
            extract_wav(video, wav_path, fixture["wav"])
        verify_file(video, fixture["video"])
        verify_file(wav_path, fixture["wav"])
        pcm[fixture["id"]] = read_exact_pcm(wav_path, fixture["pcm"])
    for model in manifest["models"]:
        path = checked_path(source_dir / "models", model["file"])
        if download:
            download_verified(model["url"], path, model)
        verify_file(path, model)
    verify_windows(manifest, pcm)

    # Check every input before staging. A failed preparation cannot look ready.
    generated = checked_path(assets_dir, "runtime-comparison/generated")
    generated.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=generated.parent, prefix=".prepare-") as temporary:
        staging = Path(temporary)
        for fixture in manifest["fixtures"]:
            destination = checked_path(staging, Path(fixture["pcm"]["asset"]).name)
            destination.write_bytes(pcm[fixture["id"]])
        for model in manifest["models"]:
            destination = checked_path(staging, Path(model["asset"]).name)
            shutil.copyfile(checked_path(source_dir / "models", model["file"]), destination)
            verify_file(destination, model)
        ready = {"schema_version": 1, "manifest_sha256": digest(manifest_bytes),
                 "all_inputs_verified": True, "windows": sum(len(s["rows"]) for s in manifest["samples"]),
                 "production_float_roundtrip_max_abs_error": 0.0,
                 "gap_coverage_accepted": False}
        (staging / "prepared.json").write_text(json.dumps(ready, indent=2) + "\n")
        generated.mkdir(exist_ok=True)
        # Remove readiness before replacing any assets; interrupted copies fail closed.
        (generated / "prepared.json").unlink(missing_ok=True)
        for path in staging.iterdir():
            if path.name != "prepared.json":
                path.replace(generated / path.name)
        (staging / "prepared.json").replace(generated / "prepared.json")
    return ready


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--download", action="store_true", help="Explicitly download pinned public source/model files")
    args = parser.parse_args()
    print(json.dumps(prepare(args.source_dir.resolve(), download=args.download), indent=2))


if __name__ == "__main__":
    main()
