"""Offline synthetic archives only; no download, installation, emulator or ACL calls."""
import copy
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_source_runtime_pair as target


def parse_runtime_gate(value):
    """Recognize closed/one-run syntax only; this does not grant authorization."""
    value = value.strip()
    expression = re.fullmatch(r'\$\{\{\s*(.*?)\s*\}\}', value)
    if expression:
        value = expression.group(1).strip()
    if value == 'false':
        return ('closed',)
    match = re.fullmatch(r'github\.run_number\s*==\s*([1-9][0-9]*)\s*&&\s*github\.run_attempt\s*==\s*1', value)
    if not match:
        raise ValueError('Gate must be false or one positive numeric run and attempt 1')
    return ('one-run', int(match.group(1)), 1)


def validate_workflow_gate_pair(workflow):
    """Read this workflow's canonical indentation, failing on missing/duplicate gates."""
    jobs = re.findall(r'^  retained-source-runtime:\n(.*?)(?=^  \S|\Z)', workflow, re.MULTILINE | re.DOTALL)
    if len(jobs) != 1:
        raise ValueError('Expected exactly one retained-source-runtime job')
    job = jobs[0]
    job_gates = re.findall(r'^    if:\s*([^\n]+)$', job, re.MULTILINE)
    steps = re.findall(r'^      - [^\n]+\n.*?(?=^      - |\Z)', job, re.MULTILINE | re.DOTALL)
    grants = [step for step in steps if 'setfacl -m "u:$(id -u):rw" /dev/kvm' in step]
    if len(job_gates) != 1 or len(grants) != 1:
        raise ValueError('Expected one job gate and one current-UID KVM grant step')
    grant_gates = re.findall(r'^        if:\s*([^\n]+)$', grants[0], re.MULTILINE)
    if len(grant_gates) != 1:
        raise ValueError('Expected exactly one KVM grant gate')
    job_gate = parse_runtime_gate(job_gates[0])
    if job_gate != parse_runtime_gate(grant_gates[0]):
        raise ValueError('Job and KVM grant gates must be identical')
    return job_gate


class RetainedSourcePairTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / 'assets'
        self.assets.mkdir()
        specs = [('model.onnx', b'model'), ('tokens.txt', b'tokens'), ('audio.f32le', b'\0' * 8)]
        self.test_inputs = {'assets/runtime-comparison/generated/' + name: data for name, data in specs}
        manifest = {'models': [{'asset': 'runtime-comparison/generated/' + name, 'bytes': len(data), 'sha256': target.digest(data)}
                               for name, data in specs[:2]],
                    'fixtures': [{'pcm': {'asset': 'runtime-comparison/generated/audio.f32le', 'bytes': 8, 'sha256': target.digest(specs[2][1])}}]}
        self.fixture = json.dumps(manifest).encode()
        self.profile = copy.deepcopy(target.PROFILE)
        self.profile['frozen_fixture_manifest_sha256'] = target.digest(self.fixture)
        self.source_profile = json.dumps(self.profile).encode()
        (self.assets / 'manifest.json').write_bytes(self.fixture)
        (self.assets / 'source-profile.json').write_bytes(self.source_profile)
        self.enterContext(patch.object(target, 'ASSETS', self.assets))
        self.enterContext(patch.object(target, 'PROFILE', self.profile))
        self.prepared = {'status': 'passed', 'all_inputs_verified': True, 'gap_coverage_accepted': False,
                         'windows': 33, 'manifest_sha256': target.digest(self.fixture)}
        self.test_inputs.update({'assets/runtime-comparison/manifest.json': self.fixture,
                                'assets/runtime-comparison/source-profile.json': self.source_profile,
                                'assets/runtime-comparison/generated/prepared.json': json.dumps(self.prepared).encode()})
        self.members = {'debug/app-arm64-v8a-debug.apk': b'arm-app', target.APP: b'x86-app',
                        'debug/app-universal-debug.apk': b'universal-app'}
        self.source = {'repository': 'csic21/FrameNest', 'commit': 'a' * 40, 'run_id': 10, 'run_attempt': 1}
        self.lock = {'schema_version': 1, 'status': 'pinned-reviewed', **self.source, 'artifact_id': 20,
                     'artifact_name': 'source-asr-apks-' + 'a' * 40 + '-1'}
        self.pair = {'schema_version': 1, 'status': 'compiled-static-verified-not-runtime-tested', 'source': self.source,
                     'profile': {'upstream_runtime_version_expected': '1.13.8', 'upstream_runtime_version_observed': None,
                                 'runtime_identity': self.profile['runtime_identity'], 'aar_sha256': self.profile['aar_sha256'],
                                 'hy_mt_native': False, 'supplemental_notice_sha256': 'b' * 64},
                     'fixture_manifest_sha256': target.digest(self.fixture), 'windows': 33, 'runtime_tested': False}
        self.archive = self.root / 'retained.zip'
        self.destination = self.root / 'verified'
        self.seal()

    @staticmethod
    def zipped(members):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name, data in members.items():
                archive.writestr(name, data)
        return output.getvalue()

    @staticmethod
    def row(path, data):
        return {'path': path, 'size_bytes': len(data), 'sha256': target.digest(data)}

    def seal(self, alter_pair=None, alter_audit=None):
        self.members[target.TEST] = self.zipped(self.test_inputs)
        abis = [['arm64-v8a'], ['x86_64'], ['arm64-v8a', 'x86_64']]
        apps = [dict(self.row(name, self.members[name]), kind='app', abis=abi)
                for name, abi in zip(list(self.members)[:3], abis)]
        audit = {'status': 'static-content-checks-passed', 'aar_sha256': self.profile['aar_sha256'], 'apks': copy.deepcopy(apps)}
        for row in audit['apks']:
            row['source_profile_supplement_verified'] = True
        if alter_audit:
            alter_audit(audit)
        self.members.update({'apk-static-audit.json': json.dumps(audit).encode(),
                             'input-preparation.json': json.dumps(self.prepared).encode(),
                             'fixture-manifest.json': self.fixture, 'source-profile.json': self.source_profile})
        self.pair['apks'] = apps + [dict(self.row(target.TEST, self.members[target.TEST]), kind='instrumentation')]
        self.pair['evidence_files'] = [self.row(name, self.members[name]) for name in sorted(target.EVIDENCE)]
        if alter_pair:
            alter_pair(self.pair)
        pair_bytes = json.dumps(self.pair).encode()
        self.members['source-app-pair.json'] = pair_bytes
        self.archive.write_bytes(self.zipped(self.members))
        self.lock.update(zip_size_bytes=self.archive.stat().st_size, zip_sha256=target.file_digest(self.archive),
                         pair_manifest_sha256=target.digest(pair_bytes), app_apk_sha256=target.digest(self.members[target.APP]),
                         test_apk_sha256=target.digest(self.members[target.TEST]))

    def stage(self):
        return target.stage_pair(self.archive, self.destination, self.lock)

    def test_valid_pair_stages_exact_selected_bytes_and_all_evidence(self):
        report = self.stage()
        self.assertEqual(report['status'], 'retained-pair-verified')
        self.assertFalse(report['runtime_tested'])
        self.assertEqual((self.destination / 'app.apk').read_bytes(), b'x86-app')
        self.assertEqual((self.destination / 'test.apk').read_bytes(), self.members[target.TEST])
        self.assertTrue((self.destination / 'APK-SHA256SUMS').exists())

    def test_pending_lock_is_closed(self):
        pending = json.loads(target.DEFAULT_LOCK.read_text())
        pending['status'] = 'pending-successful-final-ordinary-ci'
        with self.assertRaisesRegex(ValueError, 'not finalized'):
            target.validate_lock(pending)

    def test_finalized_pin_set_is_valid_independently_of_execution_gate(self):
        target.validate_lock(self.lock)

    def test_outer_corruption_fails_without_staging(self):
        self.archive.write_bytes(self.archive.read_bytes() + b'corrupt')
        with self.assertRaisesRegex(ValueError, 'ZIP size/hash'):
            self.stage()
        self.assertFalse(self.destination.exists())

    def test_existing_destination_is_never_replaced(self):
        self.destination.mkdir()
        (self.destination / 'keep').write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError, 'existing staged'):
            self.stage()
        self.assertEqual((self.destination / 'keep').read_bytes(), b'keep')

    def test_source_commit_and_profile_mismatch_are_rejected(self):
        for change in (lambda p: p['source'].update(commit='c' * 40),
                       lambda p: p['profile'].update(hy_mt_native=True),
                       lambda p: p['profile'].update(upstream_runtime_version_observed='1.13.8')):
            original = copy.deepcopy(self.pair)
            self.seal(alter_pair=change)
            with self.assertRaises(ValueError):
                self.stage()
            self.pair = original

    def test_unlisted_zip_member_is_rejected(self):
        self.members['extra.json'] = b'{}'
        self.seal()
        with self.assertRaisesRegex(ValueError, 'Unexpected artifact members'):
            self.stage()

    def test_duplicate_manifest_member_is_rejected(self):
        self.seal(alter_pair=lambda p: p['evidence_files'].append(p['evidence_files'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate manifest'):
            self.stage()

    def test_audit_of_a_different_app_is_rejected(self):
        self.seal(alter_audit=lambda a: a['apks'][0].update(sha256='e' * 64))
        with self.assertRaisesRegex(ValueError, 'does not cover'):
            self.stage()

    def test_different_prepared_model_is_rejected_even_with_resealed_apk(self):
        self.test_inputs['assets/runtime-comparison/generated/model.onnx'] = b'other'
        self.seal()
        with self.assertRaisesRegex(ValueError, 'frozen input mismatch'):
            self.stage()
        self.assertFalse(self.destination.exists())

    def test_missing_packaged_pcm_is_rejected(self):
        del self.test_inputs['assets/runtime-comparison/generated/audio.f32le']
        self.seal()
        with self.assertRaisesRegex(ValueError, 'Missing ZIP entry'):
            self.stage()

    def test_gap_coverage_or_wrong_window_count_cannot_pass(self):
        self.prepared['gap_coverage_accepted'] = True
        self.seal()
        with self.assertRaisesRegex(ValueError, 'preparation evidence'):
            self.stage()

    def test_workflow_and_kvm_gates_are_closed_or_identical_one_run_only(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/asr-source-runtime.yml').read_text()
        validate_workflow_gate_pair(workflow)
        self.assertNotIn('./gradlew', workflow)
        self.assertIn('accept-android-sdk-licenses: false', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        subprocess.run(['bash', '-n', str(root / 'scripts/run_source_runtime_sessions.sh')], check=True)


class RuntimeGateGrammarTest(unittest.TestCase):
    @staticmethod
    def workflow(job_gate, grant_gate):
        return ('jobs:\n  retained-source-runtime:\n    if: ' + job_gate + '\n'
                '    steps:\n      - name: Current UID KVM grant\n        if: ' + grant_gate + '\n'
                '        run: |\n          sudo -n setfacl -m "u:$(id -u):rw" /dev/kvm\n')

    def test_both_closed_are_supported(self):
        self.assertEqual(validate_workflow_gate_pair(self.workflow('${{ false }}', 'false')), ('closed',))

    def test_exact_matching_one_run_attempt_one_is_supported(self):
        gate = 'github.run_number == 17 && github.run_attempt == 1'
        self.assertEqual(validate_workflow_gate_pair(self.workflow(gate, '${{ ' + gate + ' }}')), ('one-run', 17, 1))

    def test_broad_or_incomplete_gates_are_rejected(self):
        for gate in ('true', '${{ true }}', 'always()', 'github.run_number == 17',
                     'github.run_number >= 17 && github.run_attempt == 1',
                     'github.run_number == 17 && github.run_attempt <= 1',
                     'github.run_number == 17 && github.run_attempt == 2',
                     'github.run_number == 17 && github.run_attempt == 1 || true',
                     'github.run_number == 0 && github.run_attempt == 1',
                     'github.run_number == 017 && github.run_attempt == 1',
                     "github.run_number == '17' && github.run_attempt == 1"):
            with self.subTest(gate=gate), self.assertRaises(ValueError):
                validate_workflow_gate_pair(self.workflow(gate, gate))

    def test_mixed_closed_or_different_run_numbers_are_rejected(self):
        gate = 'github.run_number == 17 && github.run_attempt == 1'
        for left, right in (('false', gate), (gate, 'false'), (gate, gate.replace('17', '18'))):
            with self.subTest(left=left, right=right), self.assertRaises(ValueError):
                validate_workflow_gate_pair(self.workflow(left, right))

    def test_missing_or_duplicate_job_and_grant_gates_are_rejected(self):
        original = self.workflow('false', 'false')
        for text in (original.replace('    if: false\n', '', 1),
                     original.replace('        if: false\n', ''),
                     original.replace('    if: false\n', '    if: false\n    if: false\n', 1),
                     original.replace('        if: false\n', '        if: false\n        if: false\n')):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_workflow_gate_pair(text)


if __name__ == '__main__':
    unittest.main()
