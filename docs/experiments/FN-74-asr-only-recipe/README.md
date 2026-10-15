# sherpa-onnx 1.13.8 ASR-facing / no-TTS Android recipe

Prepared 2026-10-15. **Candidate recipe only: no native build, AAR build, Android execution, 16 KB device test, benchmark, or legal clearance has been completed.** The original recipe did not change FrameNest's stock runtime A/B, production dependency, CI, or PR. The later CI-NATIVE extension adds only a dedicated experimental workflow; production dependency and existing app CI remain unchanged. This is a separate future variant and must receive separate acceptance; stock-AAR results do not transfer to it.

An optional ordinary native-only CI extension is documented in [CI-NATIVE.md](CI-NATIVE.md). It permits explicitly exploratory hosted-VM provenance, with the release/reproducibility gate closed; it does not change the stock app dependency or execute Gradle/KVM.

## Fixed inputs and scope

- Official release source commit: `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.
- Source archive SHA-256: `0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2`.
- Official maintainer-selected ORT Android 1.28.2 vendor zip SHA-256: `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`.
- Linux x86_64 build host; NDK **r28c / 28.2.13676358**; CMake **3.31.6**; Ninja **1.12.1**; Python **3.12+**; JDK **21**.
- Target ABIs only **arm64-v8a and x86_64**, Android API 21, static C++ runtime.
- Native target `sherpa-onnx-jni`; JNI ON; TTS, speaker diarization, C API, executables, tests, Python, websocket, PortAudio and optional accelerators OFF. Full option set is in `manifest.json`.
- Native package whitelist: **libsherpa-onnx-jni.so + libonnxruntime.so only**, per ABI. No C API/CXX API SO, ORT Java JNI bridge, ORT classes.jar, or other ABI is copied.
- Kotlin whitelist: exactly **eight** unmodified official files: OfflineRecognizer, OfflineStream, OnlineRecognizer, OnlineStream, FeatureConfig, HomophoneReplacerConfig, QnnConfig and VersionInfo. No Tts.kt or diarization declarations remain to call absent natives. The static audit checks each class's SourceFile and every compiled native method's exported JNI binding.
- This is an **ASR-facing no-TTS** package. Upstream's JNI/core still compiles other non-TTS subsystems; do not describe it as a proven minimal-ASR native binary.

`manifest.json` gives portable URLs, exact archive sizes/hashes, source-file hashes, FetchContent overrides and build options. `dependency-audit/dependency-inventory.json` documents the effective recursive native dependency graph and all nine dependency/vendor archives. `dependency-audit/NOTICE-CHECKLIST.md` and `license-file-manifest.json` record 27 source-license/attribution files. The main sherpa archive is the tenth locked input.

## Why TTS-off is meaningful

At this pinned commit, root `CMakeLists.txt` guards the espeak-ng and piper-phonemize includes with SHERPA_ONNX_ENABLE_TTS; `sherpa-onnx/csrc/CMakeLists.txt` separately guards TTS sources and piper linkage; `sherpa-onnx/jni/CMakeLists.txt` guards offline-tts.cc. The official Android build scripts accept the same option. Turning off executables alone would not remove TTS.

This recipe checks the resulting CMake cache, compile database, link map and JNI exports. Checking DT_NEEDED alone cannot detect statically embedded TTS. The final package audit also enforces class/native-file whitelists. These are designed checks, not evidence from a build that has already run.

## Native preparation and build

The original native/packaging commands below do not install a toolchain, accept SDK licenses, run KVM/emulators or publish a package. Supply already authorized, preinstalled official tools for them. The separate CI-NATIVE extension can download and extract checksum-verified official tools into an isolated temporary directory; it does not modify system installations or accept agreements. The CI image digest must pin the actual JDK, SDK and tool binaries; exact component versions alone do not establish bit-for-bit reproducibility.

From this directory:

```sh
python3 scripts/inputs.py verify
# If transferring the small recipe bundle without archives:
python3 scripts/inputs.py fetch

# Optional preparation-only validation. WORK must not exist.
python3 scripts/inputs.py prepare --work /absolute/new/prepare-work

# A real native build creates its own fresh workspace; do not use prepare-work above.
python3 scripts/build_native.py \
  --work /absolute/new/native-work \
  --ndk /opt/android-sdk/ndk/28.2.13676358 \
  --ndk-runtime-notices /absolute/reviewed/ndk-runtime-NOTICE.txt \
  --cmake /opt/cmake-3.31.6/bin/cmake \
  --ninja /opt/ninja-1.12.1/ninja \
  --host-image-digest sha256:YOUR_REAL_64_HEX_IMAGE_DIGEST
```

The build verifies all inputs, safely extracts separate source/dependency trees per ABI, overrides every active FetchContent source and uses the exact verified ORT include/lib directories. FetchContent is disconnected. Run the actual native configure/build in CI with outbound network disabled as an additional guarantee; disabled transitive test paths contain unpinned or HTTP/MD5 downloads. A fresh source tree matters because upstream Android CMake modifies simple-sentencepiece in place. The pinned archive also contains two absolute symlinks under unused Go examples; safe extraction explicitly omits only those two recorded paths/targets in manifest.json. Every other unsafe archive path/link remains a hard error. No Android/native source is excluded or patched by the extraction script.

The wrapper records tool binaries/version data, requested options, configure/build logs, compile commands, JNI link map, ELF checks and output hashes. It adds both max-page-size and common-page-size 16384 linker flags and stable prefix mappings. It does **not** claim bit-identical results before two clean builds are compared.

Upstream version.cc embeds SHA `8c8e275d` and its earlier date even in release commit `11afbd...`; no source patch is silently made here. Use the external manifest, not VersionInfo.gitSha1, as source provenance.

## Kotlin/AAR packaging and the open Gradle lock gate

The generated project retains upstream's Gradle 8.6 / AGP 8.4.0 / Kotlin 1.7.20 / compileSdk 34 / minSdk 21 / Java 8 choices, pins build-tools 34.0.0, and removes unused AndroidX, Material and test dependencies. Gradle's official distribution checksum is pinned. Kotlin stdlib 1.7.20 is a declared host dependency, not copied into the AAR. A consuming app must resolve and license its Kotlin runtime normally.

**The full Gradle plugin/transitive dependency checksum graph has not been downloaded or independently reviewed. A normal AAR build refuses to proceed without a reviewed verification-metadata.xml and its recorded SHA-256.** This is an explicit remaining reproducibility gate, not a fully executed/reproducible release claim.

```sh
python3 scripts/package_aar.py stage \
  --work /absolute/native-work --project /absolute/new/aar-project

