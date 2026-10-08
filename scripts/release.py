#!/usr/bin/env python3
"""Fail-closed Android release preparation and immutable GitHub publication.

Uses only Python's standard library, Android Build Tools and the GitHub CLI.
Publication is a separate subcommand so only that Actions job needs write access.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import zipfile

REPOSITORY = "csic21/FrameNest"
APPLICATION_ID = "com.framenest"
# Historical reference only. The trusted TARGET certificate is set separately
# by the owner in .github/android-signing-certificate.sha256 before release.
LEGACY_CERTIFICATE = "31324de514179999b94c2afc395b2190e3c8c242b8cf13f699656303822fd8e8"
ABIS = ("arm64-v8a", "x86_64", "universal")
TAG_RE = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-internal)?")
SHA_RE = re.compile(r"[0-9a-f]{64}")
SOURCE_RE = re.compile(r"[0-9a-f]{40}")
MARKER = "framenest-release"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def read_json(path):
    # Duplicate fields are almost always a typo and must not change provenance.
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON field: {key}")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique)


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def version_from_gradle(text):
    def field(pattern):
        matches = re.findall(pattern, text, re.M)
        require(len(matches) == 1, "Expected one unambiguous app version/application ID/minSdk.")
        return matches[0]
    app = field(r'^\s*applicationId = "([^"\n]+)"\s*$')
    name = field(r'^\s*versionName = "([^"\n]+)"\s*$')
    code = int(field(r'^\s*versionCode = ([1-9]\d*)\s*$'))
    minimum = int(field(r'^\s*minSdk = ([1-9]\d*)\s*$'))
    require(app == APPLICATION_ID and TAG_RE.fullmatch("v" + name), "Unexpected app or version name.")
    require(minimum == 26, "Changing the supported Android minimum requires explicit updater review.")
    return {"applicationId": app, "versionName": name, "versionCode": code, "minSdk": minimum}


def source_version(source, tag):
    require(isinstance(source, str) and SOURCE_RE.fullmatch(source), "A full lowercase source commit SHA is required.")
    require(isinstance(tag, str) and TAG_RE.fullmatch(tag), "Invalid FrameNest release tag.")
    require(run("git", "rev-parse", "HEAD") == source, "Checkout differs from the requested source.")
    version = version_from_gradle(Path("app/build.gradle.kts").read_text())
    require(tag == "v" + version["versionName"], "Tag differs from the source app version.")
    require(10 < version["versionCode"] <= 2100000000, "Release versionCode must exceed the legacy version (10) and fit Android's limit.")
    return version


def certificate_pin():
    pin = Path(".github/android-signing-certificate.sha256").read_text().strip()
    require(SHA_RE.fullmatch(pin), "The owner-confirmed TARGET signing certificate is unset. See docs/RELEASING.md; no key is selected automatically.")
    return pin


def validate_request(path, event_sha, output):
    request = read_json(path)
    require(isinstance(request, dict) and set(request) == {"tag", "source_sha"}, "Release request must contain only tag and source_sha.")
    tag, source = request["tag"], request["source_sha"]
    require(isinstance(tag, str) and TAG_RE.fullmatch(tag), "Invalid release tag.")
    require(isinstance(source, str) and SOURCE_RE.fullmatch(source), "source_sha must be a complete lowercase commit SHA.")
    require(SOURCE_RE.fullmatch(event_sha) and run("git", "rev-parse", "HEAD") == event_sha, "Checkout differs from the triggering commit.")
    require(run("git", "cat-file", "-t", source) == "commit", "Release source is not a commit.")
    subprocess.run(["git", "merge-base", "--is-ancestor", source, event_sha], check=True)
    # Keep the publication code and app identical to the reviewed source. The
    # request is a separate commit after integration, tests and signing setup.
    changed = run("git", "diff", "--name-only", source, event_sha).splitlines()
    require(changed == [".github/release-request.json"], "The request commit may differ from source only in .github/release-request.json.")
    version = version_from_gradle(run("git", "show", f"{source}:app/build.gradle.kts"))
    require(tag == "v" + version["versionName"], "Requested tag differs from the source app version.")
    certificate_pin()
    with Path(output).open("a", encoding="utf-8") as stream:
        stream.write(f"tag={tag}\nsource_sha={source}\n")
    print(f"Validated {tag} at {source}.")


def prepare_signing(path):
    required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    require(all(os.environ.get(name) for name in required), "Signing secrets are incomplete. See docs/RELEASING.md; no new key will be generated.")
    pin = certificate_pin()
    data = base64.b64decode("".join(os.environ["ANDROID_KEYSTORE_BASE64"].split()), validate=True)
    require(data, "Configured keystore is empty.")
    # An exclusive 0600 temporary file, outside the repository and Gradle cache.
    with Path(path).open("xb") as stream:
        os.chmod(path, 0o600)
        stream.write(data)
    result = subprocess.run([
        "keytool", "-exportcert", "-keystore", str(path), "-alias", os.environ["ANDROID_KEY_ALIAS"],
        "-storepass:env", "ANDROID_STORE_PASSWORD",
    ], capture_output=True, check=True)
    require(hashlib.sha256(result.stdout).hexdigest() == pin, "Signing certificate differs from the owner-confirmed target certificate. Refusing publication.")
    print("Owner-confirmed FrameNest signing certificate verified.")


def parse_signers(report):
    # Android Build Tools 36 uses numbered/range labels; 37 adds V1/V2/V3.0
    # labels and colons. Parse every certificate line; unknown formats fail.
    sdk_range = r"\(minSdkVersion=\d+(?: \(dev release=true\))?, maxSdkVersion=\d+\)"
    name = (rf"(?:Signer #\d+|Signer {sdk_range}|"
            rf"(?:V[12]|V3\.[012](?: Hybrid (?:Classical|PQC))?) Signer(?: #\d+)?:(?: {sdk_range})?)")
    parsed = re.findall(rf"^({name}) certificate SHA-256 digest: ([0-9a-fA-F]{{64}})[ \t]*$", report, re.M)
    lines = [line for line in report.splitlines() if " certificate SHA-256 digest:" in line
             and not line.startswith("Source Stamp Signer")]
    require(parsed and len(parsed) == len(lines), "Cannot parse every APK signer certificate.")
    return {value.lower() for _, value in parsed}


def validate_badging(text, version, abi):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text, re.M)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    require(package is not None and minimum is not None, "Cannot read APK package/version/minSdk.")
    require(package.groups() == (APPLICATION_ID, str(version["versionCode"]), version["versionName"]), "APK package or version differs from source.")
    require(int(minimum[1]) == version["minSdk"], "APK minSdk differs from source.")
    require(not re.search(r"^application-debuggable", text, re.M), "Refusing to publish a debuggable APK.")


def release_notes(version):
    text = Path("CHANGELOG.md").read_text(encoding="utf-8")
    section = re.search(r"^## " + re.escape(version) + r"(?: \([^\n]+\))?\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    require(section is not None and section[1].strip(), "CHANGELOG has no notes for this version.")
    notes = section[1].strip()
    require(len(notes) <= 12000 and "<!--" not in notes, "Release notes are too long or contain hidden metadata.")
    return notes


def make_update(version, tag, notes, assets):
    return {"schemaVersion": 1, **version, "tag": tag, "notes": notes, "assets": assets}


def package_release(source, tag, apk_dir, output, build_tools):
    version = source_version(source, tag)
    pin = certificate_pin()
    apk_dir, output, build_tools = Path(apk_dir), Path(output), Path(build_tools)
    metadata = read_json(apk_dir / "output-metadata.json")
    require(metadata.get("applicationId") == APPLICATION_ID and metadata.get("variantName") == "release", "Wrong APK output variant.")
    outputs = {}
    for element in metadata["elements"]:
        filters = element["filters"]
        require(not filters or (len(filters) == 1 and filters[0]["filterType"] == "ABI"), "Unexpected APK split filter.")
        abi = filters[0]["value"] if filters else "universal"
        filename = element["outputFile"]
        require(abi in ABIS and abi not in outputs, "Unexpected or duplicate ABI output.")
        require(Path(filename).name == filename and filename.endswith(".apk"), "Unsafe APK output filename.")
        require(element["versionCode"] == version["versionCode"] and element["versionName"] == version["versionName"], "APK metadata differs from source version.")
        outputs[abi] = apk_dir / filename
    require(set(outputs) == set(ABIS), "All supported ABI and universal APKs must be built.")
    output.mkdir(parents=True, exist_ok=False)
    assets = []
    for abi in ABIS:
        apk = outputs[abi]
        require(apk.is_file() and 0 < apk.stat().st_size <= 512 * 1024 * 1024, "APK is missing, empty or exceeds the updater's 512 MiB limit.")
        report = run(str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
        require(parse_signers(report) == {pin}, "APK signing certificate does not match the trusted target certificate.")
        badging = run(str(build_tools / "aapt2"), "dump", "badging", str(apk))
        validate_badging(badging, version, abi)
        with zipfile.ZipFile(apk) as archive:
            natives = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
        require(natives == (set(ABIS[:2]) if abi == "universal" else {abi}), "APK native libraries do not match its advertised ABI.")
        filename = f"FrameNest-{version['versionName']}-{abi}.apk"
        target = output / filename
        shutil.copyfile(apk, target)
        (output / (filename + ".signature.txt")).write_text(report + "\n", encoding="utf-8")
        assets.append({"abi": abi, "size": target.stat().st_size, "sha256": digest(target),
                       "url": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{filename}"})
    update = make_update(version, tag, release_notes(version["versionName"]), assets)
    write_json(output / "update.json", update)
    provenance = {"schemaVersion": 1, "repository": REPOSITORY, "source_sha": source, "tag": tag,
                  **version, "signer_sha256": pin, "assets": assets,
                  "workflow_url": f"https://github.com/{REPOSITORY}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
                  "workflow_attempt": os.environ["GITHUB_RUN_ATTEMPT"]}
    write_json(output / "release-manifest.json", provenance)
    files = sorted(output.iterdir())
    (output / "SHA256SUMS").write_text("".join(f"{digest(path)}  {path.name}\n" for path in files), encoding="utf-8")
    print(f"Verified and packaged {tag}: {', '.join(ABIS)}.")


def bundle_manifest(folder, source, tag):
    folder = Path(folder)
    provenance = read_json(folder / "release-manifest.json")
    version = source_version(source, tag)
    require(all(provenance.get(key) == value for key, value in version.items()), "Build provenance has a different app version.")
    require(provenance.get("source_sha") == source and provenance.get("tag") == tag and provenance.get("repository") == REPOSITORY,
            "Build provenance differs from the requested source/repository.")
    require(provenance.get("signer_sha256") == certificate_pin(), "Build provenance has a different signing certificate.")
    update = read_json(folder / "update.json")
    require(update == make_update(version, tag, release_notes(version["versionName"]), provenance["assets"]), "Update manifest differs from the verified build.")
    names = {"update.json", "release-manifest.json", "SHA256SUMS"}
    require(len(update["assets"]) == len(ABIS) and {item["abi"] for item in update["assets"]} == set(ABIS), "Update manifest must include each supported ABI exactly once.")
    for asset in update["assets"]:
        filename = f"FrameNest-{version['versionName']}-{asset['abi']}.apk"
        require(asset["url"] == f"https://github.com/{REPOSITORY}/releases/download/{tag}/{filename}", "Unexpected APK download URL.")
        path = folder / filename
        require(type(asset["size"]) is int and asset["size"] > 0 and path.stat().st_size == asset["size"] and digest(path) == asset["sha256"], "APK no longer matches the verified build.")
        require(parse_signers((folder / (filename + ".signature.txt")).read_text()) == {certificate_pin()}, "Signature report differs from the verified certificate.")
        names.update((filename, filename + ".signature.txt"))
    require({path.name for path in folder.iterdir()} == names and all(path.is_file() and not path.is_symlink() for path in folder.iterdir()), "Unexpected release bundle contents.")
    expected_sums = "".join(f"{digest(folder / name)}  {name}\n" for name in sorted(names - {"SHA256SUMS"}))
    require((folder / "SHA256SUMS").read_text() == expected_sums, "SHA256SUMS differs from the verified bundle.")
    return {"source_sha": source, "tag": tag, "versionCode": version["versionCode"],
            "signer_sha256": provenance["signer_sha256"],
            "files": {name: {"size": (folder / name).stat().st_size, "sha256": digest(folder / name)} for name in sorted(names)}}


class GitHub:
    def api(self, path, method="GET", payload=None):
        command = ["gh", "api", f"repos/{REPOSITORY}" + ("/" + path if path else ""), "--method", method]
        if payload is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                                text=True, capture_output=True, check=True)
        return json.loads(result.stdout)

    def asset_digest(self, asset):
        with tempfile.TemporaryFile() as stream:
            subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/assets/{asset['id']}",
                            "-H", "Accept: application/octet-stream"], stdout=stream, check=True)
            size = stream.tell()
            stream.seek(0)
            return {"size": size, "sha256": hashlib.file_digest(stream, "sha256").hexdigest()}

    def upload(self, tag, path):
        # Deliberately no --clobber; an uncertain upload is inspected on retry.
        subprocess.run(["gh", "release", "upload", tag, str(path), "--repo", REPOSITORY], check=True)


def check_tag(github, tag, source):
    refs = [item for item in github.api(f"git/matching-refs/tags/{tag}") if item["ref"] == f"refs/tags/{tag}"]
    if not refs:
        return False
    require(len(refs) == 1, "Ambiguous release tag.")
    obj = refs[0]["object"]
    for _ in range(8):
        if obj["type"] != "tag":
            break
        obj = github.api(f"git/tags/{obj['sha']}")["object"]
    require(obj["type"] == "commit" and obj["sha"] == source, "Existing tag points elsewhere; it will not be moved.")
    return True


def version_order(tag):
    match = TAG_RE.fullmatch(tag)
    return (*map(int, match.groups()[:3]), not bool(match[4])) if match else None


def list_releases(github):
    releases = []
    for page in range(1, 101):
        batch = github.api(f"releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
    raise ValueError("Could not exhaust releases safely.")


def no_newer_release(releases, tag, version_code):
    for release in releases:
        other = version_order(release["tag_name"])
        require(not (other and not release["draft"] and not release["prerelease"] and other > version_order(tag)), "A newer stable release exists; refusing a downgrade.")
        if release["tag_name"] != tag and not release["draft"] and not release["prerelease"]:
            marker = re.search(r"<!-- " + MARKER + r": (\{[^\n]+\}) -->", release.get("body") or "")
            if marker:
                published_code = json.loads(marker[1]).get("versionCode")
                require(type(published_code) is int and published_code < version_code,
                        "Published Android versionCode is not older; increase versionCode before releasing.")


def release_body(manifest, notes):
    installation = ("安装由 Android 系统确认；相同签名的版本可覆盖更新并保留应用数据。" if manifest['signer_sha256'] == LEGACY_CERTIFICATE else
                    "重要：0.5.2-internal 使用旧调试签名，无法覆盖安装本次正式签名版本，需要一次手动迁移。"
                    "卸载旧应用会删除本地设置、播放历史和已保存的 NAS 凭证。此后同签名的正式版本可直接更新。")
    return (f"FrameNest {manifest['tag']}\n\n{notes}\n\n"
            "arm64-v8a 适合多数手机和平板；x86_64 适合对应设备/模拟器；不确定时选择 universal。\n"
            f"{installation}\n\n"
            f"Source commit: {manifest['source_sha']}\n"
            f"Signing certificate SHA-256: {manifest['signer_sha256']}\n\n"
            f"<!-- {MARKER}: " + json.dumps(manifest, sort_keys=True) + " -->")


def previous_manifest(body, current):
    matches = re.findall(r"<!-- " + MARKER + r": (\{[^\n]+\}) -->", body or "")
    require(len(matches) == 1, "Existing release has no unambiguous publication provenance.")
    previous = json.loads(matches[0])
    require(all(previous.get(key) == current[key] for key in ("source_sha", "tag", "versionCode", "signer_sha256")), "Existing release has different source/signing provenance.")
    require(set(previous["files"]) == set(current["files"]), "Existing release has a different asset set.")
    for value in previous["files"].values():
        require(set(value) == {"size", "sha256"} and type(value["size"]) is int and value["size"] > 0
                and isinstance(value["sha256"], str) and SHA_RE.fullmatch(value["sha256"]), "Invalid existing asset provenance.")
    return previous


def verify_assets(github, release, manifest, complete):
    assets = release.get("assets", [])
    names = [asset["name"] for asset in assets]
    expected = manifest["files"]
    require(len(names) == len(set(names)) and set(names) <= set(expected), "Unexpected existing assets; nothing will be replaced.")
    if complete:
        require(set(names) == set(expected), "Release assets are incomplete.")
    for asset in assets:
        require(asset.get("state") == "uploaded", "An existing upload is incomplete; it will not be overwritten.")
        require(asset.get("size") == expected[asset["name"]]["size"] and github.asset_digest(asset) == expected[asset["name"]], "Uploaded asset differs from its recorded digest.")
    return set(names)


def publish(github, folder, current, notes):
    tag, source = current["tag"], current["source_sha"]
    require(TAG_RE.fullmatch(tag) and SOURCE_RE.fullmatch(source), "Invalid publication target.")
    require(github.api("").get("visibility") == "public", "The update repository must be public.")
    releases = list_releases(github)
    no_newer_release(releases, tag, current["versionCode"])
    existing = [release for release in releases if release["tag_name"] == tag]
    require(len(existing) <= 1, "Multiple releases have this tag.")
    tagged = check_tag(github, tag, source)
    require(tagged or not existing, "Existing release has no matching tag; nothing will be changed.")
    if not tagged:
        github.api("git/refs", "POST", {"ref": f"refs/tags/{tag}", "sha": source})
    require(check_tag(github, tag, source), "Tag creation could not be verified.")
    release = existing[0] if existing else github.api("releases", "POST", {
        "tag_name": tag, "target_commitish": source, "name": tag, "body": release_body(current, notes),
        "draft": True, "prerelease": False, "make_latest": "false"})
    require(release.get("target_commitish") == source and not release["prerelease"], "Existing release does not match the requested source/channel.")
    previous = previous_manifest(release.get("body"), current)
    uploaded = verify_assets(github, release, previous, complete=not release["draft"])
    if not release["draft"]:
        # A complete immutable publication of this source/key wins over any
        # rebuilt bytes. Verify it and leave it untouched, including latest.
        print(f"{tag} is already published and all recorded asset digests verify.")
        return previous
    if previous != current:
        require(not uploaded, "Partial draft belongs to a different build. Re-run only the failed publish job with its original artifact.")
        github.api(f"releases/{release['id']}", "PATCH", {"body": release_body(current, notes)})
    for name in current["files"]:
        if name not in uploaded:
            github.upload(tag, Path(folder) / name)
    release = github.api(f"releases/{release['id']}")
    require(previous_manifest(release.get("body"), current) == current, "Draft provenance changed during upload.")
    verify_assets(github, release, current, complete=True)
    require(check_tag(github, tag, source), "Tag vanished before publication.")
    no_newer_release(list_releases(github), tag, current["versionCode"])
    github.api(f"releases/{release['id']}", "PATCH", {"draft": False, "make_latest": "true"})
    public = github.api(f"releases/{release['id']}")
    require(not public["draft"] and not public["prerelease"] and public["tag_name"] == tag, "Publication could not be verified.")
    require(previous_manifest(public.get("body"), current) == current and check_tag(github, tag, source), "Published provenance changed.")
    print(f"Published {tag} from {source}; every asset was downloaded and verified before publication.")
    return current


def wait_for_feed(expected):
    # Bound to GitHub CDN propagation, not an open-ended polling service.
    url = f"https://github.com/{REPOSITORY}/releases/latest/download/update.json"
    for attempt in range(24):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(128 * 1024 + 1)
            require(len(data) <= 128 * 1024 and hashlib.sha256(data).hexdigest() == expected["files"]["update.json"]["sha256"], "Latest feed still differs from the published release.")
            print(f"Public update feed verified for {expected['tag']}.")
            return
        except (OSError, ValueError) as error:
            print(f"Waiting for the public update feed ({attempt + 1}/24): {type(error).__name__}")
            if attempt < 23:
                time.sleep(15)
    raise ValueError("Release is public but latest/update.json could not be verified within the propagation window; inspect and re-run the failed job.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    request = commands.add_parser("validate-request")
    request.add_argument("--request", default=".github/release-request.json")
    request.add_argument("--event-sha", default=os.environ.get("GITHUB_SHA"))
    request.add_argument("--output", default=os.environ.get("GITHUB_OUTPUT"))
    signing = commands.add_parser("prepare-signing")
    signing.add_argument("--keystore", required=True)
    for name in ("package", "publish"):
        command = commands.add_parser(name)
        command.add_argument("--source", default=os.environ.get("RELEASE_SOURCE_SHA"))
        command.add_argument("--tag", default=os.environ.get("RELEASE_TAG"))
        command.add_argument("--artifacts", default="release-artifacts")
        if name == "package":
            command.add_argument("--apk-dir", default="app/build/outputs/apk/release")
            command.add_argument("--build-tools", required=True)
    args = parser.parse_args()
    if args.command == "validate-request":
        validate_request(args.request, args.event_sha, args.output)
    elif args.command == "prepare-signing":
        prepare_signing(args.keystore)
    elif args.command == "package":
        package_release(args.source, args.tag, args.apk_dir, args.artifacts, args.build_tools)
    elif args.command == "publish":
        manifest = bundle_manifest(args.artifacts, args.source, args.tag)
        result = publish(GitHub(), args.artifacts, manifest, release_notes(args.tag[1:]))
        wait_for_feed(result)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        # Do not print subprocess output: keytool/build tooling can include
        # private paths or supplied aliases. The error category is sufficient.
        print(f"::error::Release stopped: {str(error) if isinstance(error, ValueError) else type(error).__name__}")
        raise SystemExit(1)
