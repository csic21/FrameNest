import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import collect_source_asr_pair as target


class PairManifestTests(unittest.TestCase):
    def test_generated_config_separates_version_identity_and_disables_hy(self):
        values = {'SHERPA_ASR_SOURCE': 'true', 'HY_MT_NATIVE': 'false',
                  'SHERPA_RUNTIME_VERSION': '"1.13.8"',
                  'SHERPA_RUNTIME_IDENTITY': '"' + target.LOCK['runtime_identity'] + '"',
                  'SHERPA_AAR_SHA256': '"' + target.LOCK['aar_sha256'] + '"'}
        text = '\n'.join('public static final ' + ('boolean' if k in ('HY_MT_NATIVE', 'SHERPA_ASR_SOURCE') else 'String')
                         + ' ' + k + ' = ' + v + ';' for k, v in values.items())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'BuildConfig.java'
            path.write_text(text)
            target.verify_build_config(root)
            path.write_text(text.replace('HY_MT_NATIVE = false', 'HY_MT_NATIVE = true'))
            with self.assertRaisesRegex(ValueError, 'HY_MT_NATIVE'):
                target.verify_build_config(root)
            path.write_text(text.replace('SHERPA_RUNTIME_VERSION = "1.13.8"',
                                         'SHERPA_RUNTIME_VERSION = "' + target.LOCK['runtime_identity'] + '"'))
            with self.assertRaisesRegex(ValueError, 'SHERPA_RUNTIME_VERSION'):
                target.verify_build_config(root)
            path.unlink()
            with self.assertRaises(ValueError):
                target.verify_build_config(root)

    def test_real_frozen_manifest_uses_pcm_sha256_and_exact_byte_lengths(self):
        raw = (target.ASSETS / 'manifest.json').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), target.FROZEN_MANIFEST)
        manifest = json.loads(raw)
        specs = target.fixture_asset_specs(manifest)
        self.assertEqual(len(specs), 4)
        for fixture in manifest['fixtures']:
            pcm = fixture['pcm']
            self.assertNotIn('f32le_sha256', pcm)
            self.assertIn((pcm['asset'], pcm['bytes'], pcm['sha256']), specs)
            self.assertEqual(pcm['bytes'], pcm['samples'] * 4)
        manifest['fixtures'][0]['pcm']['bytes'] += 1
        with self.assertRaisesRegex(ValueError, 'byte/sample'):
            target.fixture_asset_specs(manifest)

    def fixture(self, root, mutate=None):
        assets = root / 'assets'; assets.mkdir()
        model, pcm = b'test-model', b'\x00' * 16
        manifest = {'models': [{'asset': 'runtime-comparison/generated/model.onnx', 'bytes': len(model),
                               'sha256': hashlib.sha256(model).hexdigest()}],
                    'fixtures': [{'pcm': {'asset': 'runtime-comparison/generated/audio.f32le', 'samples': 4, 'bytes': len(pcm),
                                          'sha256': hashlib.sha256(pcm).hexdigest()}}]}
        raw = json.dumps(manifest).encode(); sha = hashlib.sha256(raw).hexdigest()
        (assets / 'manifest.json').write_bytes(raw)
        (assets / 'source-profile.json').write_bytes(b'{"profile":"fixture"}')
        members = {'assets/runtime-comparison/manifest.json': raw,
                   'assets/runtime-comparison/source-profile.json': (assets / 'source-profile.json').read_bytes(),
                   'assets/runtime-comparison/generated/prepared.json': json.dumps({
                       'all_inputs_verified': True, 'manifest_sha256': sha, 'windows': 33,
                       'gap_coverage_accepted': False}).encode(),
                   'assets/runtime-comparison/generated/model.onnx': model,
                   'assets/runtime-comparison/generated/audio.f32le': pcm}
        if mutate:
            mutate(members)
        apk = root / 'synthetic-test.apk'
        with zipfile.ZipFile(apk, 'w') as z:
            for name, data in members.items():
                z.writestr(name, data)
        return assets, sha, apk

    def test_actual_packaged_assets_are_hashed_not_only_the_preparation_report(self):
        with tempfile.TemporaryDirectory() as directory:
            assets, sha, apk = self.fixture(Path(directory))
            with patch.object(target, 'ASSETS', assets), patch.object(target, 'FROZEN_MANIFEST', sha):
                self.assertEqual(target.verify_test_assets(apk)['prepared_assets_verified'], 5)

    def test_corrupt_extra_missing_or_unready_test_assets_fail(self):
        def corrupt_model(members):
            members['assets/runtime-comparison/generated/model.onnx'] = b'x' * len(b'test-model')
        def extra(members):
            members['assets/runtime-comparison/unreviewed'] = b'x'
        def missing(members):
            del members['assets/runtime-comparison/generated/audio.f32le']
        def unready(members):
            members['assets/runtime-comparison/generated/prepared.json'] = b'{"all_inputs_verified":false}'
        for mutation in (corrupt_model, extra, missing, unready):
            with self.subTest(mutation=mutation.__name__), tempfile.TemporaryDirectory() as directory:
                assets, sha, apk = self.fixture(Path(directory), mutation)
                with patch.object(target, 'ASSETS', assets), patch.object(target, 'FROZEN_MANIFEST', sha):
                    with self.assertRaises(ValueError):
                        target.verify_test_assets(apk)


if __name__ == '__main__':
    unittest.main()
