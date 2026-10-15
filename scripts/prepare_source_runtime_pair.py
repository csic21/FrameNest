#!/usr/bin/env python3
"""Verify and stage one retained source app/test pair. Never build or execute it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from check_source_asr_apk import SafeZip

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / 'docs/experiments/FN-74-asr-source-app/runtime-input-lock.json'
ASSETS = ROOT / 'app/src/androidTest/assets/runtime-comparison'
PROFILE = json.loads((ASSETS / 'source-profile.json').read_text())
APP = 'debug/app-x86_64-debug.apk'
TEST = 'androidTest/debug/app-debug-androidTest.apk'
EVIDENCE = {'apk-static-audit.json', 'input-preparation.json', 'fixture-manifest.json', 'source-profile.json'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def validate_lock(lock):
    require(lock['schema_version'] == 1 and lock['status'] == 'pinned-reviewed', 'Runtime artifact lock is not finalized')
    require(lock['repository'] == 'csic21/FrameNest', 'Only the same reviewed repository is permitted')
    for field in ('run_id', 'run_attempt', 'artifact_id', 'zip_size_bytes'):
        require(type(lock[field]) is int and lock[field] > 0, 'Unpinned ' + field)
    require(re.fullmatch('[0-9a-f]{40}', lock['commit'] or '') is not None, 'Unpinned commit')
    for field in ('zip_sha256', 'pair_manifest_sha256', 'app_apk_sha256', 'test_apk_sha256'):
        require(re.fullmatch('[0-9a-f]{64}', lock[field] or '') is not None, 'Unpinned ' + field)
    require(lock['artifact_name'] == f"source-asr-apks-{lock['commit']}-{lock['run_attempt']}", 'Unexpected artifact name')
    # A pinned input is not execution authorization. The workflow remains disabled.


def verify_preparation(prepared):
    require(prepared['all_inputs_verified'] is True and prepared['gap_coverage_accepted'] is False
            and prepared['windows'] == 33 and prepared['manifest_sha256'] == PROFILE['frozen_fixture_manifest_sha256'],
            'Frozen preparation evidence mismatch')


def check_bytes(data, row):
    require(len(data) == row['size_bytes'] and digest(data) == row['sha256'], 'Member size/hash mismatch: ' + row['path'])


def stage_pair(archive, destination, lock):
    validate_lock(lock)
    require(not destination.exists(), 'Refusing an existing staged pair')
    require(archive.stat().st_size == lock['zip_size_bytes'] and file_digest(archive) == lock['zip_sha256'], 'Artifact ZIP size/hash mismatch')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix='.runtime-pair-') as temporary:
        staging = Path(temporary) / 'verified'
        staging.mkdir()
        with SafeZip(archive) as bundle:
            pair_bytes = bundle.read('source-app-pair.json')
            require(digest(pair_bytes) == lock['pair_manifest_sha256'], 'Pair manifest hash mismatch')
            pair = json.loads(pair_bytes)
            require(pair['schema_version'] == 1 and pair['status'] == 'compiled-static-verified-not-runtime-tested'
                    and pair['runtime_tested'] is False and pair['windows'] == 33, 'Pair manifest status/scope mismatch')
            expected_source = {'repository': lock['repository'], 'commit': lock['commit'],
                               'run_id': lock['run_id'], 'run_attempt': lock['run_attempt']}
            require(pair['source'] == expected_source, 'Pair source run/commit mismatch')
            profile = pair['profile']
            require(profile['upstream_runtime_version_expected'] == '1.13.8'
                    and profile['upstream_runtime_version_observed'] is None
                    and profile['runtime_identity'] == PROFILE['runtime_identity']
                    and profile['aar_sha256'] == PROFILE['aar_sha256'] and profile['hy_mt_native'] is False,
                    'Pair source profile mismatch')
            require(re.fullmatch('[0-9a-f]{64}', profile['supplemental_notice_sha256']) is not None, 'Missing supplemental notice identity')
            require(pair['fixture_manifest_sha256'] == PROFILE['frozen_fixture_manifest_sha256'], 'Pair frozen fixture mismatch')
            rows = pair['apks'] + pair['evidence_files']
            require(len({row['path'] for row in rows}) == len(rows), 'Duplicate manifest member')
            require({row['path'] for row in pair['evidence_files']} == EVIDENCE, 'Unexpected evidence member set')
            expected_apps = {'debug/app-arm64-v8a-debug.apk': ['arm64-v8a'], APP: ['x86_64'],
                             'debug/app-universal-debug.apk': ['arm64-v8a', 'x86_64']}
            require(len(pair['apks']) == 4, 'Expected all three static-audited app variants and one test APK')
            for row in pair['apks']:
                require((row['path'] in expected_apps and row['kind'] == 'app' and row['abis'] == expected_apps[row['path']])
                        or (row['path'] == TEST and row['kind'] == 'instrumentation'), 'Unexpected APK variant')
            require(set(bundle.entries) == {row['path'] for row in rows} | {'source-app-pair.json'}, 'Unexpected artifact members')
            apks = {row['path']: row for row in pair['apks']}
            require(apks[APP]['sha256'] == lock['app_apk_sha256'] and apks[TEST]['sha256'] == lock['test_apk_sha256'], 'Pinned APK pair mismatch')
            for row in rows:
                data = bundle.read(row['path'])
                check_bytes(data, row)
                if row['path'] in EVIDENCE or row['path'] in (APP, TEST):
                    name = 'app.apk' if row['path'] == APP else 'test.apk' if row['path'] == TEST else row['path']
                    (staging / name).write_bytes(data)
            require((staging / 'fixture-manifest.json').read_bytes() == (ASSETS / 'manifest.json').read_bytes(), 'Fixture manifest is not the frozen corpus')
            require((staging / 'source-profile.json').read_bytes() == (ASSETS / 'source-profile.json').read_bytes(), 'Source profile pin mismatch')
            prepared = json.loads((staging / 'input-preparation.json').read_text())
            require(prepared['status'] == 'passed', 'Preparation did not pass')
            verify_preparation(prepared)
            audit = json.loads((staging / 'apk-static-audit.json').read_text())
            require(audit['status'] == 'static-content-checks-passed' and audit['aar_sha256'] == PROFILE['aar_sha256'], 'Static APK audit did not pass')
            require(all(row['source_profile_supplement_verified'] is True for row in audit['apks']), 'Source supplemental notice was not verified')
            require({(r['sha256'], r['size_bytes'], tuple(r['abis'])) for r in audit['apks']}
                    == {(r['sha256'], r['size_bytes'], tuple(r['abis'])) for r in pair['apks'] if r['kind'] == 'app'}, 'Static audit does not cover these exact apps')
            # Verify the actual retained test APK's public inputs before any KVM access.
            with SafeZip(staging / 'test.apk') as test:
                prefix = 'assets/runtime-comparison/'
                for name in ('manifest.json', 'source-profile.json'):
                    require(test.read(prefix + name) == (ASSETS / name).read_bytes(), 'Test APK manifest/profile mismatch')
                verify_preparation(json.loads(test.read(prefix + 'generated/prepared.json')))
                fixture = json.loads((ASSETS / 'manifest.json').read_text())
                for spec in fixture['models'] + [r['pcm'] for r in fixture['fixtures']]:
                    data = test.read('assets/' + spec['asset'])
                    require(len(data) == spec['bytes'] and digest(data) == spec['sha256'], 'Test APK frozen input mismatch: ' + spec['asset'])
            (staging / 'source-app-pair.json').write_bytes(pair_bytes)
            (staging / 'APK-SHA256SUMS').write_text(f"{lock['app_apk_sha256']}  app.apk\n{lock['test_apk_sha256']}  test.apk\n")
            result = {'schema_version': 1, 'status': 'retained-pair-verified', 'lock': lock,
                      'app': apks[APP], 'test': apks[TEST], 'runtime_tested': False,
                      'fixture_manifest_sha256': pair['fixture_manifest_sha256'], 'profile': profile}
            (staging / 'pair-verification.json').write_text(json.dumps(result, indent=2) + '\n')
            staging.rename(destination)
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def download(archive, lock):
    validate_lock(lock)
    require(not archive.exists(), 'Refusing existing artifact download')
    require(os.environ.get('GITHUB_REPOSITORY') == lock['repository'], 'Download restricted to the same repository')
    token = os.environ.get('GITHUB_TOKEN')
    require(bool(token), 'Read-only Actions token missing')
    opener = urllib.request.build_opener(NoRedirect)

    def api(path):
        request = urllib.request.Request('https://api.github.com/repos/' + lock['repository'] + path,
            headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'FrameNest-source-runtime/1'})
        try:
            return opener.open(request, timeout=180)
        except urllib.error.HTTPError as error:
            if error.code == 302:
                return error
            raise ValueError('Official artifact API failed with HTTP ' + str(error.code)) from None
        except urllib.error.URLError:
            raise ValueError('Official artifact API request failed') from None

    with api('/actions/runs/' + str(lock['run_id'])) as response:
        run = json.load(response)
    require(run['id'] == lock['run_id'] and run['head_sha'] == lock['commit'] and run['run_attempt'] == lock['run_attempt']
            and run['status'] == 'completed' and run['conclusion'] == 'success', 'Locked successful ordinary CI run mismatch')
    require(run['path'] == '.github/workflows/asr-source-app.yml', 'Not the ordinary source-app workflow')
    with api('/actions/artifacts/' + str(lock['artifact_id'])) as response:
        meta = json.load(response)
    require(meta['id'] == lock['artifact_id'] and not meta['expired'] and meta['name'] == lock['artifact_name']
            and meta['size_in_bytes'] == lock['zip_size_bytes'] and meta['workflow_run']['id'] == lock['run_id']
            and meta['workflow_run']['head_sha'] == lock['commit'], 'Locked artifact metadata mismatch or expired')
    if meta.get('digest'):
        require(meta['digest'] == 'sha256:' + lock['zip_sha256'], 'Server artifact digest mismatch')
    with api('/actions/artifacts/' + str(lock['artifact_id']) + '/zip') as response:
        require(response.code == 302, 'Expected isolated artifact storage redirect')
        location = response.headers.get('Location', '')
    parsed = urllib.parse.urlparse(location)
    require(parsed.scheme == 'https' and not parsed.username and not parsed.password and parsed.hostname
            and (parsed.hostname.endswith('.blob.core.windows.net') or parsed.hostname.endswith('.githubusercontent.com')),
            'Unexpected artifact storage origin')
    archive.parent.mkdir(parents=True, exist_ok=True)
    try:
        # Never forward or print the API token or signed storage URL.
        with urllib.request.build_opener(NoRedirect).open(urllib.request.Request(location), timeout=180) as source:
            with archive.open('xb') as target:
                remaining = lock['zip_size_bytes']
                while chunk := source.read(min(1024 * 1024, remaining + 1)):
                    require(len(chunk) <= remaining, 'Artifact exceeded pinned size')
                    target.write(chunk)
                    remaining -= len(chunk)
                require(remaining == 0, 'Artifact shorter than pinned size')
    except urllib.error.URLError:
        raise ValueError('Artifact storage download failed; URL withheld') from None
    return {key: meta.get(key) for key in ('id', 'name', 'size_in_bytes', 'digest', 'expired', 'workflow_run')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=DEFAULT_LOCK)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    result = {'status': 'failed', 'runtime_tested': False}
    try:
        lock = json.loads(args.lock.read_text())
        metadata = download(args.archive, lock) if args.download else None
        result = stage_pair(args.archive, args.destination, lock)
        result['downloaded_metadata'] = metadata
    except Exception as error:
        result.update(failure_class=type(error).__name__, failure=str(error))
        raise
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
