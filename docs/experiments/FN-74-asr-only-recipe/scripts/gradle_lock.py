#!/usr/bin/env python3
"""Fail-closed, cache-independent review of the isolated Gradle checksum lock.

A successful review compares every captured artifact with bytes fetched directly
from its official HTTPS repository. Repository checksum sidecars, when present,
are checked too; they are not independently authenticated publisher signatures.
This is evidence for the captured graph, not a proof that an unexecuted Gradle
configuration was captured. Run the actual release tasks, then rebuild strictly
with this XML and an independently recorded digest of the review evidence.

References:
https://docs.gradle.org/8.6/userguide/dependency_verification.html
https://docs.gradle.org/8.6/userguide/plugins.html#sec:plugin_markers
https://developer.android.com/build/remote-repositories
https://developer.android.com/tools/aapt2
https://central.sonatype.org/publish/requirements/#provide-file-checksums
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

NS = 'https://schema.gradle.org/dependency-verification'
XSI = 'http://www.w3.org/2001/XMLSchema-instance'
GOOGLE = 'https://dl.google.com/dl/android/maven2/'
CENTRAL = 'https://repo.maven.apache.org/maven2/'
STATUS = 'independently-reviewed-checksum-lock'
METHOD = 'fresh-official-artifact-byte-comparison'
LIMITATIONS = (
    'Fresh official HTTPS bytes are compared independently of the Gradle cache. '
    'Published checksum sidecars are fetched from the same repository; they are '
    'not independently authenticated publisher signatures. SHA-1 sidecars are '
    'supplementary only; the artifact is always checked against locked SHA-256. '
    'Coverage is every artifact in this exact captured XML, not unexecuted '
    'configurations, SDK tools, JDK, Gradle distribution, or plugin-owned HTTP clients.'
)
IDENTITY = ('group', 'name', 'version', 'artifact', 'sha256')
MAX_XML_BYTES = 8 * 1024 * 1024
MAX_ARTIFACT_BYTES = 512 * 1024 * 1024
MAX_CHECKSUM_BYTES = 4096


class LockError(SystemExit):
    """Policy or verification failure; callers must not continue a strict build."""


def fail(message):
    raise LockError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def lock_bytes(text):
    return text if isinstance(text, bytes) else text.encode('utf-8')


def _keys(element, required, optional=()):
    if not set(required) <= set(element.attrib) or set(element.attrib) - set(required) - set(optional):
        fail('Unexpected or missing attributes on ' + element.tag)


def _coordinate(value, label):
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]*', value or '') or '..' in value:
        fail('Unsafe or unsupported ' + label + ': ' + str(value))
    if label == 'version' and ('SNAPSHOT' in value.upper() or value.lower().startswith('latest.')):
        fail('Changing/dynamic versions are forbidden: ' + value)
    return value


def _is_hash(value, length):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{' + str(length) + r'}', value) is not None


def validate_gradle_lock(text):
    """Return sorted exact artifact records; reject unsupported XML and exceptions."""
    data = lock_bytes(text)
    if len(data) > MAX_XML_BYTES:
        fail('Gradle lock is too large')
    if b'\x00' in data:
        fail('Only UTF-8 verification XML is supported')
    try:
        data.decode('utf-8')
    except UnicodeDecodeError:
        fail('Only UTF-8 verification XML is supported')
    if re.search(br'<!\s*(?:DOCTYPE|ENTITY)\b', data, re.I) or re.search(br'<\?(?!xml\s)', data, re.I):
        fail('DTD, entities, and processing instructions are forbidden')
    try:
        root = ET.fromstring(data)
    except ET.ParseError as exc:
        fail('Invalid Gradle lock XML: ' + str(exc))
    namespace = '{' + NS + '}' if root.tag == '{' + NS + '}verification-metadata' else ''
    def tag(name):
        return namespace + name
    if root.tag != tag('verification-metadata'):
        fail('Unexpected verification metadata root or namespace')
    _keys(root, (), ('{' + XSI + '}schemaLocation',))
    schema = root.get('{' + XSI + '}schemaLocation')
    if schema is not None and schema.split() != [NS, NS + '/dependency-verification-1.3.xsd']:
        fail('Expected the Gradle 8.6 dependency verification 1.3 schema')
    if [node.tag for node in root] != [tag('configuration'), tag('components')]:
        fail('Expected exactly configuration and components, in that order')
    configuration, components = root
    _keys(configuration, ())
    _keys(components, ())
    expected = {tag('verify-metadata'): 'true', tag('verify-signatures'): 'false'}
    if len(configuration) != 2 or {node.tag for node in configuration} != set(expected):
        fail('Require metadata verification and explicit checksum-only signature configuration; no trust exclusions')
    for node in configuration:
        _keys(node, ())
        if len(node) or (node.text or '').strip() != expected[node.tag]:
            fail('Metadata verification must be true and signature verification false')
    # Prevent mixed namespaces, ignored extension nodes, and significant free text.
    for node in root.iter():
        if node.tag not in expected and (node.text or '').strip():
            fail('Unexpected text in verification metadata')
        if (node.tail or '').strip():
            fail('Unexpected text after verification metadata element')
    result, seen_components, seen_artifacts = [], set(), set()
    for component in components:
        if component.tag != tag('component'):
            fail('Only component elements are allowed below components')
        _keys(component, ('group', 'name', 'version'))
        identity = tuple(_coordinate(component.get(key), key) for key in ('group', 'name', 'version'))
        if identity in seen_components:
            fail('Duplicate component: ' + ':'.join(identity))
        seen_components.add(identity)
        if not len(component):
            fail('Empty component: ' + ':'.join(identity))
        group, name, version = identity
        for artifact in component:
            if artifact.tag != tag('artifact'):
                fail('Only artifact elements are allowed below component')
            _keys(artifact, ('name',))
            filename = _coordinate(artifact.get('name'), 'artifact filename')
            prefix = name + '-' + version
            if not filename.startswith(prefix + '.') and not filename.startswith(prefix + '-'):
                fail('Artifact filename does not match component: ' + filename)
            if filename.rsplit('.', 1)[-1] not in {'pom', 'module', 'jar', 'aar'}:
                fail('Unsupported artifact extension: ' + filename)
            key = identity + (filename,)
            if key in seen_artifacts:
                fail('Duplicate artifact: ' + filename)
            seen_artifacts.add(key)
            if len(artifact) != 1 or artifact[0].tag != tag('sha256'):
                fail('Every artifact requires exactly one SHA-256; alternate checksums/trust are forbidden: ' + filename)
            checksum = artifact[0]
            _keys(checksum, ('value',), ('origin', 'reason'))
            value = checksum.get('value', '').lower()
            if len(checksum) or not _is_hash(value, 64):
                fail('Invalid or non-unique SHA-256: ' + filename)
            result.append(dict(zip(IDENTITY, identity + (filename, value))))
    if not result:
        fail('Empty verification graph cannot authorize a strict build')
    return sorted(result, key=lambda row: tuple(row[key] for key in IDENTITY))


def repository_for(group):
    """Fixed authority routing; never fall back to a mirror or another authority.

    Android's plugin marker, tools, AndroidX/data binding, and UTP are Google
    artifacts. Kotlin's Android plugin marker and implementation are on Central.
    No Plugin Portal redirect is required for either of this recipe's plugins.
    """
    # The historical com.google.android:annotations:4.1.1.4 is on Central;
    # its exact group is distinct from Google Maven's com.google.android.* groups.
    if group == 'com.google.android':
        return CENTRAL
    prefixes = ('com.android', 'androidx', 'com.google.android', 'com.google.testing.platform')
    return GOOGLE if any(group == p or group.startswith(p + '.') for p in prefixes) else CENTRAL


def artifact_url(record):
    return repository_for(record['group']) + '/'.join((
        record['group'].replace('.', '/'), record['name'], record['version'], record['artifact']))


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fail('Repository redirects require separate review: ' + req.full_url + ' -> ' + newurl)


def fetch(url, max_bytes):
    """Fetch fresh repository bytes, without Gradle/cache reads or redirects."""
    if not (url.startswith(GOOGLE) or url.startswith(CENTRAL)):
        fail('Non-authorized repository URL: ' + url)
    opener = urllib.request.build_opener(_NoRedirect())
    request = urllib.request.Request(url, headers={
        'User-Agent': 'FrameNest-FN74-lock-review/1',
        'Cache-Control': 'no-cache', 'Accept-Encoding': 'identity'})
    try:
        with opener.open(request, timeout=60) as response:
            if response.status != 200 or response.url != url:
                fail('Unexpected repository response: ' + url)
            data = response.read(max_bytes + 1)
            if len(data) > max_bytes:
                fail('Repository response exceeded size limit: ' + url)
            if not data:
                fail('Empty repository response: ' + url)
            return data
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        fail('Repository HTTP failure ' + str(exc.code) + ': ' + url)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        fail('Repository read failed: ' + url + ': ' + str(exc))


def parse_checksum(data, algorithm, filename, repository_path=None):
    """Accept hex, exact basename, or Central's exact historical staging path."""
    try:
        value = data.decode('ascii').strip()
    except UnicodeDecodeError:
        fail('Non-ASCII published checksum: ' + filename)
    length = {'sha256': 64, 'sha1': 40}[algorithm]
    labels = [re.escape(filename)]
    if repository_path is not None:
        if (not re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', repository_path)
                or '..' in repository_path or repository_path.rsplit('/', 1)[-1] != filename):
            fail('Unsafe checksum repository path: ' + str(repository_path))
        # Observed on Central's javax.inject:1 and kxml2:2.3.0 sidecars.
        # This label is compared only; it is never read as a local path.
        labels.append(re.escape('/home/maven/repository-staging/to-ibiblio/maven2/' + repository_path))
    match = re.fullmatch(r'([0-9a-fA-F]{' + str(length) + r'})(?:[ \t]+\*?(?:' + '|'.join(labels) + r'))?', value)
    if not match:
        fail('Malformed published ' + algorithm + ' checksum: ' + filename)
    return match.group(1).lower()


