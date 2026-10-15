import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import collect_source_asr_pair as target


class PairManifestTests(unittest.TestCase):
    def compile_config(self, root, identity=None, hy=False, package='com.framenest'):
        values = {'SHERPA_ASR_SOURCE': 'true', 'HY_MT_NATIVE': str(hy).lower(),
                  'SHERPA_RUNTIME_VERSION': '"1.13.8"',
                  'SHERPA_RUNTIME_IDENTITY': json.dumps(identity or target.LOCK['runtime_identity']),
                  'SHERPA_AAR_SHA256': json.dumps(target.LOCK['aar_sha256'])}
        declarations = '\n'.join('public static final ' + ('boolean' if k in ('HY_MT_NATIVE', 'SHERPA_ASR_SOURCE') else 'String')
                                 + ' ' + k + ' = ' + v + ';' for k, v in values.items())
        source = root / 'source' / package.replace('.', '/') / 'BuildConfig.java'
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text('package ' + package + '; public final class BuildConfig {' + declarations + '}')
        output = root / 'intermediates/javac/debug/compileDebugJavaWithJavac/classes'
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '17', '-target', '17', '-d', str(output), str(source)], check=True, capture_output=True)
        return output / package.replace('.', '/') / 'BuildConfig.class'

    def test_compiled_config_separates_version_identity_and_disables_hy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.compile_config(root)
            self.compile_config(root, package='com.framenest.test')
            report = target.verify_build_config(root, root)
            self.assertEqual(report['observed_constants']['SHERPA_RUNTIME_VERSION'], '1.13.8')
            self.assertEqual(report['observed_constants']['HY_MT_NATIVE'], False)
            self.assertEqual(len(report['paths']), 1)
            self.assertEqual(hashlib.sha256((root / 'compiled-BuildConfig.class').read_bytes()).hexdigest(), report['sha256'])
            self.compile_config(root, hy=True)
            with self.assertRaisesRegex(ValueError, 'HY_MT_NATIVE'):
                target.verify_build_config(root)
            path = self.compile_config(root, identity='stock-1.13.8')
            with self.assertRaisesRegex(ValueError, 'SHERPA_RUNTIME_IDENTITY'):
                target.verify_build_config(root)
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing compiled'):
                target.verify_build_config(root)

    def test_conflicting_compiled_class_or_wrong_internal_name_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.compile_config(root)
            duplicate = root / 'other/debug/com/framenest/BuildConfig.class'
            duplicate.parent.mkdir(parents=True)
            duplicate.write_bytes(path.read_bytes())
            self.assertEqual(len(target.verify_build_config(root)['paths']), 2)
            self.compile_config(root, hy=True)
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                target.verify_build_config(root)
            duplicate.unlink()
            wrong = self.compile_config(root, package='com.framenest.test')
            path.write_bytes(wrong.read_bytes())
            with self.assertRaisesRegex(ValueError, 'wrong class identity'):
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

    def test_collector_end_to_end_contract_with_compiled_profile(self):
        # App payloads stand in for previously audited APKs; fixture assets are
        # synthetic. This exercises collector paths/schema, not Android runtime.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets, sha, test_apk = self.fixture(root)
            self.compile_config(root / 'app/build')
            apk_root = root / 'app/build/outputs/apk'
            evidence = root / 'evidence'; evidence.mkdir()
            audited = []
            for member, abis in target.APP_VARIANTS.items():
                path = apk_root / member; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(member.encode())
                audited.append(dict(target.file_record(path, str(path)), abis=abis,
                                    source_profile_supplement_verified=True))
            test_destination = apk_root / target.TEST_APK
            test_destination.parent.mkdir(parents=True)
            test_destination.write_bytes(test_apk.read_bytes())
            (evidence / 'apk-static-audit.json').write_text(json.dumps({
                'status': 'static-content-checks-passed', 'aar_sha256': target.LOCK['aar_sha256'],
                'apks': audited}))
            (evidence / 'input-preparation.json').write_text(json.dumps({
                'status': 'passed', 'all_inputs_verified': True, 'manifest_sha256': sha}))
            env = {'GITHUB_REPOSITORY': target.LOCK['repository'], 'GITHUB_SHA': 'a' * 40,
                   'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
            with patch.object(target, 'ROOT', root), patch.object(target, 'ASSETS', assets), \
                    patch.object(target, 'FROZEN_MANIFEST', sha), patch.dict(os.environ, env):
                target.collect(apk_root, evidence)
            report = json.loads((apk_root / 'source-app-pair.json').read_text())
            self.assertEqual(len(report['apks']), 4)
            self.assertEqual(len(report['evidence_files']), 4)
            self.assertEqual(report['source']['commit'], 'a' * 40)
            self.assertFalse(report['runtime_tested'])
            self.assertEqual((apk_root / 'source-app-pair.json').read_bytes(),
                             (evidence / 'source-app-pair.json').read_bytes())
            self.assertEqual(len(list(apk_root.rglob('*.*'))), 9)

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
