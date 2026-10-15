#!/usr/bin/env python3
"""Read the exact reviewed same-repository AAR artifact; never fall back or self-approve bytes."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'docs/experiments/FN-74-asr-source-app/artifact-lock.json').read_text())
DESTINATION = ROOT / 'app/libs/sherpa-onnx-1.13.8-asr-no-tts.aar'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def checked_members(archive):
    files = {}
    for entry in archive.infolist():
        name = entry.filename
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or '..' in path.parts or '\\' in name
                or ':' in name or str(path) != name or entry.is_dir()
                or stat.S_ISLNK(entry.external_attr >> 16) or name in files):
            raise ValueError('Unsafe or duplicate artifact ZIP entry')
        files[name] = entry
    return files


def import_archive(archive, destination=DESTINATION):
    if destination.exists():
        raise ValueError('Refusing to overwrite existing source AAR')
    if archive.stat().st_size != LOCK['zip_size_bytes'] or digest(archive) != LOCK['zip_sha256']:
        raise ValueError('Reviewed artifact ZIP size/SHA mismatch')
    with zipfile.ZipFile(archive) as z:
        files = checked_members(z)
        manifest = json.loads(z.read('artifact-manifest.json'))
        rows = manifest['files']
        if len({r['path'] for r in rows}) != len(rows):
            raise ValueError('Duplicate artifact inventory path')
        if {r['path'] for r in rows} | {'artifact-manifest.json'} != set(files):
            raise ValueError('Artifact inventory does not match complete ZIP')
        for row in rows:
            data = z.read(row['path'])
            if len(data) != row['size_bytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
                raise ValueError('Artifact member size/SHA mismatch')
        host = json.loads(z.read('evidence/host-provenance.json'))['github']
        expected = {'GITHUB_REPOSITORY': LOCK['repository'], 'GITHUB_SHA': LOCK['commit'],
                    'GITHUB_RUN_ID': str(LOCK['run_id'])}
        if any(host.get(k) != v for k, v in expected.items()):
            raise ValueError('Embedded successful run provenance mismatch')
        if manifest['aar_built'] is not True:
            raise ValueError('Artifact is not a built candidate')
        aar = z.read(LOCK['aar_member'])
        if len(aar) != LOCK['aar_size_bytes'] or hashlib.sha256(aar).hexdigest() != LOCK['aar_sha256']:
            raise ValueError('AAR member size/SHA mismatch')
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open('xb') as output:
            output.write(aar)
    return {'lock': LOCK, 'imported_aar_sha256': digest(destination),
            'artifact_entries_verified': len(files), 'android_runtime_tested': False}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def download(archive):
    if archive.exists():
        raise ValueError('Refusing existing artifact download')
    if os.environ.get('GITHUB_REPOSITORY') != LOCK['repository']:
        raise ValueError('Download restricted to the same repository')
    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        raise ValueError('Read-only Actions token missing')
    opener = urllib.request.build_opener(NoRedirect)

    def api(path):
        request = urllib.request.Request('https://api.github.com/repos/' + LOCK['repository'] + path,
            headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'FrameNest-source-ASR/1'})
        try:
            return opener.open(request, timeout=180)
        except urllib.error.HTTPError as error:
            if error.code == 302:
                return error
            raise ValueError('Official artifact API failed with HTTP ' + str(error.code)) from None
        except urllib.error.URLError:
            raise ValueError('Official artifact API request failed') from None

    with api('/actions/runs/' + str(LOCK['run_id'])) as response:
        run = json.load(response)
    if (run['id'] != LOCK['run_id'] or run['head_sha'] != LOCK['commit']
            or run['status'] != 'completed' or run['conclusion'] != 'success'):
        raise ValueError('Locked successful run identity/status mismatch')
    with api('/actions/artifacts/' + str(LOCK['artifact_id'])) as response:
        meta = json.load(response)
    if (meta['id'] != LOCK['artifact_id'] or meta['expired']
            or meta['name'] != LOCK['artifact_name'] or meta['size_in_bytes'] != LOCK['zip_size_bytes']
            or meta['workflow_run']['id'] != LOCK['run_id']
            or meta['workflow_run']['head_sha'] != LOCK['commit']):
        raise ValueError('Locked artifact metadata mismatch or expired')
    if meta.get('digest') and meta['digest'] != 'sha256:' + LOCK['zip_sha256']:
        raise ValueError('Server artifact digest differs from lock')
    with api('/actions/artifacts/' + str(LOCK['artifact_id']) + '/zip') as response:
        if response.code != 302:
            raise ValueError('Expected isolated storage redirect')
        location = response.headers.get('Location', '')
    parsed = urllib.parse.urlparse(location)
    if (parsed.scheme != 'https' or parsed.username or parsed.password or not parsed.hostname
            or not (parsed.hostname.endswith('.blob.core.windows.net')
                    or parsed.hostname.endswith('.githubusercontent.com'))):
        raise ValueError('Unexpected artifact storage origin')
    archive.parent.mkdir(parents=True, exist_ok=True)
    try:
        # No token crosses hosts. Do not log the signed URL, including errors.
        with urllib.request.urlopen(urllib.request.Request(location,
                headers={'User-Agent': 'FrameNest-source-ASR/1'}), timeout=180) as source:
            with archive.open('xb') as target:
                shutil.copyfileobj(source, target)
    except urllib.error.URLError:
        raise ValueError('Artifact storage download failed; URL withheld') from None
    return {key: meta.get(key) for key in ('id', 'name', 'size_in_bytes', 'digest', 'expired',
                                         'created_at', 'expires_at', 'workflow_run')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    meta = download(args.archive) if args.download else None
    report = import_archive(args.archive)
    report['downloaded_metadata'] = meta
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print('Imported reviewed source AAR:', report['imported_aar_sha256'])


if __name__ == '__main__':
    main()
