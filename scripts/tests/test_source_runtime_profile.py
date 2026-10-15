"""Keep source runtime evidence separate while reusing the exact stock corpus.

FN74_SOURCE_AAR optionally enables validation against the retained real AAR.
These are byte/provenance checks, not Android inference tests.
"""
import hashlib
import json
import os
from pathlib import Path
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/androidTest/assets/runtime-comparison"
PROFILE = json.loads((ASSETS / "source-profile.json").read_text())
LOCK = json.loads((ROOT / "docs/experiments/FN-74-asr-source-app/artifact-lock.json").read_text())


class SourceRuntimeProfileTest(unittest.TestCase):
    def test_source_identity_is_the_reviewed_aar_not_plain_upstream_version(self):
        self.assertEqual(PROFILE["schema_version"], 1)
        self.assertEqual(PROFILE["profile"], "source-asr-no-tts")
        self.assertEqual(PROFILE["aar_sha256"], LOCK["aar_sha256"])
        self.assertEqual(PROFILE["aar_bytes"], LOCK["aar_size_bytes"])
        self.assertEqual(PROFILE["upstream_runtime_version"], LOCK["upstream_version"])
        self.assertEqual(PROFILE["runtime_identity"], LOCK["runtime_identity"])
        self.assertNotEqual(PROFILE["runtime_identity"], PROFILE["upstream_runtime_version"])

    def test_corpus_is_byte_identical_to_the_frozen_stock_manifest(self):
        frozen = "9231712ec85ec7a16b31000f3cb960f2a15b7e3ccc28758515452a2351f66f77"
        self.assertEqual(PROFILE["frozen_fixture_manifest_sha256"], frozen)
        self.assertEqual(hashlib.sha256((ASSETS / "manifest.json").read_bytes()).hexdigest(), frozen)
        manifest = json.loads((ASSETS / "manifest.json").read_text())
        self.assertEqual(sum(len(sample["rows"]) for sample in manifest["samples"]), 33)

    def test_native_pins_match_independently_retained_packaging_evidence(self):
        evidence = json.loads((ROOT / "docs/benchmarks/sherpa-source-aar-2026-10-15/native-output-manifest.json").read_text())
        expected = {row["path"].replace("jniLibs/", "lib/", 1): (row["size_bytes"], row["sha256"])
                    for row in evidence["outputs"]}
        actual = {row["path"]: (row["bytes"], row["sha256"]) for row in PROFILE["native_members"]}
        self.assertEqual(len(PROFILE["native_members"]), 4)
        self.assertEqual(actual, expected)
        for row in PROFILE["native_members"]:
            self.assertEqual(row["path"].split("/")[1], row["abi"])

    def test_provenance_pins_match_retained_asset_evidence(self):
        artifact = json.loads((ROOT / "docs/benchmarks/sherpa-source-aar-2026-10-15/artifact-manifest.json").read_text())
        retained = {row["path"]: row for row in artifact["files"]}
        native = next(row for row in PROFILE["apk_assets"] if row["path"].endswith("/native-output-manifest.json"))
        self.assertEqual(native["sha256"], retained["native/native-output-manifest.json"]["sha256"])
        self.assertEqual(len(PROFILE["apk_assets"]), 3)
        self.assertEqual(len({row["path"] for row in PROFILE["apk_assets"]}), 3)

    @unittest.skipUnless(os.environ.get("FN74_SOURCE_AAR"), "Set FN74_SOURCE_AAR to check the actual retained AAR")
    def test_all_pins_against_the_actual_source_aar(self):
        path = Path(os.environ["FN74_SOURCE_AAR"])
        self.assertEqual(path.stat().st_size, PROFILE["aar_bytes"])
        with path.open("rb") as source:
            self.assertEqual(hashlib.file_digest(source, "sha256").hexdigest(), PROFILE["aar_sha256"])
        with zipfile.ZipFile(path) as aar:
            for row in PROFILE["apk_assets"] + PROFILE["native_members"]:
                name = row["path"].replace("lib/", "jni/", 1) if "abi" in row else row["path"]
                with self.subTest(member=name):
                    data = aar.read(name)
                    self.assertEqual(len(data), row["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])


if __name__ == "__main__":
    unittest.main()