def review_artifact(record, fetcher=fetch):
    url = artifact_url(record)
    data = fetcher(url, MAX_ARTIFACT_BYTES)
    if data is None:
        fail('Artifact missing from official repository: ' + url)
    if not data or len(data) > MAX_ARTIFACT_BYTES:
        fail('Invalid artifact size: ' + url)
    actual = {'sha256': sha256(data), 'sha1': hashlib.sha1(data).hexdigest()}
    if actual['sha256'] != record['sha256']:
        fail('Official artifact SHA-256 differs from captured lock: ' + url)
    checksums = []
    for algorithm in ('sha256', 'sha1'):
        sidecar_url = url + '.' + algorithm
        sidecar = fetcher(sidecar_url, MAX_CHECKSUM_BYTES)
        item = {'algorithm': algorithm, 'url': sidecar_url}
        if sidecar is None:
            item.update(status='unavailable', http_status=404)
        else:
            if len(sidecar) > MAX_CHECKSUM_BYTES:
                fail('Oversized published checksum: ' + sidecar_url)
            expected = parse_checksum(sidecar, algorithm, record['artifact'], url.removeprefix(repository_for(record['group'])))
            if expected != actual[algorithm]:
                fail('Published checksum mismatch: ' + sidecar_url)
            item.update(status='matched', value=expected, sidecar_sha256=sha256(sidecar))
        checksums.append(item)
    return dict(record, repository=repository_for(record['group']), requested_url=url,
                final_url=url, size_bytes=len(data), downloaded_sha256=actual['sha256'],
                downloaded_sha1=actual['sha1'], method=METHOD,
                repository_published_checksums=checksums)


