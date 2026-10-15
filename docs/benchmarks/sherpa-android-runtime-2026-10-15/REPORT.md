# Sherpa Android runtime upgrade audit

Date: 2026-10-15. Read-only audit for FrameNest FN-74, based on the app's SenseVoice/experimental Zipformer usages at the released 0.6.9 baseline. This report records a read-only artifact/source audit; implementation and runtime results are tracked separately.

## Decision

The official 1.13.8 Android AAR exists, matches its GitHub SHA-256, and is also served byte-for-byte by the Android-only JitPack child module. It is a suitable **local A/B candidate**, subject to full app recompilation and native runtime tests. It is **not cleared for distribution by this audit**: the bundled TTS licensing question and 16 KB RELRO checks remain release gates. No Android latency, CER, memory, or thermal improvement has been measured here.

## Provenance and dependency coordinates

Official source commits:
- 1.12.32: `5f3dcc1dce87e8a6d16887d516bb35aaca4c23eb`
- 1.13.8: `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`
- Comparison: https://github.com/k2-fsa/sherpa-onnx/compare/v1.12.32...v1.13.8

Downloaded and hashed actual artifacts:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Official 1.12.32 AAR | 46,589,150 | `43450d480eda70de929be82711851e8e91d82ea640f7d2296c1f6526fd792cad` |
| Official 1.13.8 AAR | 50,129,134 | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |
| JitPack Android child 1.13.8 AAR | 50,129,134 | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |
| JitPack Android child 1.13.8 POM | 1,205 | `755acf5ea7ce090b6b30768ed5dd95c52a5ebc7ba983fb3d944b1efaf574a263` |

The old official AAR's SHA exactly equals FrameNest's existing Gradle verification entry. The new AAR is 3,539,984 bytes (+7.60%) larger compressed.

URLs:
- https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.32/sherpa-onnx-1.12.32.aar
- https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
- https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/1.13.8/sherpa-onnx-1.13.8.aar
- https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/1.13.8/sherpa-onnx-1.13.8.pom

Use `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8`. That child POM has `packaging=aar` and no dependencies. The old group at version 1.13.8 resolves to an aggregator containing Android, JVM and six desktop/native modules (the independently retrieved aggregator POM was inspected). A version-only bump is therefore inappropriate. Official `jitpack.yml` now publishes multiple modules, unlike the old single-AAR publication.

## API and ABI findings

Both AARs contain the same four ABI directories: arm64-v8a, armeabi-v7a, x86, x86_64. Each has libonnxruntime.so, libsherpa-onnx-jni.so, libsherpa-onnx-c-api.so and libsherpa-onnx-cxx-api.so. Both manifests declare minSdk 21. Both classes.jar use Java classfile major 52 (Java 8); old/new class counts are 120/122. The upstream Kotlin tool version remains 1.7.20 in the AAR project.

Actual class-file public method-descriptor comparison, rather than only source inspection:
- No removed methods in OfflineSenseVoiceModelConfig, OfflineRecognizerConfig, OfflineRecognizerResult, OfflineStream, OnlineModelConfig, OnlineRecognizerConfig, OnlineRecognizerResult or OnlineStream.
- OfflineRecognizer adds createStream(hotwords); OnlineRecognizer adds an unrelated QNN path helper.
- OfflineModelConfig adds Qwen3/Cohere members and changes its constructor/copy/destructuring descriptors. OnlineTransducerModelConfig adds qnnConfig and changes constructor/copy descriptors.
- FrameNest uses named Kotlin arguments and defaults in these constructors, so the inspected source usages remain compatible. This is not a compiled app test. Recompile the app against the complete new AAR. Do not combine old Kotlin classes or precompiled clients with new JNI libraries.
- All 125 old Java JNI exported symbol names are present in the new JNI library on all four ABIs; each new library exports 133 Java JNI names. This does not establish general native C/C++ structure ABI compatibility.
- OnlineRecognizerResult still does not expose native start_time. Keep FrameNest's fresh-stream/no-reset timestamp strategy.
- The 1.13.8 constructors explicitly reject a zero native recognizer pointer. Keep null AssetManager for private filesystem model paths.

Raw evidence: `class-api-diff.txt` and `jni-symbol-audit.json`; original extracted class/API inventories remain reproducible from the pinned AARs. Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api

## Native behavior relevant to SenseVoice

1. **Android ORT really changed:** every old libonnxruntime.so has `1.23.2`/`VERS_1.23.2`; every new one has `1.28.2`/`VERS_1.28.2`. The Android-specific CMake dependency also pins the latter, independently of Python. Its official dependency archive hash is `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`.
2. **Filesystem model loading:** since 1.12.36 / PR #3482, SenseVoice constructs Ort::Session from the model path, rather than ReadFile into an intermediate full model buffer first. This is a plausible startup-memory improvement, not a measured result or a guarantee of mmap. FrameNest's file-only model path takes this route.
3. **LFR acoustic input changed:** old SenseVoice concatenated full forward windows only, yielding `(frames - window_size) / window_shift + 1` frames for normal inputs. New shared ApplyLfr centers each window, repeats first/last frames at boundaries, and yields `ceil(frames / window_shift)`. With a 7-frame/6-shift model this changes boundary context and can change output/timestamps. A/B accuracy/latency differences cannot be attributed to ORT alone.
4. **ITN:** since 1.13.1 / PR #3565, a stream option `use_itn` overrides the model default. If absent, the recognizer uses the existing config value. FrameNest still requests true; the upgrade does not turn its ITN off. No app change is required to preserve current behavior.
5. **JNI text safety:** since 1.12.40 / PR #3527, result text, tokens, language, emotion and event pass through SafeNewStringUTF rather than direct NewStringUTF. This is relevant Android text robustness, not an ASR quality claim.
6. **Optional session configuration:** newer source accepts provider config files with memory-arena/pattern and namespaced ORT session options. Plain `provider="cpu"` does not opt into these overrides; no speculative tuning is recommended here.

