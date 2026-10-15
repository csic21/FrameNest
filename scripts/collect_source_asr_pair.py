#!/usr/bin/env python3
"""Freeze the already-audited APKs and exact prepared test assets into one CI artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from check_source_asr_apk import SafeZip, SUPPLEMENT_SHA256, classfile, file_sha256, require
from prepare_source_asr import LOCK, ROOT

ASSETS = ROOT / 'app/src/androidTest/assets/runtime-comparison'
FROZEN_MANIFEST = '9231712ec85ec7a16b31000f3cb960f2a15b7e3ccc28758515452a2351f66f77'
APP_VARIANTS = {
    'debug/app-arm64-v8a-debug.apk': ['arm64-v8a'],
    'debug/app-x86_64-debug.apk': ['x86_64'],
    'debug/app-universal-debug.apk': ['arm64-v8a', 'x86_64'],
}
TEST_APK = 'androidTest/debug/app-debug-androidTest.apk'


def verify_build_config(build_root, evidence=None):
    # AGP generates both app and test BuildConfig sources. Inspect the actual
    # compiled app class by exact namespace, never source text or test constants.
    suffix = 'com/framenest/BuildConfig.class'
    classes = [p for p in build_root.rglob('BuildConfig.class')
               if p.as_posix().endswith('/' + suffix)
               and 'debug' in p.relative_to(build_root).parts]
    require(bool(classes), 'Missing compiled debug app BuildConfig.class')
    hashes = {file_sha256(p) for p in classes}
    require(len(hashes) == 1, 'Conflicting compiled app BuildConfig classes')
    path = sorted(classes)[0]
    require(classfile(path.read_bytes())[0] == 'Lcom/framenest/BuildConfig;',
            'Compiled BuildConfig has wrong class identity')
    output = subprocess.run(['java', '-m', 'jdk.jdeps/com.sun.tools.javap.Main', '-constants', '-p', str(path)],
                            check=True, capture_output=True, text=True).stdout
    expected = {'SHERPA_ASR_SOURCE': True, 'HY_MT_NATIVE': False,
                'SHERPA_RUNTIME_VERSION': LOCK['upstream_version'],
                'SHERPA_RUNTIME_IDENTITY': LOCK['runtime_identity'],
                'SHERPA_AAR_SHA256': LOCK['aar_sha256']}
    observed = {}
    for name, value in expected.items():
        kind = 'boolean' if isinstance(value, bool) else r'java\.lang\.String'
        values = re.findall(r'public\s+static\s+final\s+' + kind + r'\s+' + name + r'\s*=\s*([^;]+);', output)
        require(len(values) == 1, 'Missing or duplicate compiled BuildConfig constant: ' + name)
        observed[name] = json.loads(values[0])
        require(type(observed[name]) is type(value) and observed[name] == value,
                'Compiled BuildConfig profile mismatch: ' + name)
    report = {'method': 'JDK javap -constants on compiled app class (not executed)',
              'class': 'com.framenest.BuildConfig', 'sha256': next(iter(hashes)),
              'paths': [p.relative_to(build_root).as_posix() for p in sorted(classes)],
              'observed_constants': observed}
    if evidence is not None:
        (evidence / 'compiled-build-config.json').write_text(json.dumps(report, indent=2) + '\n')
        (evidence / 'compiled-BuildConfig.class').write_bytes(path.read_bytes())
    return report


def fixture_asset_specs(manifest):
    specs = [(m['asset'], m['bytes'], m['sha256']) for m in manifest['models']]
    for fixture in manifest['fixtures']:
        pcm = fixture['pcm']
        require(pcm['bytes'] == pcm['samples'] * 4, 'Frozen PCM byte/sample size mismatch')
        specs.append((pcm['asset'], pcm['bytes'], pcm['sha256']))
    return specs


def verify_test_assets(apk_path):
    manifest_bytes = (ASSETS / 'manifest.json').read_bytes()
    require(hashlib.sha256(manifest_bytes).hexdigest() == FROZEN_MANIFEST, 'Frozen fixture manifest changed')
    manifest = json.loads(manifest_bytes)
    source_profile = (ASSETS / 'source-profile.json').read_bytes()
    prepared_name = 'assets/runtime-comparison/generated/prepared.json'
    expected_names = {'assets/runtime-comparison/manifest.json',
                      'assets/runtime-comparison/source-profile.json', prepared_name}
    specs = fixture_asset_specs(manifest)
    expected_names.update('assets/' + name for name, _, _ in specs)
    with SafeZip(apk_path) as apk:
        actual_names = {n for n in apk.entries if n.startswith('assets/runtime-comparison/')}
        require(actual_names == expected_names, 'Prepared instrumentation asset fullset mismatch')
        require(apk.read('assets/runtime-comparison/manifest.json') == manifest_bytes, 'Packaged fixture manifest changed')
        require(apk.read('assets/runtime-comparison/source-profile.json') == source_profile, 'Packaged source profile changed')
        for name, size, sha in specs:
            entry = apk.entries['assets/' + name]
            require(entry.file_size == size, 'Prepared instrumentation asset size mismatch: ' + name)
            digest = hashlib.sha256()
            with apk.zip.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            require(digest.hexdigest() == sha, 'Prepared instrumentation asset SHA mismatch: ' + name)
        prepared_bytes = apk.read(prepared_name)
        prepared = json.loads(prepared_bytes)
        require(prepared['all_inputs_verified'] is True and prepared['manifest_sha256'] == FROZEN_MANIFEST
                and prepared['windows'] == 33 and prepared['gap_coverage_accepted'] is False,
                'Packaged preparation is not the verified frozen corpus')
    return {'prepared_assets_verified': len(expected_names), 'windows': 33,
            'packaged_prepared_sha256': hashlib.sha256(prepared_bytes).hexdigest()}


def file_record(path, member):
    return {'path': member, 'size_bytes': path.stat().st_size, 'sha256': file_sha256(path)}


def collect(apk_root, evidence):
    require(os.environ.get('GITHUB_REPOSITORY') == LOCK['repository'], 'Require same-repository CI provenance')
    sha = os.environ.get('GITHUB_SHA', '')
    require(bool(re.fullmatch('[0-9a-f]{40}', sha)), 'Missing exact source commit')
    verify_build_config(ROOT / 'app/build', evidence)
    require({p.relative_to(apk_root).as_posix() for p in apk_root.rglob('*.apk')}
            == set(APP_VARIANTS) | {TEST_APK}, 'Unexpected app/test APK fullset')
    audit_path = evidence / 'apk-static-audit.json'
    audit = json.loads(audit_path.read_text())
    require(audit['status'] == 'static-content-checks-passed' and audit['aar_sha256'] == LOCK['aar_sha256'],
            'Final APK static audit did not pass for the pinned source AAR')
    checked = {Path(row['path']).resolve(): row for row in audit['apks']}
    require(len(checked) == len(audit['apks']) == 3, 'Duplicate or incomplete app audit records')
    rows = []
    for member, abis in APP_VARIANTS.items():
        path = apk_root / member
        require(path.resolve() in checked, 'App APK not covered by static audit: ' + member)
        result = checked[path.resolve()]
        record = file_record(path, member)
        require(record['sha256'] == result['sha256'] and record['size_bytes'] == result['size_bytes']
                and result['abis'] == abis and result['source_profile_supplement_verified'] is True,
                'App changed after audit, has wrong ABI or lacks supplemental notice')
        rows.append(dict(record, kind='app', abis=abis))
    test = apk_root / TEST_APK
    prepared = verify_test_assets(test)
    rows.append(dict(file_record(test, TEST_APK), kind='instrumentation'))
    evidence_sources = {
        'apk-static-audit.json': audit_path,
        'input-preparation.json': evidence / 'input-preparation.json',
        'fixture-manifest.json': ASSETS / 'manifest.json',
        'source-profile.json': ASSETS / 'source-profile.json',
    }
    preparation = json.loads(evidence_sources['input-preparation.json'].read_text())
    require(preparation['status'] == 'passed' and preparation['all_inputs_verified'] is True
            and preparation['manifest_sha256'] == FROZEN_MANIFEST, 'Preparation report did not pass')
    evidence_rows = []
    for member, source in evidence_sources.items():
        destination = apk_root / member
        require(not destination.exists(), 'Refusing existing packaged evidence')
        shutil.copyfile(source, destination)
        evidence_rows.append(file_record(destination, member))
    report = {
        'schema_version': 1, 'status': 'compiled-static-verified-not-runtime-tested',
        'source': {'repository': LOCK['repository'], 'commit': sha,
                   'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT'])},
        'profile': {'upstream_runtime_version_expected': LOCK['upstream_version'],
                    'upstream_runtime_version_observed': None, 'runtime_identity': LOCK['runtime_identity'],
                    'aar_sha256': LOCK['aar_sha256'], 'hy_mt_native': False,
                    'supplemental_notice_sha256': SUPPLEMENT_SHA256},
        'fixture_manifest_sha256': FROZEN_MANIFEST, **prepared, 'apks': rows,
        'evidence_files': evidence_rows, 'runtime_tested': False,
        'ui_device_tested': False, 'release_approved': False,
    }
    data = json.dumps(report, indent=2) + '\n'
    (apk_root / 'source-app-pair.json').write_text(data)
    (evidence / 'source-app-pair.json').write_text(data)
    print('Frozen exact app/test APK pair and prepared assets; no runtime execution claimed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-root', type=Path, default=ROOT / 'app/build/outputs/apk')
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    collect(args.apk_root, args.evidence)
