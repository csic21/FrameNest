import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import fixed_caption_window_schema as schema
import test_caption_diagnosis as base

SPEC = importlib.util.spec_from_file_location('prepare_fixed_windows', ROOT / 'scripts/prepare-fixed-caption-window-comparison.py')
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)
ASSETS = ROOT / 'app/src/androidTest/assets/caption-diagnosis'


class FixedCaptionWindowSchemaTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ASSETS / 'fixed-window-comparison.json').read_bytes())

    def test_pins_reuse_existing_complete_emoji_and_only_two_conditions(self):
        schema.validate_comparison(self.manifest)
        existing = json.loads((ROOT / 'app/src/androidTest/assets/runtime-comparison/manifest.json').read_bytes())['fixtures'][0]
        self.assertEqual(self.manifest['window_ms'], [3000, 6000])
        self.assertEqual(self.manifest['emoji']['media'], existing['video'])
        self.assertEqual(self.manifest['emoji']['pcm']['sha256'], existing['pcm']['s16le_sha256'])
        self.assertEqual(self.manifest['emoji']['duration_ms'] * 16, existing['pcm']['samples'])

    def test_sweeps_shortened_emoji_changed_sources_and_false_gold_fail(self):
        mutations = [
            lambda m: m.update(window_ms=[3000, 4000, 6000]),
            lambda m: m.update(window_ms=[3000.0, 6000]),
            lambda m: m.update(context_padding_ms=500),
            lambda m: m.update(timeout_ms=1200001),
            lambda m: m['emoji'].update(duration_ms=114000),
            lambda m: m['emoji'].update(original_media_start_ms=3000),
            lambda m: m['emoji'].update(reference_is_verbatim_gold=True),
            lambda m: m['emoji'].update(media_url='https://example.org/emoji'),
            lambda m: m['emoji']['pcm'].update(samples=1823361),
            lambda m: m['emoji']['pcm'].update(file='../emoji.pcm'),
            lambda m: m['emoji']['pcm'].update(sha256='0' * 64),
        ]
        for mutate in mutations:
            candidate = copy.deepcopy(self.manifest)
            mutate(candidate)
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                schema.validate_comparison(candidate)

    def test_gate_and_single_model_and_no_paced_wait(self):
        source = (ROOT / 'app/src/androidTest/java/com/framenest/feature/listen_translate/FixedCaptionWindowComparisonTest.kt').read_text()
        self.assertLess(source.index('assumeTrue(arguments.getString("fixedCaptionWindowComparison")'), source.index('BuildConfig.SHERPA_ASR_SOURCE'))
        self.assertLess(source.index('BuildConfig.SHERPA_ASR_SOURCE'), source.index('assets.open('))
        self.assertIn('withTimeout(20 * 60_000L)', source)
        self.assertEqual(source.count('SherpaAsrEngine()'), 1)
        self.assertEqual(source.count('MlKitMtEngine(allowMeteredDownloads = true)'), 1)
        self.assertNotIn('delay(', source)
        self.assertNotIn('experimentalJapaneseSegments = true', source)
        self.assertIn('val result = delegate.recognize(pcm16kMono)', source)
        self.assertIn('StandardCopyOption.ATOMIC_MOVE', source)
        self.assertLess(source.index('status\", \"verifying_inputs'), source.index('BuildConfig.SHERPA_ASR_SOURCE'))
        self.assertIn('asr.onUpdate = { checkpoint() }; mt.onUpdate = { checkpoint() }', source)


