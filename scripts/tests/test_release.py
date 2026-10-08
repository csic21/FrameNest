"""No network, credentials or Android SDK needed for publication guard tests."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import os
import subprocess
import zipfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)
SOURCE = "a" * 40
OTHER = "b" * 40
PIN = "1" * 64
TAG = "v0.6.0-internal"
FILES = {"first.apk": b"signed APK fixture", "update.json": b'{"versionCode":11}'}
MANIFEST = {"source_sha": SOURCE, "tag": TAG, "versionCode": 11, "signer_sha256": PIN,
            "files": {name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in FILES.items()}}


class FakeGitHub:
    def __init__(self):
        self.tag = None
        self.release = None
        self.additional = []
        self.data = {}
        self.writes = []
        self.corrupt_upload = False
        self.interrupt = False
        self.fail_make_public = False

    def api(self, path, method="GET", payload=None):
        if method != "GET":
            self.writes.append((path, method, copy.deepcopy(payload)))
        if path == "":
            return {"visibility": "public"}
        if path.startswith("git/matching-refs/"):
            return [{"ref": f"refs/tags/{TAG}", "object": {"type": "commit", "sha": self.tag}}] if self.tag else []
        if path == "git/refs":
            self.tag = payload["sha"]
            return {}
        if path.startswith("releases?"):
            return copy.deepcopy(([self.release] if self.release else []) + self.additional)
        if path == "releases":
            self.release = {**payload, "id": 7, "assets": []}
            return copy.deepcopy(self.release)
        if path == "releases/7":
            if method == "PATCH":
                if payload.get("draft") is False and self.fail_make_public:
                    self.fail_make_public = False
                    raise OSError("mock interruption before publication")
                if payload.get("draft") is False:
                    assert set(self.data) == set(FILES), "A release must never be public before all assets exist."
                self.release.update(payload)
            return copy.deepcopy(self.release)
        raise AssertionError((path, method, payload))

    def upload(self, tag, path):
        name = path.name
        if name in self.data:
            raise AssertionError("An existing asset was overwritten.")
        self.writes.append(("upload", name))
        self.data[name] = b"corrupt" if self.corrupt_upload else FILES[name]
        self.release["assets"].append({"id": name, "name": name, "state": "uploaded", "size": len(self.data[name])})
        if self.interrupt:
            self.interrupt = False
            raise OSError("mock lost upload response")

    def asset_digest(self, asset):
        data = self.data[asset["name"]]
        return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class ReleaseTests(unittest.TestCase):
    def test_build_tools_36_and_37_signature_labels(self):
        labels = ["Signer #1", "Signer (minSdkVersion=26, maxSdkVersion=32)", "V1 Signer:", "V2 Signer:",
                  "V3.0 Signer: (minSdkVersion=28, maxSdkVersion=32)", "V3.1 Signer #1:",
                  "V3.2 Hybrid Classical Signer: (minSdkVersion=37 (dev release=true), maxSdkVersion=2147483647)"]
        for label in labels:
            with self.subTest(label=label):
                self.assertEqual({PIN}, r.parse_signers(f"{label} certificate SHA-256 digest: {PIN}\n"))
        report = "\n".join(f"{label} certificate SHA-256 digest: {PIN}" for label in labels)
        self.assertEqual({PIN}, r.parse_signers(report))

    def test_unknown_or_missing_signer_is_rejected(self):
        for report in ("", f"Unknown Signer certificate SHA-256 digest: {PIN}",
                       f"Signer #1 certificate SHA-256 digest: {PIN}\nNew Signer certificate SHA-256 digest: {PIN}"):
            with self.assertRaises(ValueError):
                r.parse_signers(report)

    def test_multiple_certificates_remain_visible(self):
        report = f"Signer #1 certificate SHA-256 digest: {PIN}\nSigner #2 certificate SHA-256 digest: {'2' * 64}"
        self.assertEqual({PIN, "2" * 64}, r.parse_signers(report))

    def test_source_stamp_does_not_replace_app_signer(self):
        with self.assertRaises(ValueError):
            r.parse_signers(f"Source Stamp Signer certificate SHA-256 digest: {PIN}")

    def test_gradle_identity_is_strict(self):
        text = 'applicationId = "com.framenest"\nversionName = "0.6.0-internal"\nversionCode = 11\nminSdk = 26\n'
        self.assertEqual(11, r.version_from_gradle(text)["versionCode"])
        for bad in (text + 'versionCode = 12\n', text.replace("com.framenest", "com.other"), text.replace("26", "27"), text.replace("0.6.0-internal", "main")):
            with self.assertRaises(ValueError):
                r.version_from_gradle(bad)

    def test_badging_rejects_debug_wrong_package_version_and_sdk(self):
        version = {"versionName": "0.6.0-internal", "versionCode": 11, "minSdk": 26}
        text = "package: name='com.framenest' versionCode='11' versionName='0.6.0-internal'\nsdkVersion:'26'\n"
        r.validate_badging(text, version, "arm64-v8a")
        r.validate_badging(text.replace("sdkVersion:", "minSdkVersion:"), version, "arm64-v8a")
        for bad in (text + "application-debuggable\n", text.replace("com.framenest", "com.other"),
                    text.replace("'11'", "'10'"), text.replace("'26'", "'27'")):
            with self.assertRaises(ValueError):
                r.validate_badging(bad, version, "arm64-v8a")

    def test_json_rejects_duplicate_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "request.json"
            path.write_text('{"tag":"good","tag":"bad"}')
            with self.assertRaises(ValueError):
                r.read_json(path)

    def test_missing_signing_material_does_not_create_a_key(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(r.os.environ, {}, clear=True):
            path = Path(folder) / "release.jks"
            with self.assertRaises(ValueError):
                r.prepare_signing(path)
            self.assertFalse(path.exists())

    def test_update_schema_matches_client(self):
        update = r.make_update({"applicationId": "com.framenest", "versionName": "0.6.0-internal", "versionCode": 11, "minSdk": 26}, TAG, "说明", [])
        self.assertEqual({"schemaVersion", "applicationId", "versionName", "versionCode", "minSdk", "tag", "notes", "assets"}, set(update))
        self.assertEqual(1, update["schemaVersion"])

    def test_fresh_publication_is_draft_until_verified(self):
        gh = FakeGitHub()
        r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertFalse(gh.release["draft"])
        self.assertEqual(SOURCE, gh.tag)
        created = next(write[2] for write in gh.writes if write[0] == "releases")
        self.assertTrue(created["draft"])
        self.assertFalse(created["prerelease"])

    def test_wrong_tag_is_never_moved(self):
        gh = FakeGitHub()
        gh.tag = OTHER
        with self.assertRaises(ValueError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertEqual([], gh.writes)

    def test_newer_release_blocks_old_request(self):
        gh = FakeGitHub()
        gh.additional = [{"tag_name": "v0.7.0-internal", "draft": False, "prerelease": False}]
        with self.assertRaises(ValueError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertEqual([], gh.writes)

    def test_interrupted_upload_resumes_without_overwriting(self):
        gh = FakeGitHub()
        gh.interrupt = True
        with self.assertRaises(OSError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertTrue(gh.release["draft"])
        r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertEqual(1, sum(write == ("upload", "first.apk") for write in gh.writes))
        self.assertFalse(gh.release["draft"])

    def test_interrupted_final_publication_resumes(self):
        gh = FakeGitHub()
        gh.fail_make_public = True
        with self.assertRaises(OSError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertEqual(len(FILES), sum(write[0] == "upload" for write in gh.writes))
        self.assertFalse(gh.release["draft"])

    def test_corrupt_upload_stays_draft(self):
        gh = FakeGitHub()
        gh.corrupt_upload = True
        with self.assertRaises(ValueError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertTrue(gh.release["draft"])

    def test_existing_public_release_is_verified_and_not_mutated(self):
        gh = FakeGitHub()
        r.publish(gh, "/unused", MANIFEST, "notes")
        gh.writes.clear()
        rebuilt = copy.deepcopy(MANIFEST)
        rebuilt["files"]["first.apk"]["sha256"] = "3" * 64
        self.assertEqual(MANIFEST, r.publish(gh, "/unused", rebuilt, "notes"))
        self.assertEqual([], gh.writes)

    def test_public_release_missing_asset_is_not_repaired(self):
        gh = FakeGitHub()
        r.publish(gh, "/unused", MANIFEST, "notes")
        gh.release["assets"].pop()
        gh.writes.clear()
        with self.assertRaises(ValueError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        self.assertEqual([], gh.writes)

    def test_partial_draft_with_different_build_is_not_modified(self):
        gh = FakeGitHub()
        gh.interrupt = True
        with self.assertRaises(OSError):
            r.publish(gh, "/unused", MANIFEST, "notes")
        gh.writes.clear()
        rebuilt = copy.deepcopy(MANIFEST)
        rebuilt["files"]["first.apk"]["sha256"] = "3" * 64
        with self.assertRaises(ValueError):
            r.publish(gh, "/unused", rebuilt, "notes")
        self.assertEqual([], gh.writes)

    def test_unknown_asset_and_changed_provenance_fail_closed(self):
        for mutation in ("asset", "source", "signature", "tag", "marker"):
            gh = FakeGitHub()
            r.publish(gh, "/unused", MANIFEST, "notes")
            if mutation == "asset":
                gh.release["assets"].append({"name": "unexpected"})
            elif mutation == "source":
                gh.release["body"] = gh.release["body"].replace(SOURCE, OTHER)
            elif mutation == "signature":
                gh.release["body"] = gh.release["body"].replace(PIN, "2" * 64)
            elif mutation == "tag":
                gh.tag = OTHER
            else:
                gh.release["body"] = "unrelated human-created release"
            gh.writes.clear()
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                r.publish(gh, "/unused", MANIFEST, "notes")
            self.assertEqual([], gh.writes)

    def test_tag_order_preserves_latest_internal_channel(self):
        self.assertLess(r.version_order("v0.5.2-internal"), r.version_order(TAG))
        self.assertLess(r.version_order(TAG), r.version_order("v0.6.0"))
        self.assertIsNone(r.version_order("v0.6.0-beta.1"))



class RequestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.previous)
        subprocess.run(["git", "init", "-q"], check=True)
        subprocess.run(["git", "config", "user.name", "Release Test"], check=True)
        subprocess.run(["git", "config", "user.email", "release-test@example.invalid"], check=True)
        Path("app").mkdir()
        Path(".github").mkdir()
        Path(".github/android-signing-certificate.sha256").write_text(PIN + "\n")
        Path("app/build.gradle.kts").write_text('applicationId = "com.framenest"\nversionName = "0.6.0-internal"\nversionCode = 11\nminSdk = 26\n')
        self.commit()
        self.source = r.run("git", "rev-parse", "HEAD")

    def commit(self):
        subprocess.run(["git", "add", "."], check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], check=True)

    def request(self, source=None, tag=TAG, extra=None):
        request = {"tag": tag, "source_sha": source or self.source}
        request.update(extra or {})
        r.write_json(".github/release-request.json", request)
        self.commit()
        return r.run("git", "rev-parse", "HEAD")

    def test_exact_source_and_separate_request_commit_pass(self):
        head = self.request()
        r.validate_request(".github/release-request.json", head, "output.txt")
        self.assertEqual(f"tag={TAG}\nsource_sha={self.source}\n", Path("output.txt").read_text())

    def test_request_rejects_arbitrary_fields(self):
        head = self.request(extra={"repository": "other/project"})
        with self.assertRaises(ValueError):
            r.validate_request(".github/release-request.json", head, "output.txt")

    def test_request_rejects_non_sha_or_wrong_tag(self):
        for source, tag in (("main", TAG), (self.source, "v0.5.2-internal")):
            head = self.request(source, tag)
            with self.assertRaises(ValueError):
                r.validate_request(".github/release-request.json", head, "output.txt")

    def test_request_does_not_skip_intervening_code_changes(self):
        Path("app/new-code.kt").write_text("unreviewed change")
        head = self.request()
        with self.assertRaises(ValueError):
            r.validate_request(".github/release-request.json", head, "output.txt")

    def test_wrong_triggering_checkout_is_rejected(self):
        self.request()
        with self.assertRaises(ValueError):
            r.validate_request(".github/release-request.json", self.source, "output.txt")

    def test_unconfirmed_target_certificate_is_rejected(self):
        Path(".github/android-signing-certificate.sha256").write_text("UNSET")
        self.commit()
        self.source = r.run("git", "rev-parse", "HEAD")
        head = self.request()
        with self.assertRaisesRegex(ValueError, "TARGET signing certificate is unset"):
            r.validate_request(".github/release-request.json", head, "output.txt")


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.previous)
        self.version = {"applicationId": "com.framenest", "versionName": "0.6.0-internal", "versionCode": 11, "minSdk": 26}
        self.apk_dir = Path("apks")
        self.apk_dir.mkdir()
        Path(".github").mkdir()
        Path(".github/android-signing-certificate.sha256").write_text(PIN + "\n")
        Path("CHANGELOG.md").write_text("# Changelog\n\n## 0.6.0-internal (2026-10-08)\n\n- 更新说明\n\n## 0.5.2-internal\n\nOld notes\n")
        elements = []
        for abi in r.ABIS:
            filename = f"app-{abi}-release.apk"
            with zipfile.ZipFile(self.apk_dir / filename, "w") as apk:
                for native in (r.ABIS[:2] if abi == "universal" else (abi,)):
                    apk.writestr(f"lib/{native}/libfixture.so", "fixture")
            elements.append({"filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}],
                             "versionCode": 11, "versionName": "0.6.0-internal", "outputFile": filename})
        r.write_json(self.apk_dir / "output-metadata.json", {"applicationId": "com.framenest", "variantName": "release", "elements": elements})
        self.metadata = self.apk_dir / "output-metadata.json"
        source = patch.object(r, "source_version", return_value=self.version)
        source.start()
        self.addCleanup(source.stop)
        environment = patch.dict(os.environ, {"GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"})
        environment.start()
        self.addCleanup(environment.stop)

    def tools(self, *args):
        if args[0].endswith("apksigner"):
            return f"Signer #1 certificate SHA-256 digest: {PIN}\n"
        if args[0].endswith("aapt2"):
            return "package: name='com.framenest' versionCode='11' versionName='0.6.0-internal'\nminSdkVersion:'26'\n"
        raise AssertionError(args)

    def package(self):
        with patch.object(r, "run", side_effect=self.tools):
            r.package_release(SOURCE, TAG, self.apk_dir, "artifacts", "tools")

    def test_complete_bundle_round_trips_and_all_digests_match(self):
        self.package()
        manifest = r.bundle_manifest("artifacts", SOURCE, TAG)
        self.assertEqual(9, len(manifest["files"]))
        self.assertEqual(PIN, manifest["signer_sha256"])
        update = r.read_json("artifacts/update.json")
        self.assertEqual(list(r.ABIS), [item["abi"] for item in update["assets"]])
        self.assertEqual("- 更新说明", update["notes"])

    def test_altered_apk_is_rejected_before_publication(self):
        self.package()
        apk = next(Path("artifacts").glob("*.apk"))
        apk.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            r.bundle_manifest("artifacts", SOURCE, TAG)

    def test_cross_repository_download_url_is_rejected(self):
        self.package()
        manifest = r.read_json("artifacts/release-manifest.json")
        manifest["assets"][0]["url"] = "https://github.com/attacker/project/releases/download/test/payload.apk"
        r.write_json("artifacts/release-manifest.json", manifest)
        update = r.read_json("artifacts/update.json")
        update["assets"] = manifest["assets"]
        r.write_json("artifacts/update.json", update)
        with self.assertRaises(ValueError):
            r.bundle_manifest("artifacts", SOURCE, TAG)

    def test_missing_supported_abi_is_rejected(self):
        metadata = r.read_json(self.metadata)
        metadata["elements"].pop()
        r.write_json(self.metadata, metadata)
        with self.assertRaises(ValueError):
            self.package()

    def test_unexpected_native_abi_is_rejected(self):
        apk = self.apk_dir / "app-arm64-v8a-release.apk"
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("lib/armeabi-v7a/libfixture.so", "fixture")
        with self.assertRaises(ValueError):
            self.package()

    def test_unsigned_or_wrongly_signed_apk_is_rejected(self):
        for signer in ("", f"Signer #1 certificate SHA-256 digest: {'2' * 64}"):
            folder = "artifacts" + str(len(signer))
            with patch.object(r, "run", return_value=signer), self.assertRaises(ValueError):
                r.package_release(SOURCE, TAG, self.apk_dir, folder, "tools")

    def test_unexpected_asset_is_rejected(self):
        self.package()
        Path("artifacts/keystore.jks").write_text("must never be published")
        with self.assertRaises(ValueError):
            r.bundle_manifest("artifacts", SOURCE, TAG)

    def test_changed_checksum_file_is_rejected(self):
        self.package()
        Path("artifacts/SHA256SUMS").write_text("wrong")
        with self.assertRaises(ValueError):
            r.bundle_manifest("artifacts", SOURCE, TAG)

    def test_reused_version_code_is_rejected(self):
        prior = {"tag_name": "v0.5.2-internal", "draft": False, "prerelease": False,
                 "body": '<!-- framenest-release: {"versionCode": 11} -->'}
        with self.assertRaises(ValueError):
            r.no_newer_release([prior], TAG, 11)


if __name__ == "__main__":
    unittest.main()
