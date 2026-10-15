#!/usr/bin/env python3
"""Exact source-ASR release assets; no network, signing, or native rebuild."""
import hashlib
import json
from pathlib import Path
import re
import shutil

from check_source_asr_apk import NOTICE_PREFIX, SUPPLEMENT_ASSET, load_reference, require
from prepare_source_asr import DESTINATION, LOCK, ROOT, digest

APPROVAL = ROOT / '.github/source-asr-release-approval.json'
RUNTIME_REPORT = ROOT / 'docs/benchmarks/sherpa-source-runtime-2026-10-15/release-acceptance.json'
AAR_NAME = 'sherpa-onnx-1.13.8-asr-no-tts.aar'
NOTICE_NAME = 'sherpa-onnx-1.13.8-asr-no-tts-NOTICE.txt'
PROVENANCE_NAME = 'source-asr-provenance.json'
ASSET_NAMES = (AAR_NAME, NOTICE_NAME, PROVENANCE_NAME)


def regular(path):
    require(path.is_file() and not path.is_symlink(), 'Missing/nonregular source-ASR asset: ' + path.name)


def approval():
    regular(APPROVAL)
    def unique(pairs):
        result = {}
        for key, item in pairs:
            require(key not in result, 'Duplicate runtime approval field: ' + key)
            result[key] = item
        return result
    value = json.loads(APPROVAL.read_text(), object_pairs_hook=unique)
    require(isinstance(value, dict), 'Runtime approval must be an object')
    require(set(value) == {'schema_version', 'approved', 'aar_sha256', 'source_app_commit',
                           'runtime_run_id', 'runtime_report_sha256'}, 'Unexpected source runtime approval fields')
    require(value['schema_version'] == 1 and value['approved'] is True,
            'Source-ASR release awaits explicit runtime acceptance')
    require(value['aar_sha256'] == LOCK['aar_sha256']
            and value['source_app_commit'] == '6216ecc3ad6cfe7a0cbb2100140ff4a7f9c788d0'
            and type(value['runtime_run_id']) is int and value['runtime_run_id'] > 0
            and isinstance(value['runtime_report_sha256'], str)
            and re.fullmatch('[0-9a-f]{64}', value['runtime_report_sha256']),
            'Source-ASR runtime acceptance provenance mismatch')
    regular(RUNTIME_REPORT)
    require(digest(RUNTIME_REPORT) == value['runtime_report_sha256'], 'Accepted runtime report hash mismatch')
    report = json.loads(RUNTIME_REPORT.read_text(), object_pairs_hook=unique)
    require(report['status'] == 'accepted-for-release-candidate-with-stated-limits'
            and report['run_id'] == value['runtime_run_id']
            and report['source_app_commit'] == value['source_app_commit']
            and report['source_aar_sha256'] == value['aar_sha256']
            and report['native_version_observed'] == LOCK['upstream_version']
            and report['all_396_rows_equal_stock_1_13_8_except_decode_ms'] is True,
            'Accepted runtime report identity/result mismatch')
    return value


def notice_bytes(aar):
    regular(aar)
    require(aar.stat().st_size == LOCK['aar_size_bytes'], 'Source AAR size mismatch')
    reference = load_reference(aar)  # Exact AAR SHA, native/API/29 full notice digests.
    assets = reference['assets']
    rows = json.loads(assets[NOTICE_PREFIX + 'license-bundle-manifest.json'])
    names = [NOTICE_PREFIX + row['path'] for row in rows] + [SUPPLEMENT_ASSET]
    require(len(names) == len(set(names)) == 30, 'Expected all 29 original notices plus supplement')
    output = [b'FrameNest source-ASR third-party notices\n',
              ('AAR SHA-256: ' + LOCK['aar_sha256'] + '\n'
               'All 29 original texts and the source-access/changed-file supplement follow in full.\n').encode()]
    for name in sorted(names):
        data = assets[name]
        data.decode('utf-8', errors='strict')
        output.extend([('\n===== BEGIN ' + name + ' =====\n').encode(), data,
                       ('\n===== END ' + name + ' =====\n').encode()])
    return b''.join(output)


def provenance(source, tag, accepted):
    require(bool(re.fullmatch('[0-9a-f]{40}', source)), 'Full release source SHA required')
    require(bool(re.fullmatch(r'v\d+\.\d+\.\d+(?:-internal)?', tag)), 'Invalid release tag')
    return {'schema_version': 1, 'repository': LOCK['repository'], 'release_source_commit': source,
            'release_tag': tag, 'upstream_runtime_version': LOCK['upstream_version'],
            'runtime_identity': LOCK['runtime_identity'], 'aar_sha256': LOCK['aar_sha256'],
            'aar_size_bytes': LOCK['aar_size_bytes'], 'input_actions_artifact': LOCK,
            'runtime_acceptance': accepted,
            'source_recipe_url': f'https://github.com/{LOCK["repository"]}/tree/{source}/docs/experiments/FN-74-asr-only-recipe',
            'source_input_manifest_url': f'https://github.com/{LOCK["repository"]}/blob/{source}/docs/experiments/FN-74-asr-only-recipe/manifest.json',
            'upstream_source_url': 'https://github.com/k2-fsa/sherpa-onnx/archive/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf.tar.gz',
            'eigen_source_url': 'https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz',
            'eigen_source_sha256': 'e9c326dc8c05cd1e044c71f30f1b2e34a6161a3b6ecf445d56b53ff1669e3dec',
            'native_rebuilt_for_app_release': False,
            'arm64_16kb_runtime_tested': False,
            'warning': 'Static PT_LOAD/ZIP alignment passes. arm64 ORT unrounded RELRO-end warning remains; no known overlapping LOAD protection or demonstrated runtime failure. Not a measured speedup claim.'}


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + '\n').encode()


def record(path, tag):
    return {'name': path.name, 'size': path.stat().st_size, 'sha256': digest(path),
            'url': f'https://github.com/{LOCK["repository"]}/releases/download/{tag}/{path.name}'}


def package_assets(output, source, tag):
    accepted = approval()
    output = Path(output)
    notices = notice_bytes(DESTINATION)
    require(all(not (output / name).exists() and not (output / name).is_symlink()
                for name in ASSET_NAMES), 'Refusing existing source-ASR release assets')
    shutil.copyfile(DESTINATION, output / AAR_NAME)
    (output / NOTICE_NAME).write_bytes(notices)
    (output / PROVENANCE_NAME).write_bytes(encoded(provenance(source, tag, accepted)))
    return [record(output / name, tag) for name in ASSET_NAMES]


def verify_assets(folder, source, tag, records):
    accepted = approval()
    folder = Path(folder)
    require(isinstance(records, list) and len(records) == len(ASSET_NAMES), 'Missing/duplicate source-ASR asset records')
    require(all(isinstance(row, dict) for row in records), 'Source-ASR asset records must be objects')
    require([r.get('name') for r in records] == list(ASSET_NAMES), 'Unexpected source-ASR asset inventory/order')
    for name, row in zip(ASSET_NAMES, records):
        path = folder / name
        regular(path)
        require(row == record(path, tag), 'Source-ASR asset URL/size/SHA record mismatch: ' + name)
    require((folder / NOTICE_NAME).read_bytes() == notice_bytes(folder / AAR_NAME), 'Source-ASR full notices changed')
    require((folder / PROVENANCE_NAME).read_bytes() == encoded(provenance(source, tag, accepted)),
            'Source-ASR provenance/source URL differs from accepted source')
    return set(ASSET_NAMES)
