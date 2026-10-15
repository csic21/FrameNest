import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import source_asr_release_assets as assets
import prepare_source_asr_from_release as fetch

SOURCE = 'a' * 40
TAG = 'v0.6.10-internal'
ACCEPTED = json.loads(assets.APPROVAL.read_text())


class ApprovalAndFetchTests(unittest.TestCase):
    def test_pending_approval_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'approval.json'
            path.write_text(json.dumps(dict(ACCEPTED, approved=False, runtime_report_sha256=None)))
            with patch.object(assets, 'APPROVAL', path), self.assertRaisesRegex(ValueError, 'awaits explicit'):
                assets.approval()

    def test_exact_approval_shape_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'approval.json'
            with patch.object(assets, 'APPROVAL', path):
                path.write_text(json.dumps(ACCEPTED))
                self.assertEqual(assets.approval(), ACCEPTED)
                for key, value in [('approved', 1), ('aar_sha256', '0' * 64), ('source_app_commit', 'c' * 40),
                                   ('runtime_run_id', True), ('runtime_report_sha256', None)]:
                    item = dict(ACCEPTED); item[key] = value; path.write_text(json.dumps(item))
                    with self.subTest(key=key), self.assertRaises(ValueError): assets.approval()
                path.write_text(json.dumps(ACCEPTED)[:-1] + ',"approved":true}')
                with self.assertRaisesRegex(ValueError, 'Duplicate'): assets.approval()

    def test_fixed_url_rejects_alternate_latest_query_and_origin(self):
        fetch.validate_url(fetch.RELEASE_URL)
        for url in [fetch.RELEASE_URL + '?x=1', fetch.RELEASE_URL + '#x',
                    fetch.RELEASE_URL.replace('v0.6.10-internal', 'latest'),
                    fetch.RELEASE_URL.replace('github.com', 'example.com'),
                    fetch.RELEASE_URL.replace('https:', 'http:')]:
            with self.subTest(url=url), self.assertRaises(ValueError): fetch.validate_url(url)

    def test_release_redirect_never_accepts_credentials_or_other_origin(self):
        request = fetch.urllib.request.Request(fetch.RELEASE_URL)
        for url in ['http://release-assets.githubusercontent.com/a', 'https://evil.example/a',
                    'https://user:secret@release-assets.githubusercontent.com/a',
                    'https://release-assets.githubusercontent.com/a#fragment',
                    'https://release-assets.githubusercontent.com:444/a']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                fetch.ReleaseRedirect().redirect_request(request, None, 302, '', {}, url)

    def test_release_download_exact_bytes_hash_size_and_no_fallback(self):
        data = b'fixture-reviewed-aar'
        lock = dict(fetch.LOCK, aar_size_bytes=len(data), aar_sha256=hashlib.sha256(data).hexdigest())
        class Opener:
            def __init__(self, value): self.value = value
            def open(self, request, timeout):
                self_url = request.full_url
                assert self_url == fetch.RELEASE_URL
                assert request.get_header('Authorization') is None
                return io.BytesIO(self.value)
        for payload in [data, data[:-1], data + b'x', b'x' * len(data)]:
            with self.subTest(payload=payload), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / 'candidate.aar'
                with patch.object(fetch, 'LOCK', lock), patch.object(fetch.urllib.request, 'build_opener', return_value=Opener(payload)):
                    if payload == data:
                        self.assertEqual(fetch.download_release(destination)['sha256'], lock['aar_sha256'])
                        self.assertEqual(destination.read_bytes(), data)
                        with self.assertRaisesRegex(ValueError, 'overwrite'): fetch.download_release(destination)
                    else:
                        with self.assertRaises(ValueError): fetch.download_release(destination)
                        self.assertFalse(destination.exists())
                        self.assertEqual(list(Path(directory).iterdir()), [])


@unittest.skipUnless(os.environ.get('FN74_SOURCE_AAR'), 'Set FN74_SOURCE_AAR for actual reviewed AAR asset tests')
class ActualSourceAssetTests(unittest.TestCase):
    def setUp(self):
        self.aar = Path(os.environ['FN74_SOURCE_AAR']).resolve()

    def test_all_30_notice_texts_are_present_in_full(self):
        data = assets.notice_bytes(self.aar)
        reference = assets.load_reference(self.aar)
        rows = json.loads(reference['assets'][assets.NOTICE_PREFIX + 'license-bundle-manifest.json'])
        names = [assets.NOTICE_PREFIX + row['path'] for row in rows] + [assets.SUPPLEMENT_ASSET]
        self.assertEqual(len(names), 30)
        for name in names:
            wrapped = ('\n===== BEGIN ' + name + ' =====\n').encode() + reference['assets'][name] + ('\n===== END ' + name + ' =====\n').encode()
            self.assertIn(wrapped, data)
        self.assertEqual(data, assets.notice_bytes(self.aar))

    def test_exact_bundle_roundtrip_and_tampered_records_urls_or_payloads_fail(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(assets, 'DESTINATION', self.aar), patch.object(assets, 'approval', return_value=ACCEPTED):
            root = Path(directory)
            records = assets.package_assets(root, SOURCE, TAG)
            self.assertEqual(assets.verify_assets(root, SOURCE, TAG, records), set(assets.ASSET_NAMES))
            for mutate in [lambda r: r.pop(), lambda r: r.append(r[0]), lambda r: r.__setitem__(0, 'bad'),
                           lambda r: r[0].update(url='https://example.com/source.aar'),
                           lambda r: r[0].update(sha256='0' * 64)]:
                changed = copy.deepcopy(records); mutate(changed)
                with self.assertRaises(ValueError): assets.verify_assets(root, SOURCE, TAG, changed)
            # Rehashing tampered provenance/notice records does not bless changes.
            for name in [assets.PROVENANCE_NAME, assets.NOTICE_NAME]:
                path = root / name; original = path.read_bytes(); path.write_bytes(original + b'changed')
                changed = [assets.record(root / n, TAG) for n in assets.ASSET_NAMES]
                with self.assertRaises(ValueError): assets.verify_assets(root, SOURCE, TAG, changed)
                path.write_bytes(original)
            with self.assertRaisesRegex(ValueError, 'existing'): assets.package_assets(root, SOURCE, TAG)

    def test_symlink_and_wrong_aar_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); link = root / 'link.aar'; link.symlink_to(self.aar)
            with self.assertRaises(ValueError): assets.notice_bytes(link)
            bad = root / 'bad.aar'; bad.write_bytes(b'wrong')
            with self.assertRaises(ValueError): assets.notice_bytes(bad)


if __name__ == '__main__':
    unittest.main()
