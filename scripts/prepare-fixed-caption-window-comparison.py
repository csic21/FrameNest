#!/usr/bin/env python3
"""Add only full pinned Emoji PCM to an already prepared FN-76 diagnosis.

No new model or old FN-74 corpus. No default network. --download authorizes only
exact pinned public Emoji bytes and full-audio ffmpeg decoding; mismatches fail.
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
from fixed_caption_window_schema import validate_comparison

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/androidTest/assets/caption-diagnosis'


def load(asset_root):
    path = Path(asset_root) / 'fixed-window-comparison.json'
    raw = path.read_bytes()
    return raw, validate_comparison(json.loads(raw))


def extract_pcm(input_dir, emoji):
    """Existing WAV sample bytes or full source decode; never clip to improve score."""
    input_dir = Path(input_dir)
    media = verify_file(input_dir, emoji['media'])
    pcm = input_dir / emoji['pcm']['file']
    if pcm.exists():
        return verify_file(input_dir, emoji['pcm'])
    with tempfile.TemporaryDirectory(prefix='.emoji-pcm-', dir=input_dir) as temporary:
        partial = Path(temporary) / pcm.name
        wav_path = input_dir / 'emoji.wav'
        if wav_path.exists():
            if not wav_path.resolve().is_relative_to(input_dir.resolve()) or wav_path.stat().st_size > emoji['pcm']['bytes'] + 65536:
                raise ValueError('Existing Emoji WAV escapes input directory or exceeds bounded metadata allowance')
            with wave.open(str(wav_path), 'rb') as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes(), wav.getcomptype()) != (1, 2, 16000, 1823360, 'NONE'):
                    raise ValueError('Existing Emoji WAV must retain the complete pinned format/extent')
                partial.write_bytes(wav.readframes(wav.getnframes()))
        else:
            subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-v', 'error', '-i', str(media),
                            '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', '-f', 's16le', '-y', str(partial)],
                           check=True, timeout=180)
        verify_file(Path(temporary), emoji['pcm'])
        partial.rename(pcm)
    return pcm


def download_inputs(input_dir, asset_root=ASSETS):
    _, comparison = load(asset_root)
    emoji = comparison['emoji']
    input_dir = Path(input_dir)
    input_dir.mkdir(parents=True, exist_ok=True)
    media = input_dir / emoji['media']['file']
    if media.exists():
        verify_file(input_dir, emoji['media'])
    else:
        with tempfile.TemporaryDirectory(prefix='.emoji-download-', dir=input_dir) as temporary:
            partial = Path(temporary) / media.name
            request = urllib.request.Request(emoji['media_url'], headers={'User-Agent': 'FrameNest-FN76/1.0'})
            with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as output:
                size = 0
                while chunk := response.read(65536):
                    size += len(chunk)
                    if size > emoji['media']['bytes']:
                        raise ValueError('Public Emoji exceeds the pinned byte limit')
                    output.write(chunk)
            verify_file(Path(temporary), emoji['media'])
            partial.rename(media)


def prepare(input_dir, asset_root=ASSETS):
    asset_root = Path(asset_root)
    comparison_bytes, comparison = load(asset_root)
    generated = asset_root / 'generated'
    prepared = json.loads((generated / 'prepared.json').read_bytes())
    manifest_bytes = (asset_root / 'manifest.json').read_bytes()
    manifest = validate_manifest(json.loads(manifest_bytes))
    if prepared['manifest_sha256'] != hashlib.sha256(manifest_bytes).hexdigest() or prepared['all_inputs_verified'] is not True:
        raise ValueError('First prepare the exact existing MCDC diagnosis')
    if prepared['source_profile_sha256'] != hashlib.sha256((asset_root / 'source-profile.json').read_bytes()).hexdigest():
        raise ValueError('Existing source profile changed')
    for spec in [manifest['pcm'], manifest['reference'], *manifest['models']]:
        verify_file(generated, spec)
    validate_reference(manifest, json.loads((generated / manifest['reference']['file']).read_bytes()))
    target = generated / comparison['emoji']['pcm']['file']
    metadata = generated / 'comparison-prepared.json'
    if target.exists() or metadata.exists():
        raise ValueError('Comparison already staged; inspect/remove only its two generated files before restaging')
    pcm = extract_pcm(input_dir, comparison['emoji'])
    report = {
        'schema_version': 1, 'all_inputs_verified': True,
        'comparison_manifest_sha256': hashlib.sha256(comparison_bytes).hexdigest(),
        'diagnosis_manifest_sha256': hashlib.sha256(manifest_bytes).hexdigest(),
        'diagnosis_prepared_sha256': hashlib.sha256((generated / 'prepared.json').read_bytes()).hexdigest(),
        'emoji_pcm': comparison['emoji']['pcm'], 'emoji_duration_ms': 113960,
        'window_ms': [3000, 6000], 'additional_model_files': 0,
        'scope': 'Full pinned Emoji PCM added; existing MCDC/model/source pins reverified. No Android, ASR, MT or semantic result.',
    }
    with tempfile.TemporaryDirectory(prefix='.comparison-', dir=asset_root) as temporary:
        staging = Path(temporary)
        shutil.copyfile(pcm, staging / target.name)
        (staging / metadata.name).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        (staging / target.name).rename(target)
        (staging / metadata.name).rename(metadata)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, required=True, help='Existing full emoji.webm plus pinned PCM or WAV; otherwise decode original locally')
    parser.add_argument('--download', action='store_true', help='Fetch only exact public Emoji original if absent')
    args = parser.parse_args()
    if args.download:
        download_inputs(args.input_dir)
    print(json.dumps(prepare(args.input_dir), ensure_ascii=False, indent=2))