# First CI pass only: create a quarantined checksum inventory for independent review.
# This can stage from prepare-work too, without native binaries, for dependency review.
python3 scripts/package_aar.py capture-lock \
  --project /absolute/aar-project --sdk /opt/android-sdk \
  --host-image-digest sha256:YOUR_REAL_64_HEX_IMAGE_DIGEST

# Review every resolved dependency's official provenance/checksum. Commit the approved
# Gradle verification file in the separate development branch; do not approve by TOFU alone.
# Then use a new staged project and strict verification:
python3 scripts/package_aar.py build \
  --project /absolute/new/strict-aar-project --sdk /opt/android-sdk \
  --host-image-digest sha256:YOUR_REAL_64_HEX_IMAGE_DIGEST \
  --verification-file /absolute/reviewed/verification-metadata.xml \
  --verification-sha256 YOUR_REVIEWED_LOCK_SHA256
```

Lock-capture output is explicitly quarantined and cannot establish release readiness. Strict packaging preserves native bytes, checks them against native-output-manifest.json, enforces the source/API/native whitelists and includes notice assets. ZIP order/timestamps are normalized, but a second clean build comparison remains required. It never publishes to Maven or modifies the application's dependency coordinate.

## 16 KB: preserve the ORT warning

The stock vendor ORT has 16 KB PT_LOAD alignment, but its arm64 PT_GNU_RELRO end fails the newer documented static condition `(p_vaddr + p_memsz) % 16384 == 0`. The documented rule uses the **unrounded end**, not an end rounded to the device page size. The current official AOSP check_elf_alignment.sh tests LOAD alignment and does not implement this RELRO check.

Our checker reports RELRO as a **static-check warning**. It is not a demonstrated Android crash or a substitute for loading the final library on a real 16 KB environment. NDK r28c and new linker flags can affect the rebuilt JNI SO; they cannot alter the retained ORT vendor binary. Reusing this pinned vendor avoids an unreviewed all-dependency rebuild while retaining an explicit release-review gate. Do not call the resulting AAR fully 16 KB accepted until the final app's ELF, APK ZIP alignment and runtime tests pass and the warning is resolved or explicitly assessed with evidence.

Sources: [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes), [official AOSP alignment script](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh), [Gradle checksum verification](https://docs.gradle.org/8.6/userguide/dependency_verification.html), [official source commit](https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf).

## Release gates for this separate variant

1. Complete the reviewed Gradle checksum lock, pin the CI image digest, verify official NDK provenance, retain NDK static-runtime notices and record SDK tool hashes. The original preparation downloaded no toolchain. The CI extension subsequently downloaded and verified the exact official NDK/CMake/Ninja archives and actual notices; it has not yet configured or compiled native code.
2. Run clean arm64 and x86_64 native/AAR builds. Inspect compile commands, link maps, static dependency inventory, ELF LOAD/RELRO, JNI bindings and the absence of TTS/Piper/espeak code. Compare two clean candidate output hashes. Do not infer success from this recipe's syntax/fixture tests.
3. Complete notice/license review: Eigen MPL source availability, exact ORT vendor-binary notice/SBOM coverage, embedded JSON attribution discrepancies, NDK runtime notices, Kotlin/host graph and model licenses. The vendor ORT ZIP contains no license files; the bundled Microsoft notices are verified source notices at commit 33ca9628233dc8f002435e868d4c2e9f82766ca1, not proof of the vendor's complete build contents. No GPL-free/legal-compatibility claim is made.
4. Add this candidate only on its independent development branch, with a new exact artifact hash. Compile FrameNest against the narrowed Kotlin API and test JNI load and all retained calls.
5. Test final APK on 4 KB and actual 16 KB Android environments, inspect every packaged dependency, run current official zipalign validation and resolve/assess RELRO warning. No KVM/device work was performed here.
6. Separately rerun startup/memory/latency and bilingual accuracy tests, SenseVoice ITN, seek/cancel lifecycle, stream timestamps and long-media regression tests. The 1.12.32-to-1.13.8 feature/LFR changes are separate confounders. Stock A/B measurements are not results for this source-built variant.

Preparation validation passed all ten archive hashes/sizes, all 27 notice hashes, safe extraction of both private ABI source trees, eight-file Kotlin project staging, Python syntax, CLI help, and seven positive/negative unit fixtures. The first extraction intentionally failed on the upstream absolute Go symlinks; the narrow recorded exclusion corrected that preparation issue. Validation logs and vendor-ORT static-only checks are in validation/. No NDK/Gradle build or Android runtime test occurred.

The prepared recipe and license evidence can now be reviewed and imported into a separate branch. The current production worktree remains untouched by this work.
