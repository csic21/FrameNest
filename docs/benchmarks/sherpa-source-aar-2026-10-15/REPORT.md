# FN-74: actual source-built no-TTS AAR, static milestone complete

The isolated **official sherpa-onnx 1.13.8 ASR-facing/no-TTS candidate AAR** built
successfully in [Actions run 38713363406](https://github.com/csic21/FrameNest/actions/runs/38713363406),
job 116350000151, at recipe commit `c4088fab8d0e7daf0af5fa6895a9f88b11adcf81`.
The downloaded artifact was independently rechecked after CI. No application,
main-branch dependency, release, signing, emulator or KVM work was performed in
this packaging milestone. Runtime/performance, full reproducibility and legal
acceptance remain separate open gates.

## Exact deliverable

- AAR: `sherpa-onnx-1.13.8-asr-no-tts.aar`
- Size: **59,632,871 bytes / 56.87 MiB**
- SHA-256: `6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`
- Candidate evidence artifact: **11960534932**, ZIP size 21,153,522 bytes
- Candidate ZIP SHA-256: `1aa5aca91348cb103c3b6fbd05370ba0c0d6bf951312aca9724c09c5fae9775b`
- Separate original-JNI debug artifact: **11960694902**, ZIP size 36,891,118 bytes
- Debug ZIP SHA-256: `524403cf264a9e42d59a626e2217c46bc133b8d7633aa63852f88dbfbbb32e68`

The candidate artifact expires **2026-10-29 09:31:42 UTC**; the debug artifact
expires three seconds later. Retain approved copies before expiry if this
candidate remains under evaluation. These are experiment artifacts, not a release.

## What actually passed

1. The exact first native build was reused by repository/run/head/artifact
   identity and full ZIP SHA-256. Every one of its entry/output hashes was
   rechecked. Source/tool/archive pins remain those documented in the recipe and
   [native review](../sherpa-source-native-2026-10-15/REPORT.md).
2. The full Gradle graph was independently reviewed before this strict run:
   **460 files / 242 components / 326,450,244 official bytes**, including the
   32 downloaded POMs omitted from Gradle's generated metadata. Official byte
   comparison and available same-repository checksum sidecars passed. The XML
   and complete review evidence are separately pinned under the recipe's
   `gradle-lock/`. This is not a publisher-signature authentication claim.
3. Strict CI used a fresh staged project and Gradle home, no build cache,
   rerun tasks, metadata verification and checksum-only strict dependency
   verification without trust exclusions. All staged inputs were rechecked.
   Its **404 actual downloaded files** matched the reviewed lock. **56 additional
   captured/reviewed files were unused** by the strict invocation, chiefly test
   infrastructure and unused plugin configurations; the exact list is retained.
   No unknown or mismatched downloaded cache file was accepted.
4. The final AAR contains exactly two ABIs and two SOs per ABI:
   `libsherpa-onnx-jni.so` and `libonnxruntime.so`. There is no C/CXX API SO, ORT
   Java bridge/classes JAR, additional ABI, or TTS-facing Kotlin class.
5. Exactly **48 classes** compiled from the **eight unmodified official Kotlin
   sources**, with JVM target 1.8. All **32 compiled native methods** have their
   required JNI exports on both ABIs. SourceFile/package/ABI/library whitelists,
   duplicate ZIP rejection and native-byte checks passed. Packaged minSdk is 21;
   JNI consumer keep rules are present.
6. **29 packaged notice files** passed complete inventory and hash checks:
   23 selected dependency attribution files, the recipe notice, the exact
   combined NDK notice, and all four original official NDK notices. Notice
   verification establishes the recorded contents, not legal clearance or the
   vendor binary's complete SBOM.
7. All **29 outer artifact-manifest entries**, candidate ZIP digest, source/run
   identity, final AAR digest and separately uploaded debug archive were
   independently checked after download. Both original JNI debug SOs are
   byte-identical to the first native build.

## Explicit stripping evidence

The official checksum-verified NDK r28c `llvm-strip --strip-unneeded` was applied
only to rebuilt JNI. Its executable was matched to the pinned official archive.
A repeated strip from each original produced identical bytes and matched the
separately recorded local results. Dynamic exports and every program header
remained identical. PT_LOAD bytes remained identical except the ELF section-table
location/count bookkeeping fields changed by stripping. No `.debug_*` or
`.zdebug_*` sections remain in the packaged JNI.

| ABI | Original JNI bytes | Stripped JNI bytes | Stripped SHA-256 |
|---|---:|---:|---|
| arm64-v8a | 70,491,184 | 3,433,440 | `7c2add157be1d7ad3208f99ff4e5ebed6e50cccac6ef75b33b7b08d77cd7ddc7` |
| x86_64 | 69,695,368 | 3,728,696 | `d5a02856e9e0a05b035628dbff5c6cdd6a3e8ef06e7a661ba1b364df746816e9` |

This reduction removes debug/symbol material from these unstripped native build
outputs. It does not itself measure application size, startup, memory or latency
improvement against the stock runtime.

ORT's two SOs remain byte-identical to the pinned official-maintainer-selected
vendor ZIP. All four final SOs pass PT_LOAD 16 KB static alignment. The arm64 ORT
PT_GNU_RELRO unrounded end still has **remainder 12,288 modulo 16,384**. That is a
retained static warning, with no Android runtime incompatibility demonstrated.

## Open acceptance gates

- Compile FrameNest against this narrowed AAR, then execute JNI loading and all
  retained paths. This packaging experiment did not change the app dependency.
- Test actual 4 KB and 16 KB Android environments and the final APK's ZIP/ELF
  alignment; explicitly assess the retained ORT RELRO warning with evidence.
- Compare clean complete builds and obtain an immutable host for any stronger
  reproducibility claim. Recorded rolling hosted-VM provenance and repeatable
  stripping do not establish full-build reproducibility.
- Run this variant's own startup/memory/latency/bilingual accuracy, SenseVoice ITN,
  seek/cancel/timestamps and long-media regressions. Stock A/B results do not
  automatically apply.
- Complete exact ORT vendor-build/transitive-SBOM coverage, Eigen source
  availability, embedded attribution reconciliation, static C++ runtime and
  model/app license review. No legal clearance or GPL-free conclusion is made.

## Verification commands and retained evidence

- `python3 -m unittest discover -s docs/experiments/FN-74-asr-only-recipe/scripts -p 'test_*.py' -q`: **54 passed**
- `python3 -m py_compile docs/experiments/FN-74-asr-only-recipe/scripts/*.py`: passed
- `git diff --check`: passed
- Recipe `gradle_lock.py check-review` for the exact reviewed XML/evidence: passed
- Recipe `audit_artifact.py --aar <downloaded AAR> --report <report>`: passed again
  independently after CI, including 48 classes / 32 native methods / 29 notices
- Additional post-download checks of minSdk, consumer rules, empty JNI debug
  sections, actual cache hashes and original debug-archive bytes: passed

`SUMMARY.json` records the result and gates; `independent-static-audit.json` and
`independent-package-check.json` retain the post-download checks. Native/strip,
Gradle cache, environment, build-log and outer artifact inventory evidence are
included here, with file hashes in `SHA256SUMS`. Large binary artifacts remain in
the identified Actions run and the downloaded evaluation workspace.
