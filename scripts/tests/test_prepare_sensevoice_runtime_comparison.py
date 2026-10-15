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

    @staticmethod
    def wav_with_info(wav_bytes, value=b"Lavf60.16.100\0"):
        info = b"INFOISFT" + struct.pack("<I", len(value)) + value + b"\0" * (len(value) % 2)
        chunk = b"LIST" + struct.pack("<I", len(info)) + info
        # The unit fixture from wave has fmt/data only. Keep both chunks exact.
        body = wav_bytes[8:36] + chunk + wav_bytes[36:]
        return b"RIFF" + struct.pack("<I", len(body)) + body

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
            with self.assertRaisesRegex(ValueError, "WAV verification failed"):
                prepare.extract_wav(self.fixtures / "test.webm", destination, spec, self.manifest["fixtures"][0]["pcm"])
        self.assertFalse(destination.exists())
        self.assertFalse(destination.with_suffix(".partial.wav").exists())

    def test_metadata_only_difference_keeps_pcm_and_reports_actual_container(self):
        path = self.fixtures / "test.wav"
        reference_bytes = path.read_bytes()
        for value in [b"Lavf60.16.100\0", b"Longer different encoder metadata\0"]:
            with self.subTest(metadata=value):
                variant = self.wav_with_info(reference_bytes, value)
                path.write_bytes(variant)
                ready = self.run_prepare()
                provenance = ready["wav_container_provenance"][0]
                self.assertTrue(provenance["metadata_only_exception_used"])
                self.assertFalse(provenance["reference_container_matches"])
                self.assertEqual(provenance["actual_container_sha256"], prepare.digest(variant))
                self.assertEqual(provenance["reference_container_sha256"], prepare.digest(reference_bytes))
                self.assertEqual(provenance["f32le_sha256"], prepare.digest(self.f32))
                self.assertEqual(provenance["s16le_sha256"], prepare.digest(self.s16))
                self.assertEqual(path.read_bytes(), variant, "Existing metadata-only WAV is not rewritten")

    def test_extraction_accepts_only_verified_pcm_despite_metadata_difference(self):
        fixture = self.manifest["fixtures"][0]
        original = (self.fixtures / "test.wav").read_bytes()
        destination = self.fixtures / "extracted.wav"
        variant = self.wav_with_info(original)
        def ffmpeg_metadata_variant(command, **kwargs):
            Path(command[-1]).write_bytes(variant)
        with patch.object(prepare.subprocess, "run", side_effect=ffmpeg_metadata_variant):
            prepare.extract_wav(self.fixtures / "test.webm", destination, fixture["wav"], fixture["pcm"])
        self.assertEqual(destination.read_bytes(), variant)

    def test_corrupt_pcm_with_valid_metadata_is_not_replaced_or_accepted(self):
        fixture = self.manifest["fixtures"][0]
        path = self.fixtures / "test.wav"
        corrupt = bytearray(self.wav_with_info(path.read_bytes()))
        corrupt[-2] ^= 1
        path.write_bytes(corrupt)
        for extraction in [False, True]:
            with self.subTest(extraction=extraction), patch.object(prepare.subprocess, "run") as ffmpeg:
                with self.assertRaisesRegex(ValueError, "Signed PCM16 content hash mismatch") as caught:
                    if extraction:
                        prepare.extract_wav(self.fixtures / "test.webm", path, fixture["wav"], fixture["pcm"])
                    else:
                        self.run_prepare()
                self.assertIn(prepare.digest(corrupt), str(caught.exception))
                self.assertIn("float32_actual=", str(caught.exception))
                self.assertEqual(path.read_bytes(), corrupt)
                self.assertFalse(self.assets.exists())
                ffmpeg.assert_not_called()

    def test_wav_exception_rejects_extra_audio_trailing_bytes_and_bad_metadata(self):
        fixture = self.manifest["fixtures"][0]
        path = self.fixtures / "test.wav"
        original = path.read_bytes()
        def riff(body):
            return b"RIFF" + struct.pack("<I", len(body)) + body
        variants = [original + b"trailing",
                    riff(original[8:] + b"data" + struct.pack("<I", 2) + b"\0\0"),
                    riff(original[8:] + b"JUNK" + struct.pack("<I", 2) + b"\0\0"),
                    self.wav_with_info(original).replace(b"INFO", b"adtl", 1),
                    self.wav_with_info(original).replace(b"ISFT" + struct.pack("<I", 14), b"ISFT\xff\xff\xff\xff", 1)]
        for variant in variants:
            with self.subTest(variant=variant[:80]):
                path.write_bytes(variant)
                with self.assertRaisesRegex(ValueError, "WAV verification failed"):
                    prepare.verify_wav(path, fixture["wav"], fixture["pcm"])

    def test_metadata_exception_does_not_relax_pcm_format_or_frame_count(self):
        fixture = self.manifest["fixtures"][0]
        path = self.fixtures / "test.wav"
        original = path.read_bytes()
        for offset, value in [(22, 2), (24, 48000), (28, 12345), (32, 4), (34, 8)]:
            changed = bytearray(original)
            struct.pack_into("<H" if offset in (22, 32, 34) else "<I", changed, offset, value)
            path.write_bytes(self.wav_with_info(changed))
            with self.assertRaisesRegex(ValueError, "PCM16 WAV format"):
                prepare.verify_wav(path, fixture["wav"], fixture["pcm"])
        path.write_bytes(self.wav_with_info(original))
        with self.assertRaisesRegex(ValueError, "frame count mismatch"):
            prepare.verify_wav(path, fixture["wav"], dict(fixture["pcm"], samples=8))

    def test_cli_failure_report_preserves_fail_closed_diagnostics(self):
        report = self.root / "results/preparation.json"
        with patch("sys.argv", [str(SCRIPT), "--source-dir", str(self.source), "--report", str(report)]), \
                patch.object(prepare, "prepare", side_effect=ValueError("PCM expected=abc actual=def")):
            with self.assertRaises(ValueError):
                prepare.main()
        diagnostic = json.loads(report.read_text())
        self.assertEqual(diagnostic["status"], "failed")
        self.assertFalse(diagnostic["all_inputs_verified"])
        self.assertEqual(diagnostic["failure"], "PCM expected=abc actual=def")

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
