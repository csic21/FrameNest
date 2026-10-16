#!/usr/bin/env python3
"""Fetch/stage one pinned ordinary-CI APK pair; never build or execute Android."""
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
from fixed_caption_window_schema import validate_comparison

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / 'docs/benchmarks/caption-diagnosis-2026-10-15/runtime-input-lock.json'
ASSETS = ROOT / 'app/src/androidTest/assets/caption-diagnosis'
PROFILE = json.loads((ASSETS / 'source-profile.json').read_text())
APK_PATHS = {'app.apk': 'app/build/outputs/apk/debug/app-x86_64-debug.apk',
             'test.apk': 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def validate_lock(lock):
    require(lock.get('schema_version') == 1 and lock.get('status') == 'pinned-reviewed', 'Runtime artifact lock is not finalized')
    require(lock['repository'] == 'csic21/FrameNest', 'Only the same reviewed repository is permitted')
    for field in ('run_id', 'artifact_id', 'zip_size_bytes'):
        require(type(lock[field]) is int and lock[field] > 0, 'Unpinned ' + field)
    require(type(lock['run_attempt']) is int and lock['run_attempt'] == 1, 'Only the first build attempt is locked')
    require(lock['zip_size_bytes'] <= 1024**3, 'Artifact exceeds bounded size')
    require(re.fullmatch('[0-9a-f]{40}', lock['commit'] or '') is not None, 'Unpinned commit')
    for field in ('zip_sha256', 'pair_manifest_sha256', 'app_apk_sha256', 'test_apk_sha256'):
        require(re.fullmatch('[0-9a-f]{64}', lock[field] or '') is not None, 'Unpinned ' + field)
    require(lock['artifact_name'] == f"caption-diagnosis-apks-{lock['commit']}-1", 'Unexpected artifact name')
    require(lock['input_lock_is_execution_authorization'] is False, 'An input lock cannot authorize execution')


def verify_comparison_preparation(prepared, comparison_bytes, diagnosis_bytes, diagnosis_prepared_bytes):
    comparison = validate_comparison(json.loads(comparison_bytes))
    require(prepared['schema_version'] == 1 and prepared['all_inputs_verified'] is True
            and prepared['comparison_manifest_sha256'] == digest(comparison_bytes)
            and prepared['diagnosis_manifest_sha256'] == digest(diagnosis_bytes)
            and prepared['diagnosis_prepared_sha256'] == digest(diagnosis_prepared_bytes)
            and prepared['emoji_pcm'] == comparison['emoji']['pcm'] and prepared['emoji_duration_ms'] == 113960
            and prepared['window_ms'] == [3000, 6000] and prepared['additional_model_files'] == 0,
            'Packaged fixed-window preparation mismatch')
    return comparison


def stage_pair(archive, destination, lock):
    validate_lock(lock)
    require(not destination.exists(), 'Refusing an existing staged pair')
    require(archive.stat().st_size == lock['zip_size_bytes'] and file_digest(archive) == lock['zip_sha256'], 'Artifact ZIP size/hash mismatch')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix='.caption-pair-') as temporary:
        staging = Path(temporary) / 'verified'
        staging.mkdir()
        with SafeZip(archive) as bundle:
            require(set(bundle.entries) == {'app.apk', 'test.apk', 'apk-pair.json'}, 'Unexpected artifact members')
            pair_bytes = bundle.read('apk-pair.json')
            require(digest(pair_bytes) == lock['pair_manifest_sha256'], 'Pair manifest hash mismatch')
            pair = json.loads(pair_bytes)
            require(pair['schema_version'] == 1 and pair['runtime_executed'] is False
                    and pair['source_commit'] == lock['commit'] and pair['run_id'] == str(lock['run_id']), 'Pair source/status mismatch')
            expected = {'SHERPA_ASR_SOURCE': True, 'HY_MT_NATIVE': False,
                        'SHERPA_RUNTIME_VERSION': PROFILE['upstream_runtime_version'],
                        'SHERPA_RUNTIME_IDENTITY': PROFILE['runtime_identity'], 'SHERPA_AAR_SHA256': PROFILE['aar_sha256']}
            constants = pair['profile']['observed_constants']
            require(constants == expected and all(type(constants[k]) is type(v) for k, v in expected.items()), 'Pair source profile mismatch')
            require(len(pair['apks']) == 2 and {r['artifact_name'] for r in pair['apks']} == set(APK_PATHS), 'Expected one app and one test APK')
            for row in pair['apks']:
                name = row['artifact_name']
                data = bundle.read(name)
                key = 'app_apk_sha256' if name == 'app.apk' else 'test_apk_sha256'
                require(row['path'] == APK_PATHS[name] and len(data) == row['bytes']
                        and digest(data) == row['sha256'] == lock[key], 'Pinned APK member mismatch: ' + name)
                (staging / name).write_bytes(data)
            # Do not repeat the native/DEX audit already required by ordinary CI.
            # Check the retained harness metadata; Android verifies actual inputs/JNI again.
            with SafeZip(staging / 'test.apk') as test:
                prefix = 'assets/caption-diagnosis/'
                for name in ('manifest.json', 'source-profile.json', 'reference.json', 'fixed-window-comparison.json'):
                    require(test.read(prefix + name) == (ASSETS / name).read_bytes(), 'Packaged diagnosis metadata mismatch: ' + name)
                prepared_bytes = test.read(prefix + 'generated/prepared.json')
                prepared = json.loads(prepared_bytes)
                require(prepared['all_inputs_verified'] is True
                        and prepared['manifest_sha256'] == file_digest(ASSETS / 'manifest.json')
                        and prepared['source_profile_sha256'] == file_digest(ASSETS / 'source-profile.json')
                        and prepared['reference_units'] == 4 and prepared['duration_ms'] == 47720
                        and prepared['semantic_acceptance_passed'] is None, 'Packaged input preparation mismatch')
                comparison_prepared = json.loads(test.read(prefix + 'generated/comparison-prepared.json'))
                comparison = verify_comparison_preparation(
                    comparison_prepared,
                    test.read(prefix + 'fixed-window-comparison.json'), test.read(prefix + 'manifest.json'), prepared_bytes)
                emoji = comparison['emoji']['pcm']
                pcm = test.read(prefix + 'generated/' + emoji['file'])
                require(len(pcm) == emoji['bytes'] and digest(pcm) == emoji['sha256'], 'Packaged full Emoji PCM mismatch')
            (staging / 'apk-pair.json').write_bytes(pair_bytes)
            (staging / 'APK-SHA256SUMS').write_text(f"{lock['app_apk_sha256']}  app.apk\n{lock['test_apk_sha256']}  test.apk\n")
            result = {'schema_version': 1, 'status': 'retained-pair-verified', 'lock': lock,
                      'profile': expected, 'runtime_tested': False,
                      'diagnosis_prepared_inputs': prepared, 'comparison_prepared_inputs': comparison_prepared}
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
                     'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'FrameNest-caption-runtime/1'})
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
    require(run['path'] == '.github/workflows/caption-diagnosis-build.yml', 'Not the ordinary caption-diagnosis build workflow')
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
