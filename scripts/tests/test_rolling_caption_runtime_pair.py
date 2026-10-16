"""Offline synthetic archives/logs only. Never launch Android, network, or ACL tools."""
import copy
from functools import lru_cache
import io
import json
from pathlib import Path
import re
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_rolling_caption_runtime_pair as pair

ROOT = Path(__file__).resolve().parents[2]


def zipped(members):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in members.items():
            archive.writestr(name, data)
    return output.getvalue()


def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)


@lru_cache(maxsize=8)
def synthetic_png(width, height, compressed=None):
    if compressed is None:
        compressed = zlib.compress((b'\0' * (1 + width * 4)) * height, level=1)
    return (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + png_chunk(b'IDAT', compressed) + png_chunk(b'IEND', b''))


def gate(value):
    value = value.strip()
    if value.startswith('${{') and value.endswith('}}'):
        value = value[3:-2].strip()
    if value == 'false':
        return ('closed',)
    match = re.fullmatch(r'github\.run_number == ([1-9][0-9]*) && github\.run_attempt == 1', value)
    if match:
        return ('one-run', int(match.group(1)))
    raise ValueError('Only closed or exact one-run/attempt-one gates are accepted')


class RetainedCaptionPairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.destination = self.root / 'staged'
        self.archive = self.root / 'retained.zip'
        prefix = 'assets/caption-diagnosis/'
        self.assets = self.root / 'assets'
        self.assets.mkdir()
        for name in ('manifest.json', 'source-profile.json', 'reference.json', 'fixed-window-comparison.json'):
            (self.assets / name).write_bytes((pair.ASSETS / name).read_bytes())
        self.comparison = json.loads((self.assets / 'fixed-window-comparison.json').read_bytes())
        real_pcm = copy.deepcopy(self.comparison['emoji']['pcm'])
        self.synthetic_pcm = b'\0' * 8
        self.comparison['emoji']['pcm'].update(bytes=8, samples=4, sha256=pair.digest(self.synthetic_pcm))
        (self.assets / 'fixed-window-comparison.json').write_text(json.dumps(self.comparison))
        validate = pair.validate_comparison
        def validate_synthetic_manifest(manifest):
            # Exercise the real schema except the tiny synthetic archive PCM.
            original = copy.deepcopy(manifest)
            original['emoji']['pcm'] = real_pcm
            validate(original)
            return manifest
        self.enterContext(patch.object(pair, 'validate_comparison', validate_synthetic_manifest))
        self.enterContext(patch.object(pair, 'ASSETS', self.assets))
        self.test_inputs = {prefix + name: (self.assets / name).read_bytes() for name in ('manifest.json', 'source-profile.json', 'reference.json', 'fixed-window-comparison.json')}
        self.test_inputs[prefix + 'generated/emoji.s16le'] = self.synthetic_pcm
        self.comparison_prepared = {'schema_version': 1, 'all_inputs_verified': True, 'emoji_pcm': self.comparison['emoji']['pcm'],
                                    'emoji_duration_ms': 113960, 'window_ms': [3000, 6000], 'additional_model_files': 0}
        self.prepared = {'all_inputs_verified': True, 'manifest_sha256': pair.file_digest(pair.ASSETS / 'manifest.json'),
                         'source_profile_sha256': pair.file_digest(pair.ASSETS / 'source-profile.json'),
                         'reference_units': 4, 'duration_ms': 47720, 'semantic_acceptance_passed': None}
        self.record = {'schema_version': 1, 'runtime_executed': False, 'source_commit': 'a' * 40, 'run_id': '10',
                       'profile': {'observed_constants': {'SHERPA_ASR_SOURCE': True, 'HY_MT_NATIVE': False,
                           'SHERPA_RUNTIME_VERSION': '1.13.8', 'SHERPA_RUNTIME_IDENTITY': pair.PROFILE['runtime_identity'],
                           'SHERPA_AAR_SHA256': pair.PROFILE['aar_sha256']}}}
        self.lock = {'schema_version': 1, 'status': 'pinned-reviewed', 'repository': 'csic21/FrameNest',
                     'run_id': 10, 'run_attempt': 1, 'artifact_id': 20, 'commit': 'a' * 40,
                     'artifact_name': 'rolling-caption-diagnosis-apks-' + 'a' * 40 + '-1', 'input_lock_is_execution_authorization': False}
        self.seal()

    def seal(self, mutate=None, extra=None):
        self.test_inputs['assets/caption-diagnosis/generated/prepared.json'] = json.dumps(self.prepared).encode()
        self.comparison_prepared.update(
            comparison_manifest_sha256=pair.file_digest(self.assets / 'fixed-window-comparison.json'),
            diagnosis_manifest_sha256=pair.file_digest(self.assets / 'manifest.json'),
            diagnosis_prepared_sha256=pair.digest(self.test_inputs['assets/caption-diagnosis/generated/prepared.json']))
        self.test_inputs['assets/caption-diagnosis/generated/comparison-prepared.json'] = json.dumps(self.comparison_prepared).encode()
        members = {'app.apk': b'synthetic-app', 'test.apk': zipped(self.test_inputs)}
        self.record['apks'] = [{'path': path, 'artifact_name': name, 'bytes': len(members[name]), 'sha256': pair.digest(members[name])}
                               for name, path in pair.APK_PATHS.items()]
        if mutate:
            mutate(self.record)
        members['apk-pair.json'] = json.dumps(self.record).encode()
        if extra:
            members.update(extra)
        self.archive.write_bytes(zipped(members))
        self.lock.update(zip_size_bytes=self.archive.stat().st_size, zip_sha256=pair.file_digest(self.archive),
                         pair_manifest_sha256=pair.digest(members['apk-pair.json']),
                         app_apk_sha256=pair.digest(members['app.apk']), test_apk_sha256=pair.digest(members['test.apk']))

    def stage(self):
        return pair.stage_pair(self.archive, self.destination, self.lock)

    def test_valid_pair_stages_exact_bytes_without_execution(self):
        report = self.stage()
        self.assertEqual(report['status'], 'retained-pair-verified')
        self.assertFalse(report['runtime_tested'])
        self.assertEqual((self.destination / 'app.apk').read_bytes(), b'synthetic-app')
        self.assertEqual((self.destination / 'test.apk').read_bytes(), zipped(self.test_inputs))
        self.assertTrue((self.destination / 'APK-SHA256SUMS').is_file())

    def test_pending_lock_stops_before_any_network_or_staging(self):
        pending = dict(self.lock)
        pending['status'] = 'pending-successful-final-ordinary-ci'
        with patch.object(pair.urllib.request, 'build_opener') as network:
            with self.assertRaisesRegex(ValueError, 'not finalized'):
                pair.download(self.archive, pending)
            network.assert_not_called()
        with self.assertRaises(ValueError):
            pair.stage_pair(self.archive, self.destination, pending)
        self.assertFalse(self.destination.exists())

    def test_missing_hash_wrong_attempt_and_authorization_claim_fail(self):
        for change in ({'app_apk_sha256': None}, {'run_attempt': 2}, {'run_attempt': True},
                       {'input_lock_is_execution_authorization': True}, {'repository': 'other/repo'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                pair.validate_lock(dict(self.lock, **change))

    def test_zip_corruption_and_existing_destination_fail(self):
        self.archive.write_bytes(self.archive.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'ZIP size/hash'):
            self.stage()
        self.seal()
        self.destination.mkdir()
        with self.assertRaisesRegex(ValueError, 'existing staged'):
            self.stage()

    def test_wrong_commit_profile_members_or_hash_fail_before_staging(self):
        original = copy.deepcopy(self.record)
        for mutate in (lambda p: p.update(source_commit='b' * 40),
                       lambda p: p['profile']['observed_constants'].update(HY_MT_NATIVE=True),
                       lambda p: p['profile']['observed_constants'].update(SHERPA_ASR_SOURCE=1),
                       lambda p: p['apks'][0].update(sha256='f' * 64),
                       lambda p: p['apks'].append(p['apks'][0])):
            self.record = copy.deepcopy(original)
            self.seal(mutate)
            with self.assertRaises(ValueError):
                self.stage()
            self.assertFalse(self.destination.exists())

    def test_unlisted_member_or_changed_packaged_metadata_fail(self):
        self.seal(extra={'extra.txt': b'extra'})
        with self.assertRaisesRegex(ValueError, 'Unexpected artifact members'):
            self.stage()
        self.test_inputs['assets/caption-diagnosis/reference.json'] = b'{}'
        self.seal()
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            self.stage()

    def test_preparation_cannot_claim_semantic_acceptance(self):
        self.prepared['semantic_acceptance_passed'] = True
        self.seal()
        with self.assertRaisesRegex(ValueError, 'preparation mismatch'):
            self.stage()


    def test_changed_emoji_pcm_or_incomplete_comparison_preparation_fails(self):
        key = 'assets/caption-diagnosis/generated/emoji.s16le'
        self.test_inputs[key] = b'x' * 8
        self.seal()
        with self.assertRaisesRegex(ValueError, 'Emoji PCM mismatch'):
            self.stage()
        self.test_inputs[key] = self.synthetic_pcm
        self.comparison_prepared['additional_model_files'] = 1
        self.seal()
        with self.assertRaisesRegex(ValueError, 'fixed-window preparation mismatch'):
            self.stage()