Sources:
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-sense-voice-model.cc
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-sense-voice-impl.h
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/lfr.cc
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/jni/offline-recognizer.cc
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/CHANGELOG.md

## 16 KB findings: LOAD passes; RELRO does not

Every PT_LOAD in all 32 inspected native libraries has p_align 0x4000. The current official AOSP check_elf_alignment.sh was fetched, inspected and run on both extracted JNI directories: all 32 libraries report ALIGNED. That script checks the first LOAD alignment and does not check RELRO. Its fetched SHA-256 is c0930b897d70233c0d37ff85f5a22bf049a20a81a2754b323552be7e73c7d298. These official script passes are distinct from the additional documented RELRO warning below. Current Android guidance also specifies `(GNU_RELRO VirtAddr + MemSiz) % 0x4000 == 0`. Both official AARs fail this check on all four arm64 libraries.

New arm64 RELRO end remainders:
- libonnxruntime.so: 0x3000
- libsherpa-onnx-c-api.so: 0x2000
- libsherpa-onnx-cxx-api.so: 0x3000
- libsherpa-onnx-jni.so: 0x1000

For example JNI's RELRO starts at 0x480750 with MemSiz 0x108b0, ending at 0x491000. New x86_64 ORT passes RELRO but its three sherpa libraries do not. `native-audit.json` records all ELF findings and individual binary hashes/sizes.

Upstream CMake explicitly adds max-page-size=16384 but not common-page-size=16384. The source fact is consistent with the binary result; it does not substitute for a target-device test. Treat these remainders as an official documented static-check warning, not proof of a runtime crash: no 16 KB device was tested. First look for a pinned official/vendor ORT artifact that passes the documented checks and runtime validation; only rebuild it if that is needed. A future source build should pin a suitable modern NDK and verify both max/common-page behavior in every resulting ELF, including ORT. Final APK/AAB packaging and runtime tests are still needed; AAR ZIP offsets are not an APK zipalign result.

Official verification instructions: https://developer.android.com/guide/practices/page-sizes

Official script source: https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh

The documentation rule is exactly `(VirtAddr + MemSiz) % 0x4000 == 0`; the endpoint is not rounded before applying the modulus.

## License evidence and ASR-only source path

The root sherpa LICENSE is byte-identical Apache-2.0 in both tags (SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`). The Android child POM labels Apache-2.0. ORT's upstream license is MIT. Neither downloaded AAR contains a LICENSE/NOTICE entry; its POM label is not a complete bundled-dependency audit.

Both actual arm64 JNI libraries contain piper-phonemize source-path and espeak runtime strings. Both versions' build configuration includes espeak-ng and piper-phonemize when TTS is enabled. The current pinned espeak source COPYING is GPL v3. Upstream maintainer issue #3731 explicitly identifies this bundled dependency licensing concern and plans removal for 2.0.0. This is a concrete distribution-review gate, not a legal conclusion about the app; it also affects the historical baseline.

Official evidence: https://github.com/k2-fsa/sherpa-onnx/issues/3731

An ASR-only official-source build can exclude this chain:
- Pin official sherpa commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` and record a verified source archive SHA if archiving it.
- All four official `build-android-*.sh` honor exported `SHERPA_ONNX_ENABLE_TTS=OFF` and pass it to CMake. Keep `SHERPA_ONNX_ENABLE_JNI=ON`; `SHERPA_ONNX_ENABLE_C_API=OFF` is sufficient for FrameNest's Kotlin-only runtime unless another requirement needs C APIs. Avoid unrelated RKNN/QNN providers.
- Top-level CMake suppresses espeak/piper FetchContent; core CMake suppresses all TTS sources and piper target linkage; JNI CMake suppresses offline-tts.cc. Setting only BUILD_ESPEAK_NG_EXE=OFF does **not** remove the library.
- The wrapper supports externally verified ORT through SHERPA_ONNXRUNTIME_LIB_DIR plus SHERPA_ONNXRUNTIME_INCLUDE_DIR. Rebuilding sherpa alone cannot change the prebuilt ORT RELRO values. Pin and validate ORT/toolchain separately, reusing a suitable verified official/vendor ORT build if available rather than assuming every component must be rebuilt.
- Follow the official AAR workflow: copy only the newly built intended ABI/native files into the AAR project's jniLibs, then run `:sherpa_onnx:assembleRelease`. Do not merge stale stock libraries into a reduced build.
- Before distribution, save exact source/dependency hashes and licenses/NOTICE; verify CMakeCache TTS=OFF, generated build/link dependencies contain no espeak/piper targets/objects, final symbols/strings do not retain their code, every packaged ELF passes LOAD/RELRO checks, APK zip alignment passes, and relevant Android device behavior passes. Absence from DT_NEEDED alone is insufficient because the stock TTS chain is statically linked.

This exclusion path is source-verified, not a completed or benchmarked source build. The fetched Kotlin AAR API files may still declare TTS classes in a reduced package; app code must not invoke native TTS methods that were intentionally excluded.

## Commands and verification limits

Executed: official HTTPS downloads; SHA-256/size comparisons; zip extraction/inventory; `readelf -lW`; `nm -D --defined-only`; `strings`; exact C++/Kotlin source diffs; Python class-file descriptor parser; source build-guard inspection. Initial old-group JitPack requests returned 403 in this route; the Android-child requests succeeded and matched official bytes.

Not executed: full FrameNest Gradle build/tests, Android JNI inference, ASR-only NDK compilation, APK zipalign, 16 KB runtime test, phone benchmarks, or legal compliance assessment. These are not represented as passes.
