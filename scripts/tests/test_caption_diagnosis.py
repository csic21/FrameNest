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
sys.path.insert(0, str(ROOT / "scripts"))
import caption_diagnosis_schema as schema

SPEC = importlib.util.spec_from_file_location("prepare_caption_diagnosis", ROOT / "scripts/prepare-caption-diagnosis.py")
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)
ASSETS = ROOT / "app/src/androidTest/assets/caption-diagnosis"


class CaptionDiagnosisSchemaTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ASSETS / "manifest.json").read_bytes())
        self.reference = json.loads((ASSETS / "reference.json").read_bytes())

    def test_pinned_reference_matches_manually_grouped_published_wording(self):
        schema.validate_manifest(self.manifest)
        schema.validate_reference(self.manifest, self.reference)
        schema.verify_file(ASSETS, self.manifest["reference"])
        self.assertEqual(len(self.manifest["units"]), 4)
        self.assertEqual(self.manifest["pcm"]["samples"], 763520)
        self.assertFalse(self.reference["is_verbatim_gold"])

    def test_invalid_ranges_types_provenance_paths_and_models_fail_closed(self):
        mutations = [
            lambda m: m.update(schema_version=True),
            lambda m: m.update(duration_ms=120001),
            lambda m: m.update(source_language="en"),
            lambda m: m.update(media_url="https://private.example/video"),
            lambda m: m["reference"].update(verbatim_gold=True),
            lambda m: m["reference"].update(kind="gold"),
            lambda m: m["pcm"].update(bytes=1),
            lambda m: m["pcm"].update(file="../secret"),
            lambda m: m["models"][0].update(sha256="0" * 64),
            lambda m: m["units"][0].update(start_ms=-1),
            lambda m: m["units"][0].update(end_ms=30001),
            lambda m: m["units"][1].update(start_ms=0),
            lambda m: m["units"][1].update(id=m["units"][0]["id"]),
            lambda m: m["units"][0].update(original_start_ms=0),
            lambda m: m["units"][0].update(text=""),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                candidate = copy.deepcopy(self.manifest)
                mutate(candidate)
                with self.assertRaises((ValueError, KeyError)):
                    schema.validate_manifest(candidate)

    def test_reference_edits_or_partial_cue_splits_are_not_silently_accepted(self):
        for field, value in [("text", "invented replacement"), ("end_ms", 8000), ("cue_ids", [17])]:
            candidate = copy.deepcopy(self.manifest)
            candidate["units"][0][field] = value
            with self.assertRaises(ValueError):
                schema.validate_reference(candidate, self.reference)
        reference = copy.deepcopy(self.reference)
        reference["cues"][0]["text"] = "changed public wording"
        with self.assertRaises(ValueError):
            schema.validate_reference(self.manifest, reference)

    def test_hash_and_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "input"
            source.mkdir()
            (root / "other").write_bytes(b"abc")
            (source / "x").symlink_to(root / "other")
            spec = {"file": "x", "bytes": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}
            with self.assertRaises(ValueError):
                schema.verify_file(source, spec)
            (source / "x").unlink()
            (source / "x").write_bytes(b"abd")
            with self.assertRaises(ValueError):
                schema.verify_file(source, spec)

    def test_source_binary_profile_reuses_exact_released_native_pins(self):
        profile = json.loads((ASSETS / "source-profile.json").read_bytes())
        existing = json.loads((ROOT / "app/src/androidTest/assets/runtime-comparison/source-profile.json").read_bytes())
        for key in ("aar_sha256", "runtime_identity", "native_members", "apk_assets"):
            self.assertEqual(profile[key], existing[key])
        self.assertNotIn("frozen_fixture_manifest_sha256", profile)

    def test_explicit_gate_precedes_asset_or_model_access(self):
        source = (ROOT / "app/src/androidTest/java/com/framenest/feature/listen_translate/CaptionDiagnosisTest.kt").read_text()
        self.assertLess(source.index('assumeTrue(arguments.getString("captionDiagnosis")'), source.index("BuildConfig.SHERPA_ASR_SOURCE"))
        self.assertLess(source.index("BuildConfig.SHERPA_ASR_SOURCE"), source.index("assets.open("))
        self.assertIn('require(arguments.getString("captionDiagnosisNetwork") == "true")', source)
        self.assertIn('val result = delegate.recognize(pcm16kMono)', source)
        self.assertNotIn('experimentalJapaneseSegments = true', source)


class CaptionDiagnosisPreparationTest(unittest.TestCase):
    """Tiny synthetic files test packaging only; they never reach a recognizer."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.assets = self.root / "assets"
        self.inputs = self.root / "inputs"
        self.models = self.root / "models"
        for path in (self.assets, self.inputs, self.models): path.mkdir()
        self.manifest = json.loads((ASSETS / "manifest.json").read_bytes())
        self.manifest["duration_ms"] = 1
        unit = dict(self.manifest["units"][0], start_ms=0, end_ms=1, original_end_ms=113461, text="説明。", cue_ids=[1])
        self.manifest["units"] = [unit]
        reference = json.loads((ASSETS / "reference.json").read_bytes())
        reference.update(duration_ms=1, units=[unit], cues=[{"id": 1, "local_start_ms": 0, "local_end_ms": 1, "text": "説明。"}])
        self.put(self.assets, "reference.json", json.dumps(reference, ensure_ascii=False).encode(), "reference")
        self.put(self.inputs, "original.webm", b"public-synthetic-input", "media")
        self.put(self.inputs, "tiny.pcm", bytes(range(32)), "pcm")
        self.manifest["pcm"]["samples"] = 16
        self.fake_models = {}
        self.manifest["models"] = []
        for name in ("model.int8.onnx", "tokens.txt"):
            data = ("synthetic-no-inference-" + name).encode()
            (self.models / name).write_bytes(data)
            spec = {"file": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            self.manifest["models"].append(spec)
            self.fake_models[name] = (len(data), spec["sha256"])
        self.addCleanup(patch.stopall)
        patch.dict(schema.MODEL_HASHES, self.fake_models, clear=True).start()
        (self.assets / "source-profile.json").write_bytes((ASSETS / "source-profile.json").read_bytes())
        self.save()

    def put(self, root, name, data, key):
        (root / name).write_bytes(data)
        self.manifest[key].update(file=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    def save(self):
        (self.assets / "manifest.json").write_text(json.dumps(self.manifest))

    def test_stages_only_required_inputs_without_network_or_existing_output_overwrite(self):
        with patch.object(prepare.urllib.request, "urlopen", side_effect=AssertionError("No default networking")):
            result = prepare.prepare(self.inputs, self.models, self.assets)
        self.assertTrue(result["all_inputs_verified"])
        self.assertIsNone(result["semantic_acceptance_passed"])
        self.assertEqual({p.name for p in (self.assets / "generated").iterdir()}, {"tiny.pcm", "reference.json", "model.int8.onnx", "tokens.txt", "prepared.json"})
        with self.assertRaises(ValueError):
            prepare.prepare(self.inputs, self.models, self.assets)

    def test_corrupt_input_leaves_no_ready_output(self):
        (self.inputs / "tiny.pcm").write_bytes(b"x" * 32)
        with self.assertRaises(ValueError):
            prepare.prepare(self.inputs, self.models, self.assets)
        self.assertFalse((self.assets / "generated").exists())

    def test_existing_corrupt_original_is_not_redownloaded_or_replaced(self):
        (self.inputs / "original.webm").write_bytes(b"bad")
        with patch.object(prepare.urllib.request, "urlopen", side_effect=AssertionError("No repair by silent replacement")):
            with self.assertRaises(ValueError):
                prepare.download_inputs(self.inputs, self.assets)
        self.assertEqual((self.inputs / "original.webm").read_bytes(), b"bad")

    def test_reproduction_uses_original_seek_duration_and_checks_pcm_hash(self):
        (self.inputs / "tiny.pcm").unlink()
        def reproduce(command, **kwargs):
            self.assertEqual(command[command.index("-ss") + 1], "113.460")
            self.assertEqual(command[command.index("-t") + 1], "0.001")
            Path(command[-1]).write_bytes(bytes(range(32)))
        with patch.object(prepare.subprocess, "run", side_effect=reproduce) as call:
            prepare.download_inputs(self.inputs, self.assets)
        call.assert_called_once()
        self.assertEqual((self.inputs / "tiny.pcm").read_bytes(), bytes(range(32)))

    def test_oversize_download_and_different_decode_are_never_promoted(self):
        (self.inputs / "original.webm").unlink()
        with patch.object(prepare.urllib.request, "urlopen", return_value=io.BytesIO(b"x" * 100)):
            with self.assertRaises(ValueError):
                prepare.download_inputs(self.inputs, self.assets)
        self.assertFalse((self.inputs / "original.webm").exists())
        (self.inputs / "original.webm").write_bytes(b"public-synthetic-input")
        (self.inputs / "tiny.pcm").unlink()
        def wrong_decode(command, **kwargs): Path(command[-1]).write_bytes(b"x" * 32)
        with patch.object(prepare.subprocess, "run", side_effect=wrong_decode):
            with self.assertRaises(ValueError):
                prepare.download_inputs(self.inputs, self.assets)
        self.assertFalse((self.inputs / "tiny.pcm").exists())

    def test_wav_container_hash_is_provenance_but_pcm_remains_exact(self):
        with wave.open(str(self.inputs / self.manifest["wav"]["file"]), "wb") as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000)
            wav.writeframes(bytes(range(32)))
        result = prepare.prepare(self.inputs, self.models, self.assets)
        self.assertTrue(result["optional_wav"]["pcm_equal"])
        self.assertNotEqual(result["optional_wav"]["sha256"], self.manifest["wav"]["sha256"])


if __name__ == "__main__":
    unittest.main()
