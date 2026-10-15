#!/usr/bin/env python3
"""Prepare only the reviewed public FN-74 inputs; never refresh accepted hashes.

Local reuse:
  python3 scripts/prepare-sensevoice-runtime-comparison.py --source-dir /path/to/reazon-ab
Explicit public downloads (about 312 MB plus generated assets):
  python3 scripts/prepare-sensevoice-runtime-comparison.py --download --source-dir /tmp/fn74

The source directory contains fixtures/{emoji,kishida}.{webm,wav} and
models/sensevoice/{model.int8.onnx,tokens.txt}. Download mode needs ffmpeg on PATH.
Any different source, sample bytes, model or window hash fails closed. A WAV
container may differ only in bounded RIFF LIST/INFO metadata (for example the
FFmpeg encoder-version tag) while its PCM format, frame count, signed PCM16,
float32 and window hashes remain exact. The actual container hash is recorded
separately from the unchanged reference hash. No inference or emulator is launched.
Both APKs must package this same generated directory. Generated assets are
ignored by git and are deliberately absent from ordinary instrumentation APKs.
"""

import argparse
import array
import hashlib
import json
from pathlib import Path
import shutil
import struct
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


def extract_wav(video, wav_path, spec, pcm_spec):
    if wav_path.exists():
        verify_wav(wav_path, spec, pcm_spec)
        return
    partial = wav_path.with_suffix(".partial.wav")
    try:
        subprocess.run([
            "ffmpeg", "-nostdin", "-v", "error", "-i", str(video),
            "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-y", str(partial),
        ], check=True)
        verify_wav(partial, spec, pcm_spec)
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
            raise ValueError(f"PCM frame count mismatch: expected={spec['samples']} actual={stream.getnframes()}")
        s16le = stream.readframes(stream.getnframes())
    if len(s16le) != spec["samples"] * 2:
        raise ValueError(f"Signed PCM16 byte count mismatch: expected={spec['samples'] * 2} actual={len(s16le)}")
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
    if digest(s16le) != spec["s16le_sha256"]:
        raise ValueError(f"Signed PCM16 content hash mismatch: expected={spec['s16le_sha256']} "
                         f"actual={digest(s16le)} bytes={len(s16le)}; "
                         f"float32_expected={spec['sha256']} float32_actual={digest(f32le)}")
    if len(f32le) != spec["bytes"] or digest(f32le) != spec["sha256"]:
        raise ValueError(f"Float32 PCM content hash mismatch: expected={spec['sha256']} "
                         f"actual={digest(f32le)} bytes={len(f32le)}")
    return f32le


def verify_wav(wav_path, wav_spec, pcm_spec):
    """Explicit metadata-only exception; no decoder/sample tolerance or repair.

    Keep the historical whole-WAV pin as provenance. Permit only the canonical
    PCM16 fmt/data chunks plus optional, bounded, structurally valid LIST/INFO
    metadata. Duplicate audio chunks, trailing bytes, other chunk types, changed
    format and any changed sample remain errors, even if wave would ignore them.
    """
    if not wav_path.is_file():
        raise ValueError(f"Missing WAV: {wav_path.name}")
    if wav_path.stat().st_size > pcm_spec["samples"] * 2 + 65536:
        raise ValueError("WAV container exceeds bounded metadata allowance")
    container = wav_path.read_bytes()
    actual_hash = digest(container)
    try:
        if (len(container) < 44 or container[:4] != b"RIFF" or container[8:12] != b"WAVE"
                or struct.unpack_from("<I", container, 4)[0] != len(container) - 8):
            raise ValueError("Invalid RIFF/WAVE extent or trailing bytes")
        chunks = {}
        position = 12
        while position < len(container):
            if position + 8 > len(container):
                raise ValueError("Truncated WAV chunk header")
            tag, size = struct.unpack_from("<4sI", container, position)
            end = position + 8 + size
            padded_end = end + size % 2
            if tag not in (b"fmt ", b"LIST", b"data") or tag in chunks or padded_end > len(container):
                raise ValueError("Unsupported, duplicate or truncated WAV chunk")
            chunks[tag] = container[position + 8:end]
            position = padded_end
        expected_format = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
        if chunks.get(b"fmt ") != expected_format:
            raise ValueError("Expected canonical mono 16 kHz signed PCM16 WAV format")
        if b"data" not in chunks or len(chunks[b"data"]) != pcm_spec["samples"] * 2:
            raise ValueError(f"PCM frame count mismatch in WAV data chunk: expected_bytes={pcm_spec['samples'] * 2} "
                             f"actual_bytes={len(chunks.get(b'data', b''))}")
        if b"LIST" in chunks:
            info = chunks[b"LIST"]
            if info[:4] != b"INFO":
                raise ValueError("Only LIST/INFO WAV metadata is allowed")
            position = 4
            while position < len(info):
                if position + 8 > len(info):
                    raise ValueError("Truncated WAV INFO metadata")
                size = struct.unpack_from("<I", info, position + 4)[0]
                position += 8 + size + size % 2
                if position > len(info):
                    raise ValueError("Truncated WAV INFO metadata value")
        pcm = read_exact_pcm(wav_path, pcm_spec)
    except (ValueError, wave.Error, EOFError, struct.error) as error:
        raise ValueError(f"WAV verification failed: file={wav_path.name} "
                         f"actual_container_bytes={len(container)} actual_container_sha256={actual_hash} "
                         f"reference_container_bytes={wav_spec['bytes']} reference_container_sha256={wav_spec['sha256']}; {error}") from error
    reference_matches = len(container) == wav_spec["bytes"] and actual_hash == wav_spec["sha256"]
    provenance = {
        "file": wav_spec["file"], "actual_container_bytes": len(container),
        "actual_container_sha256": actual_hash,
        "reference_container_bytes": wav_spec["bytes"], "reference_container_sha256": wav_spec["sha256"],
        "reference_container_matches": reference_matches,
        "metadata_only_exception_used": not reference_matches,
        "verification": "Canonical PCM16 format/frame count and pinned signed-PCM16/float32 hashes exact; only bounded LIST/INFO metadata may differ",
        "samples": pcm_spec["samples"], "sample_rate_hz": 16000, "channels": 1,
        "s16le_sha256": pcm_spec["s16le_sha256"], "f32le_sha256": digest(pcm),
    }
    return pcm, provenance


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
    wav_provenance = []
    for fixture in manifest["fixtures"]:
        video = checked_path(source_dir / "fixtures", fixture["video"]["file"])
        wav_path = checked_path(source_dir / "fixtures", fixture["wav"]["file"])
        if download:
            download_verified(fixture["media_url"], video, fixture["video"])
            extract_wav(video, wav_path, fixture["wav"], fixture["pcm"])
        verify_file(video, fixture["video"])
        pcm[fixture["id"]], provenance = verify_wav(wav_path, fixture["wav"], fixture["pcm"])
        wav_provenance.append(dict(provenance, fixture_id=fixture["id"]))
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
                 "wav_container_provenance": wav_provenance,
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
    parser.add_argument("--report", type=Path, help="Write preparation provenance or fail-closed diagnostics for CI artifacts")
    args = parser.parse_args()
    report = {"schema_version": 1, "status": "failed", "all_inputs_verified": False}
    try:
        report = dict(prepare(args.source_dir.resolve(), download=args.download), status="passed")
        print(json.dumps(report, indent=2))
    except Exception as error:
        report.update(failure_class=type(error).__name__, failure=str(error))
        raise
    finally:
        if args.report is not None:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