class FixedCaptionWindowPreparationTest(unittest.TestCase):
    def setUp(self):
        # Reuse tiny existing diagnosis setup; fake bytes never reach ASR or MT.
        base.CaptionDiagnosisPreparationTest.setUp(self)
        base.prepare.prepare(self.inputs, self.models, self.assets)
        comparison = json.loads((ASSETS / 'fixed-window-comparison.json').read_bytes())
        self.emoji_pcm = bytes(1823360 * 2)
        self.emoji_media = b'synthetic-emoji-original'
        for key, data in [('media', self.emoji_media), ('pcm', self.emoji_pcm)]:
            comparison['emoji'][key].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.comparison = comparison
        self.emoji = comparison['emoji']
        (self.assets / 'fixed-window-comparison.json').write_text(json.dumps(comparison))
        self.addCleanup(patch.stopall)
        patch.object(prepare, 'validate_comparison', side_effect=lambda value: value).start()
        (self.inputs / 'emoji.webm').write_bytes(self.emoji_media)
        (self.inputs / 'emoji.s16le').write_bytes(self.emoji_pcm)

    put = base.CaptionDiagnosisPreparationTest.put
    save = base.CaptionDiagnosisPreparationTest.save

    def test_adds_only_one_pcm_and_metadata_with_no_new_model_or_network(self):
        before = {p.name for p in (self.assets / 'generated').iterdir()}
        with patch.object(prepare.urllib.request, 'urlopen', side_effect=AssertionError('No default networking')):
            result = prepare.prepare(self.inputs, self.assets)
        self.assertEqual({p.name for p in (self.assets / 'generated').iterdir()} - before, {'emoji.s16le', 'comparison-prepared.json'})
        self.assertEqual(result['additional_model_files'], 0)
        self.assertEqual(result['window_ms'], [3000, 6000])
        self.assertEqual(result['diagnosis_prepared_sha256'], hashlib.sha256((self.assets / 'generated/prepared.json').read_bytes()).hexdigest())
        with self.assertRaises(ValueError): prepare.prepare(self.inputs, self.assets)

    def test_corrupt_existing_model_or_emoji_is_not_silently_repaired(self):
        for name in ['model.int8.onnx', 'tiny.pcm']:
            target = self.assets / 'generated' / name
            original = target.read_bytes()
            target.write_bytes(b'x' * len(original))
            with self.assertRaises(ValueError): prepare.prepare(self.inputs, self.assets)
            target.write_bytes(original)
        (self.inputs / 'emoji.s16le').write_bytes(b'x' * len(self.emoji_pcm))
        with self.assertRaises(ValueError): prepare.prepare(self.inputs, self.assets)
        self.assertFalse((self.assets / 'generated/comparison-prepared.json').exists())
        self.assertFalse((self.assets / 'generated/emoji.s16le').exists())

    def test_full_wav_sample_extraction_does_not_resample(self):
        (self.inputs / 'emoji.s16le').unlink()
        with wave.open(str(self.inputs / 'emoji.wav'), 'wb') as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(self.emoji_pcm)
        with patch.object(prepare.subprocess, 'run', side_effect=AssertionError('Existing WAV needs no decode')):
            prepare.prepare(self.inputs, self.assets)
        self.assertEqual((self.assets / 'generated/emoji.s16le').read_bytes(), self.emoji_pcm)

    def test_full_decode_has_no_seek_truncation_or_duration_override(self):
        (self.inputs / 'emoji.s16le').unlink()
        def decode(command, **kwargs):
            self.assertNotIn('-ss', command); self.assertNotIn('-t', command); self.assertNotIn('-to', command)
            Path(command[-1]).write_bytes(self.emoji_pcm)
        with patch.object(prepare.subprocess, 'run', side_effect=decode) as call:
            prepare.prepare(self.inputs, self.assets)
        call.assert_called_once()

    def test_short_wav_and_wrong_full_decode_fail_closed(self):
        (self.inputs / 'emoji.s16le').unlink()
        with wave.open(str(self.inputs / 'emoji.wav'), 'wb') as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000); wav.writeframes(self.emoji_pcm[:-32])
        with self.assertRaises(ValueError): prepare.prepare(self.inputs, self.assets)
        (self.inputs / 'emoji.wav').unlink()
        def decode(command, **kwargs): Path(command[-1]).write_bytes(b'x' * len(self.emoji_pcm))
        with patch.object(prepare.subprocess, 'run', side_effect=decode), self.assertRaises(ValueError):
            prepare.prepare(self.inputs, self.assets)
        self.assertFalse((self.inputs / 'emoji.s16le').exists())

    def test_download_is_bounded_and_corrupt_existing_original_is_not_replaced(self):
        (self.inputs / 'emoji.webm').write_bytes(b'bad')
        with patch.object(prepare.urllib.request, 'urlopen', side_effect=AssertionError('No repair')):
            with self.assertRaises(ValueError): prepare.download_inputs(self.inputs, self.assets)
        (self.inputs / 'emoji.webm').unlink()
        with patch.object(prepare.urllib.request, 'urlopen', return_value=io.BytesIO(b'x' * 100)):
            with self.assertRaises(ValueError): prepare.download_inputs(self.inputs, self.assets)
        self.assertFalse((self.inputs / 'emoji.webm').exists())


if __name__ == '__main__': unittest.main()
