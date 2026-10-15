# Ordinary AAR CI: quarantine first, strict review second

This experiment uses `.github/workflows/asr-aar-source.yml`, on the dedicated
`experiment/FN-74-asr-only-recipe` branch. It never changes the app dependency,
opens a release, signs/publishes an artifact, accepts SDK agreements, or uses
an emulator/KVM. Existing Android device and legal gates remain open.

## Two separate runs

1. Without `gradle-lock/approved-lock.json`, the workflow stages exactly the eight
   pinned Kotlin files and runs the real `:asr:assembleRelease` task with
   `--write-verification-metadata sha256`. The graph, cache inventory, logs and
   actual hosted/JDK/SDK provenance are uploaded as **quarantine evidence**.
   This pass intentionally lacks native binaries. Its raw AAR and Gradle cache
   are never uploaded as candidates. Plugin execution during capture is why
   this is an isolated read-only workflow with no secrets or write permissions.
2. Independently review every captured artifact with `scripts/gradle_lock.py`:
   fetch each exact POM/module/JAR/AAR from its official Google Maven or Maven
   Central coordinate, compare locked SHA-256, and check available publisher
   checksum sidecars. The reviewer does not read Gradle's downloaded cache.
   HTTPS bytes and sidecars from the same publisher are not a signature or
   independent publisher authentication. Missing artifacts, network errors,
   unexpected redirects, checksum mismatches, alternate hashes, exclusions,
   malformed XML and unsupported graph entries fail closed.
3. After human/owner coordination, commit the exact XML, successful independent
   review JSON, and `approved-lock.json` with their separate SHA-256 digests.
   This is a different commit/run. The capture workflow never creates this
   approval file or approves its own graph.
4. The strict run verifies the locked native artifact's repository, successful
   run ID, head SHA, artifact ID, server digest when present, downloaded ZIP
   digest, full entry manifest and every output hash. It freshly stages pinned
   sources and imports only the reviewed native outputs/notices. The short-lived
   token is sent only to GitHub's API; storage redirects use a separate request
   without Authorization. Signed URLs and tokens are never logged.
5. The verified official NDK r28c strips rebuilt JNI SOs explicitly with
   `llvm-strip --strip-unneeded`. Original SOs are uploaded separately as debug
   evidence. ORT vendor bytes are retained unchanged. Each stripped output has
   new hashes/sizes, identical dynamic exports, identical program headers and
   identical PT_LOAD bytes except ELF section-table bookkeeping fields changed
   by stripping. A second strip from the original must produce the same bytes.
   This proves that stripping step's repeatability, not whole-build reproducibility.
6. The strict build uses a fresh Gradle home, `--dependency-verification strict`,
   the exact reviewed XML and independently pinned review evidence. AGP is told
   to retain the explicitly stripped native bytes. After ZIP normalization the
   audit checks ABI/SO/class/native-call/notice whitelists and hashes, and matches
   the final AAR's four SOs against the new native manifest.

The host is an actual rolling GitHub VM image with recorded provenance. It is
never assigned a fabricated immutable digest. An immutable host and two clean
full-build comparisons remain needed for a reproducibility claim.

## Native input and expiration

`native-artifact-lock.json` pins run 38706441953, artifact 11958847995, source
recipe commit 8fb54b09e405a9cb9d2a0211d83176ced0328af8, and the verified ZIP SHA-256.
The first-run review is in `docs/benchmarks/sherpa-source-native-2026-10-15`.
Artifacts have finite retention: expiry or mismatch stops packaging. Rebuild
through the independent native workflow, review its evidence, and deliberately
update the lock rather than silently substituting another artifact.

The native workflow path filters continue covering its own workflow, shared
inputs/manifest, native tools, native build/audit/collector and regression tests.
Packaging-only changes use the separate AAR workflow. No `main` or app files are
modified by these workflows.

## Review commands

From this recipe directory, after retrieving the quarantine XML:

```sh
python3 scripts/gradle_lock.py review \
  --verification-file /absolute/quarantine/verification-metadata.xml \
  --output /absolute/review/independent-review.json
```

Review the complete evidence and coverage before creating the separately
committed approval file. Its format is:

```json
{
  "status": "independently-reviewed",
  "verification_sha256": "REVIEWED_XML_SHA256",
  "review_sha256": "REVIEWED_EVIDENCE_SHA256"
}
```

Official graph provenance, exact resolved aapt2 platform JAR, Kotlin runtime,
Gradle distribution, JDK and SDK hashes must remain distinguishable. Recording
SDK build-tools/aapt2 does not prove that AGP used that executable: AGP's Maven
platform-classifier aapt2 JAR belongs in the captured/strict dependency graph.

## Remaining gates

Successful AAR static checks do not establish Android load/call success, 4 KB or
16 KB runtime acceptance, app compatibility, latency/accuracy, reproducibility
or legal clearance. The unchanged arm64 ORT RELRO end warning stays visible and
is not a proven runtime crash. Exact ORT vendor build/transitive SBOM, Eigen
source availability, embedded attribution, static C++ runtime and model/app
license review remain separate requirements.
