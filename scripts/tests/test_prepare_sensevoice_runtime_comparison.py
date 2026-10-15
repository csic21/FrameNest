import copy
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import wave


SCRIPT = Path(__file__).resolve().parents[1] / "prepare-sensevoice-runtime-comparison.py"
SPEC = importlib.util.spec_from_file_location("prepare_runtime_comparison", SCRIPT)
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


class RuntimeComparisonPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.fixtures = self.source / "fixtures"
        self.models = self.source / "models/sensevoice"
        self.assets = self.root / "assets"
        self.fixtures.mkdir(parents=True)
        self.models.mkdir(parents=True)
        self.values = [-32768, -16384, -1, 0, 1, 16384, 32767]
        self.s16 = struct.pack("<7h", *self.values)
        self.f32 = struct.pack("<7f", *(v / 32768 for v in self.values))
        wav_path = self.fixtures / "test.wav"
        with wave.open(str(wav_path), "wb") as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(16000)
            stream.writeframes(self.s16)
        (self.fixtures / "test.webm").write_bytes(b"synthetic-public-video-for-unit-test")
        (self.models / "model.int8.onnx").write_bytes(b"synthetic-model-not-for-inference")
        model = self.file_spec(self.models / "model.int8.onnx", "sensevoice/model.int8.onnx")
        model.update(url="https://example.invalid/model", asset="runtime-comparison/generated/model.int8.onnx")
        pcm = {"asset": "runtime-comparison/generated/test.f32le", "samples": 7,
               "bytes": len(self.f32), "sha256": prepare.digest(self.f32),
               "s16le_sha256": prepare.digest(self.s16)}
        self.manifest = {"schema_version": 1,
                         "fixtures": [{"id": "test", "media_url": "https://example.invalid/video",
                                       "video": self.file_spec(self.fixtures / "test.webm"),
                                       "wav": self.file_spec(wav_path), "pcm": pcm}],
                         "models": [model],
                         "samples": [{"id": "test", "fixture_id": "test", "extent_samples": 7,
                                      "rows": [{"index": 0, "start_sample": 1, "end_sample": 6,
                                                "f32le_sha256": prepare.digest(self.f32[4:24])}],
                                      "gaps": [{"start_sample": 0, "end_sample": 1, "status": "excluded"}]}]}
        self.manifest_path = self.root / "manifest.json"
        self.write_manifest()

    @staticmethod
    def file_spec(path, name=None):
        return {"file": name or path.name, "bytes": path.stat().st_size,
                "sha256": prepare.digest(path.read_bytes())}

    def write_manifest(self):
        self.manifest_path.write_text(json.dumps(self.manifest))

    def run_prepare(self):
        return prepare.prepare(self.source, self.assets, manifest_path=self.manifest_path)

    def test_exact_signed_pcm_edges_roundtrip_and_ready_manifest(self):
        ready = self.run_prepare()
        generated = self.assets / "runtime-comparison/generated"
        self.assertEqual((generated / "test.f32le").read_bytes(), self.f32)
        self.assertEqual(ready["manifest_sha256"], prepare.digest(self.manifest_path.read_bytes()))
        self.assertEqual(ready["windows"], 1)
        self.assertEqual(ready["production_float_roundtrip_max_abs_error"], 0.0)
        self.assertFalse(ready["gap_coverage_accepted"])
        self.assertEqual(json.loads((generated / "prepared.json").read_text()), ready)
        self.assertEqual(self.run_prepare(), ready)

    def test_same_length_changed_source_fails_before_any_output(self):
        video = self.fixtures / "test.webm"
        video.write_bytes(b"x" * video.stat().st_size)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.run_prepare()
        self.assertFalse(self.assets.exists())

    def test_truncated_model_fails_before_output(self):
        (self.models / "model.int8.onnx").write_bytes(b"short")
        with self.assertRaisesRegex(ValueError, "length"):
            self.run_prepare()
        self.assertFalse(self.assets.exists())

    def test_signed_and_float_content_hashes_are_independent_checks(self):
        pcm = self.manifest["fixtures"][0]["pcm"]
        for key in ["s16le_sha256", "sha256"]:
            with self.subTest(key=key):
                spec = dict(pcm, **{key: "0" * 64})
                with self.assertRaisesRegex(ValueError, "hash mismatch"):
                    prepare.read_exact_pcm(self.fixtures / "test.wav", spec)

    def test_wrong_channel_rate_and_encoding_rejected(self):
        for channels, width, rate in [(2, 2, 16000), (1, 1, 16000), (1, 2, 48000)]:
            with self.subTest(channels=channels, width=width, rate=rate):
                path = self.fixtures / "bad.wav"
                with wave.open(str(path), "wb") as stream:
                    stream.setnchannels(channels)
                    stream.setsampwidth(width)
                    stream.setframerate(rate)
                    stream.writeframes(bytes(28))
                with self.assertRaisesRegex(ValueError, "mono 16 kHz"):
                    prepare.read_exact_pcm(path, self.manifest["fixtures"][0]["pcm"])

    def test_bad_window_bounds_overlap_and_hash_rejected(self):
        for updates in [{"start_sample": -1}, {"end_sample": 8}, {"end_sample": 1},
                        {"start_sample": 1.0}, {"index": 1}, {"f32le_sha256": "0" * 64}]:
            with self.subTest(updates=updates):
                manifest = copy.deepcopy(self.manifest)
                manifest["samples"][0]["rows"][0].update(updates)
                with self.assertRaises(ValueError):
                    prepare.verify_windows(manifest, {"test": self.f32})
        manifest = copy.deepcopy(self.manifest)
        manifest["samples"][0]["rows"].append(dict(manifest["samples"][0]["rows"][0], index=1))
        with self.assertRaisesRegex(ValueError, "nonoverlapping"):
            prepare.verify_windows(manifest, {"test": self.f32})

    def test_duplicate_sample_and_extent_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["samples"].append(copy.deepcopy(manifest["samples"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            prepare.verify_windows(manifest, {"test": self.f32})
        self.manifest["samples"][0]["extent_samples"] = 8
        with self.assertRaisesRegex(ValueError, "extent"):
            prepare.verify_windows(self.manifest, {"test": self.f32})

    def test_missing_inputs_do_not_download_implicitly(self):
        (self.fixtures / "test.webm").unlink()
        with patch.object(prepare.urllib.request, "urlopen") as network:
            with self.assertRaises(ValueError):
                self.run_prepare()
            network.assert_not_called()

    def test_download_integrity_limit_and_atomic_publication(self):
        data = b"reviewed-public-content"
        path = self.root / "download.webm"
        spec = {"bytes": len(data), "sha256": prepare.digest(data)}
        for content in [data + b"extra", b"x" * len(data), data[:-1]]:
            with self.subTest(content=content), patch.object(prepare.urllib.request, "urlopen", return_value=io.BytesIO(content)):
                with self.assertRaises(ValueError):
                    prepare.download_verified("https://example.invalid/video", path, spec)
                self.assertFalse(path.exists())
                self.assertEqual(list(self.root.glob("*.part")), [])
        with patch.object(prepare.urllib.request, "urlopen", return_value=io.BytesIO(data)):
            prepare.download_verified("https://example.invalid/video", path, spec)
        self.assertEqual(path.read_bytes(), data)
        path.write_bytes(b"x" * len(data))
        with patch.object(prepare.urllib.request, "urlopen") as network:
            with self.assertRaises(ValueError):
                prepare.download_verified("https://example.invalid/video", path, spec)
            network.assert_not_called()

    def test_changed_ffmpeg_wav_is_not_accepted_or_published(self):
        destination = self.fixtures / "extracted.wav"
        spec = self.manifest["fixtures"][0]["wav"]
        def changed_output(command, **kwargs):
            Path(command[-1]).write_bytes(b"x" * spec["bytes"])
        with patch.object(prepare.subprocess, "run", side_effect=changed_output):
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                prepare.extract_wav(self.fixtures / "test.webm", destination, spec)
        self.assertFalse(destination.exists())
        self.assertFalse(destination.with_suffix(".partial.wav").exists())

    def test_paths_cannot_escape_or_follow_external_symlink(self):
        for relative in ["../outside", "/absolute"]:
            with self.assertRaises(ValueError):
                prepare.checked_path(self.fixtures, relative)
        (self.fixtures / "escape").symlink_to(self.models, target_is_directory=True)
        with self.assertRaises(ValueError):
            prepare.checked_path(self.fixtures, "escape/model.int8.onnx")

    def test_checked_in_manifest_has_public_attribution_and_fixed_extent(self):
        manifest = json.loads(prepare.MANIFEST.read_text())
        self.assertEqual(manifest["allowed_runtimes"], ["1.12.32", "1.13.8"])
        self.assertEqual(manifest["recognizer"], {"language": "ja", "use_itn": True,
                         "decoding_method": "greedy_search", "max_active_paths": 4,
                         "provider": "cpu", "num_threads": 2, "sample_rate_hz": 16000})
        self.assertEqual([(s["id"], len(s["rows"])) for s in manifest["samples"]],
                         [("emoji", 13), ("kishida", 12), ("kishida114", 8)])
        for fixture in manifest["fixtures"]:
            self.assertTrue(fixture["source_url"].startswith("https://commons.wikimedia.org/"))
            self.assertTrue(fixture["author"])
            self.assertIn(fixture["license"], ["CC-BY-SA-4.0", "CC-BY-4.0"])
            self.assertTrue(fixture["license_url"].startswith("https://creativecommons.org/"))
        self.assertTrue(all("not decoded" in gap["status"] for sample in manifest["samples"] for gap in sample["gaps"]))


if __name__ == "__main__":
    unittest.main()