def _graph_digest(records):
    return sha256(json.dumps(records, sort_keys=True, separators=(',', ':')).encode())


def review_lock(text, fetcher=fetch, workers=4):
    """Review every captured artifact independently; any failure aborts all output."""
    records = validate_gradle_lock(text)
    if not 1 <= workers <= 16:
        fail('workers must be between 1 and 16')
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        artifacts = list(executor.map(lambda row: review_artifact(row, fetcher), records))
    evidence = {
        'schema_version': 1, 'status': STATUS,
        'reviewed_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'lock_sha256': sha256(lock_bytes(text)), 'graph_sha256': _graph_digest(records),
        'artifact_count': len(records), 'artifacts': artifacts, 'limitations': LIMITATIONS,
    }
    verify_review(text, evidence)
    return evidence


def verify_review(text, evidence):
    """Check evidence coverage/binding; caller must also pin its reviewed digest.

    A JSON report is not self-authenticating. The build must compare its bytes
    with a separately recorded, reviewed SHA-256 before calling this function.
    """
    records = validate_gradle_lock(text)
    top_keys = {'schema_version', 'status', 'reviewed_at_utc', 'lock_sha256', 'graph_sha256',
                'artifact_count', 'artifacts', 'limitations'}
    if not isinstance(evidence, dict) or set(evidence) != top_keys:
        fail('Unsupported or incomplete review evidence')
    if type(evidence['schema_version']) is not int or evidence['schema_version'] != 1 or evidence['status'] != STATUS:
        fail('Quarantined, incomplete, or unsupported review status')
    if evidence['lock_sha256'] != sha256(lock_bytes(text)) or evidence['graph_sha256'] != _graph_digest(records):
        fail('Review evidence does not match this exact verification XML')
    if type(evidence['artifact_count']) is not int or evidence['artifact_count'] != len(records):
        fail('Review artifact count differs from lock')
    if evidence['limitations'] != LIMITATIONS:
        fail('Review evidence has unsupported provenance claims')
    try:
        instant = datetime.datetime.fromisoformat(evidence['reviewed_at_utc'])
        if instant.utcoffset() != datetime.timedelta(0):
            fail('Review time must be timezone-aware UTC')
    except (TypeError, ValueError):
        fail('Invalid review timestamp')
    artifacts = evidence['artifacts']
    if not isinstance(artifacts, list) or len(artifacts) != len(records):
        fail('Missing or surplus reviewed artifacts')
    item_keys = set(IDENTITY) | {'repository', 'requested_url', 'final_url', 'size_bytes',
                              'downloaded_sha256', 'downloaded_sha1', 'method', 'repository_published_checksums'}
    for record, item in zip(records, artifacts):
        if not isinstance(item, dict) or set(item) != item_keys:
            fail('Unexpected reviewed artifact evidence shape')
        if {key: item[key] for key in IDENTITY} != record:
            fail('Review artifacts differ from exact sorted lock graph')
        url = artifact_url(record)
        if item['repository'] != repository_for(record['group']) or item['requested_url'] != url or item['final_url'] != url:
            fail('Review source differs from fixed official repository mapping')
        if item['method'] != METHOD or item['downloaded_sha256'] != record['sha256'] or not _is_hash(item['downloaded_sha1'], 40):
            fail('Missing fresh official byte comparison')
        if type(item['size_bytes']) is not int or not 0 < item['size_bytes'] <= MAX_ARTIFACT_BYTES:
            fail('Invalid reviewed artifact size')
        published = item['repository_published_checksums']
        if not isinstance(published, list) or len(published) != 2:
            fail('Both published checksum availability checks must be recorded')
        for algorithm, check in zip(('sha256', 'sha1'), published):
            if not isinstance(check, dict) or check.get('algorithm') != algorithm or check.get('url') != url + '.' + algorithm:
                fail('Published checksum source mismatch')
            if check.get('status') == 'unavailable':
                if set(check) != {'algorithm', 'url', 'status', 'http_status'} or type(check['http_status']) is not int or check['http_status'] != 404:
                    fail('Only an explicit HTTP 404 means checksum unavailable')
            elif check.get('status') == 'matched':
                if set(check) != {'algorithm', 'url', 'status', 'value', 'sidecar_sha256'} or check['value'] != item['downloaded_' + algorithm] or not _is_hash(check['sidecar_sha256'], 64):
                    fail('Published checksum is unverified or mismatched')
            else:
                fail('Unsuccessful published checksum review')
    return evidence


