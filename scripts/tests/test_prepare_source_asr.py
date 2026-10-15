import hashlib
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_source_asr as target


class SourceArtifactTests(unittest.TestCase):
    def fixture(self, root, *, wrong_run=False, inventory_extra=False):
        aar = b'test-only-aar'
        lock = dict(target.LOCK)
        host = {'github': {'GITHUB_REPOSITORY': lock['repository'],
                          'GITHUB_SHA': 'wrong' if wrong_run else lock['commit'],
                          'GITHUB_RUN_ID': str(lock['run_id'])}}
        files = {lock['aar_member']: aar, 'evidence/host-provenance.json': json.dumps(host).encode()}
        rows = [{'path': p, 'size_bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()}
                for p, b in files.items()]
        files['artifact-manifest.json'] = json.dumps({'files': rows, 'aar_built': True}).encode()
        if inventory_extra:
            files['unexpected'] = b'not listed'
        archive = root / 'candidate.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            for name, data in files.items():
                z.writestr(name, data)
        lock.update(zip_size_bytes=archive.stat().st_size, zip_sha256=target.digest(archive),
                    aar_sha256=hashlib.sha256(aar).hexdigest(), aar_size_bytes=len(aar))
        return archive, lock

    def test_exact_inventory_and_aar_are_imported_without_network(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive, lock = self.fixture(root)
            with patch.object(target, 'LOCK', lock):
                result = target.import_archive(archive, root / 'new.aar')
            self.assertEqual(lock['aar_sha256'], result['imported_aar_sha256'])
            self.assertEqual(3, result['artifact_entries_verified'])
            self.assertFalse(result['android_runtime_tested'])

    def test_archive_mismatch_wrong_provenance_inventory_and_existing_destination_fail(self):
        for kind in ('archive', 'provenance', 'inventory', 'destination', 'aar'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive, lock = self.fixture(root, wrong_run=kind == 'provenance', inventory_extra=kind == 'inventory')
                if kind == 'archive':
                    lock['zip_sha256'] = '0' * 64
                if kind == 'aar':
                    lock['aar_sha256'] = '0' * 64
                destination = root / 'new.aar'
                if kind == 'destination':
                    destination.write_bytes(b'existing')
                with patch.object(target, 'LOCK', lock), self.assertRaises(ValueError):
                    target.import_archive(archive, destination)
                self.assertEqual(kind == 'destination', destination.exists())

    def test_unsafe_zip_entries_are_rejected(self):
        for name in ('../escape', '/absolute', 'a/../b', 'a//b', './b', 'a\\b', 'C:x', 'directory/'):
            with self.subTest(name=name), io.BytesIO() as data:
                with zipfile.ZipFile(data, 'w') as z:
                    z.writestr(name, b'x')
                with zipfile.ZipFile(data) as z, self.assertRaises(ValueError):
                    target.checked_members(z)

    def test_duplicate_and_symlink_rejected(self):
        for duplicate in (True, False):
            with io.BytesIO() as data:
                with zipfile.ZipFile(data, 'w') as z, warnings.catch_warnings():
                    warnings.simplefilter('ignore')
                    if duplicate:
                        z.writestr('file', b'one'); z.writestr('file', b'two')
                    else:
                        entry = zipfile.ZipInfo('link'); entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                        z.writestr(entry, '../target')
                with zipfile.ZipFile(data) as z, self.assertRaises(ValueError):
                    target.checked_members(z)

    def test_download_requires_same_repository_before_token_or_network(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(target.os.environ, {'GITHUB_REPOSITORY': 'other/repo'}):
            with self.assertRaisesRegex(ValueError, 'same repository'):
                target.download(Path(temp) / 'new.zip')

    def test_gradle_profile_pin_matches_reviewed_lock_and_remains_opt_in(self):
        build = (target.ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('val sherpaSourceSha256 = "' + target.LOCK['aar_sha256'] + '"', build)
        self.assertIn('providers.gradleProperty("sherpaAsrSource").map(String::toBooleanStrict).getOrElse(false)', build)
        self.assertIn('implementation(libs.sherpa.onnx)', build)
        self.assertIn('check(sherpaAsrSource)', build)
        self.assertIn('check(!hyMtNative)', build)
        self.assertIn('acceptance["approved"] == true', build)


if __name__ == '__main__':
    unittest.main()
