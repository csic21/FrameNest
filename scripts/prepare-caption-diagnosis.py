#!/usr/bin/env python3
"""Stage fixed public FN-76 inputs. Fetch only with explicit --download; never repin.

The default app and ordinary instrumentation runs never read generated assets.
Only the PCM, published reference, and existing released ASR model are packaged.
The original media and PCM are verified; an optional WAV is checked by sample
content, not container metadata. Original media and WAV are not packaged.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import wave

from caption_diagnosis_schema import validate_manifest, validate_reference, verify_file

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "app/src/androidTest/assets/caption-diagnosis"


def prepare(input_dir, model_dir, asset_root=ASSET_ROOT):
    manifest_path = asset_root / "manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = validate_manifest(json.loads(manifest_bytes))
    paths = {key: verify_file(input_dir, manifest[key]) for key in ("media", "pcm")}
    paths["reference"] = verify_file(asset_root, manifest["reference"])
    validate_reference(manifest, json.loads(paths["reference"].read_bytes()))
    model_paths = [verify_file(model_dir, spec) for spec in manifest["models"]]
    # A WAV is optional human-review material. Its container hash is provenance,
    # not a gate: FFmpeg can change LIST/INFO metadata without changing samples.
    wav_path = Path(input_dir) / manifest["wav"]["file"]
    wav_provenance = None
    if wav_path.exists():
        if wav_path.stat().st_size > manifest["pcm"]["bytes"] + 65536:
            raise ValueError("WAV exceeds bounded metadata allowance")
        with wave.open(str(wav_path), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes(), wav.getcomptype()) != (1, 2, 16000, manifest["pcm"]["samples"], "NONE"):
                raise ValueError("WAV must match the exact pinned 16 kHz mono PCM format and extent")
            if wav.readframes(wav.getnframes()) != paths["pcm"].read_bytes():
                raise ValueError("WAV sample bytes and packaged PCM differ")
        wav_provenance = {"bytes": wav_path.stat().st_size, "sha256": hashlib.sha256(wav_path.read_bytes()).hexdigest(), "pcm_equal": True}
    generated = asset_root / "generated"
    if generated.exists():
        raise ValueError("Generated inputs already exist; inspect/remove them explicitly before restaging")
    with tempfile.TemporaryDirectory(prefix=".preparing-", dir=asset_root) as temporary:
        staging = Path(temporary)
        for key in ("pcm", "reference"):
            shutil.copyfile(paths[key], staging / manifest[key]["file"])
        for spec, path in zip(manifest["models"], model_paths):
            shutil.copyfile(path, staging / spec["file"])
        report = {
            "schema_version": 1,
            "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "all_inputs_verified": True,
            "source_profile_sha256": hashlib.sha256((asset_root / "source-profile.json").read_bytes()).hexdigest(),
            "original_media_start_ms": manifest["original_media_start_ms"],
            "duration_ms": manifest["duration_ms"],
            "reference_units": len(manifest["units"]),
            "reference_kind": manifest["reference"]["kind"],
            "optional_wav": wav_provenance,
            "semantic_acceptance_passed": None,
            "scope": "Pinned public original-media/PCM/reference/model bytes verified. Optional WAV sample bytes checked; container metadata is not a gate. No inference, translation, device, or semantic acceptance.",
        }
        (staging / "prepared.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        staging.rename(generated)
    return report


def download_inputs(input_dir, asset_root=ASSET_ROOT):
    """Explicit-only bounded public source fetch plus exact PCM reproduction."""
    manifest = validate_manifest(json.loads((asset_root / "manifest.json").read_bytes()))
    input_dir = Path(input_dir)
    input_dir.mkdir(parents=True, exist_ok=True)
    media = input_dir / manifest["media"]["file"]
    if media.exists():
        verify_file(input_dir, manifest["media"])
    else:
        with tempfile.TemporaryDirectory(prefix=".download-", dir=input_dir) as temporary:
            partial = Path(temporary) / media.name
            request = urllib.request.Request(manifest["media_url"], headers={"User-Agent": "FrameNest-FN76/1.0"})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
                size = 0
                while chunk := response.read(65536):
                    size += len(chunk)
                    if size > manifest["media"]["bytes"]:
                        raise ValueError("Public media exceeds the pinned byte limit")
                    output.write(chunk)
            verify_file(Path(temporary), manifest["media"])
            partial.rename(media)
    pcm = input_dir / manifest["pcm"]["file"]
    if pcm.exists():
        verify_file(input_dir, manifest["pcm"])
    else:
        with tempfile.TemporaryDirectory(prefix=".pcm-", dir=input_dir) as temporary:
            partial = Path(temporary) / pcm.name
            subprocess.run([
                "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
                "-ss", f"{manifest['original_media_start_ms'] / 1000:.3f}", "-i", str(media),
                "-t", f"{manifest['duration_ms'] / 1000:.3f}", "-vn", "-ac", "1", "-ar", "16000",
                "-c:a", "pcm_s16le", "-f", "s16le", "-y", str(partial),
            ], check=True, timeout=180)
            verify_file(Path(temporary), manifest["pcm"])
            partial.rename(pcm)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--download", action="store_true", help="Explicitly download the fixed public original and reproduce pinned PCM with ffmpeg")
    args = parser.parse_args()
    if args.download:
        download_inputs(args.input_dir)
    print(json.dumps(prepare(args.input_dir, args.model_dir), ensure_ascii=False, indent=2))