def read_review(path):
    """Reject duplicate JSON fields rather than silently replacing evidence."""
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail('Duplicate review evidence JSON field: ' + key)
            result[key] = value
        return result
    try:
        return json.loads(Path(path).read_bytes(), object_pairs_hook=unique_pairs)
    except (ValueError, UnicodeError) as exc:
        fail('Invalid review JSON: ' + str(exc))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    review = sub.add_parser('review', help='Independently compare every captured artifact against its official repository')
    review.add_argument('--verification-file', type=Path, required=True)
    review.add_argument('--output', type=Path, required=True)
    review.add_argument('--workers', type=int, default=4)
    check = sub.add_parser('check-review', help='Validate a separately pinned review without network access')
    check.add_argument('--verification-file', type=Path, required=True)
    check.add_argument('--review-file', type=Path, required=True)
    check.add_argument('--review-sha256', required=True)
    args = parser.parse_args()
    text = args.verification_file.read_bytes()
    if args.action == 'review':
        if args.output.exists():
            fail('Refusing to overwrite existing review evidence: ' + str(args.output))
        evidence = review_lock(text, workers=args.workers)
        # Exclusive creation prevents overwriting a review produced concurrently.
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(evidence, indent=2, sort_keys=True) + '\n')
        print('REVIEWED', evidence['artifact_count'], 'artifacts; XML sha256', evidence['lock_sha256'])
        print('EVIDENCE', args.output, 'sha256', sha256(args.output.read_bytes()))
    else:
        if not _is_hash(args.review_sha256, 64) or sha256(args.review_file.read_bytes()) != args.review_sha256:
            fail('Independently recorded review evidence digest mismatch')
        evidence = verify_review(text, read_review(args.review_file))
        print('VALID REVIEW', evidence['artifact_count'], 'artifacts; XML sha256', evidence['lock_sha256'])


if __name__ == '__main__':
    main()
