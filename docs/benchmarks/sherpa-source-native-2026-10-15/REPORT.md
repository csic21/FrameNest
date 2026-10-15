# FN-74: first source-native build, independent audit

## Result

**Native build and static audit passed for arm64-v8a and x86_64. Release gate remains closed.** The actual compiled/link evidence excludes TTS, espeak and Piper. This is an exploratory native build, with no AAR or Android runtime acceptance and no legal clearance.

- Run: [38706441953](https://github.com/csic21/FrameNest/actions/runs/38706441953), job `116326917905`, artifact `11958847995`.
- Recorded CI commit: `8fb54b09e405a9cb9d2a0211d83176ced0328af8`.
- Downloaded ZIP: **55,597,220 bytes**, SHA-256 `5dc4019557ce6ac0f9a203fb6e00bd4e52f58153e2cf17347805cf108744e48e`, independently rehashed.
- Official sherpa source: [11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf](https://github.com/k2-fsa/sherpa-onnx/tree/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf). Archive SHA-256 `0a8db6c55dd318f4a688faba85f7760b99a6c92e8ef8864479d418531bee1ac2`.

Run identity was provided by the integration owner and agrees with the artifact's recorded provenance. This audit independently checked local artifact bytes and bundled recipe copies; it did not query GitHub run identity or reconstruct the remote commit tree.

## What was actually verified

1. **Integrity:** all 73 manifest-listed artifact files match exact size/SHA-256; the manifest itself is the only expected unlisted file. Exactly four native outputs, two library names across two ABIs, match their native manifest. All 27 notice records match. The independent notice audit passed all 30 checks. Ten pinned source/vendor archives and three official tool archives were also rehashed from retained inputs.
2. **Compile:** each ABI built 259 units, all with `-O3 -DNDEBUG` and retained `-g`. The 211 sherpa units per ABI have `SHERPA_ONNX_ENABLE_TTS=0` and speaker-diarization disabled. The CMake cache and requested flags agree on Release/JNI-on/TTS-off/C-API-off and `c++_static`. SenseVoice was actually compiled. Dependency source overrides point to the pinned roots, with FetchContent fully disconnected.
3. **Link:** both logs end with successful step 268/268. No compiler warning/error lines occurred. Maps show sherpa/fst/kaldifst/kaldi/fbank/kissfft/simple-sentencepiece and NDK static libc++, libc++abi, libunwind and builtins. There are no TTS/espeak/Piper compile inputs, link-map markers, local or exported symbols, or strong TTS string markers. A naive `espeak` substring also matches `WeSpeaker`, which is a separate speaker-embedding label and was not counted as a TTS finding. The saved build log is non-verbose, so this audit does not claim to recover the full executed link command.
4. **JNI/API:** each actual JNI exports 117 defined dynamic symbols. Compared with official stock 1.13.8's 133, the only 16 removals are GeneratedAudio (1), OfflineTts (8), and OfflineSpeakerDiarization (7); none were added. The eight retained official Kotlin source filenames correspond to 48 stock classes with 32 native declarations, all resolving by name in both new JNI libraries. This is a stock-classfile comparison, not a newly compiled Kotlin/AAR field-lookup or runtime test. Other native capabilities, such as VAD and audio tagging, remain; “ASR-facing, no TTS” does not mean a minimal ASR-only native implementation.
5. **Toolchain:** actual selected binaries are official NDK r28c / 28.2.13676358, CMake 3.31.6 and Ninja 1.12.1; Android API 21 targets. Archive and recorded executable hashes agree. Runner-installed Ninja 1.13.2 and other NDK versions were inventory observations, not the selected tools. Recorded existing SDK license evidence was used; no new license-acceptance step was performed.
6. **Notices:** 25 notice texts/excerpts reproduce directly from rehashed source archives; two ORT notices match earlier immutable-source fetch evidence. Four original NDK notices match the official r28c archive and pins. Their deterministic combined notice is **1,941,704 bytes**, SHA-256 `e15e0bcaee943231836afe3a961b8a1d2ade12cb9e244c29d7d13a84cf71750f`.

The configure warnings were intentionally disabled Git discovery and an unused `EIGEN_ENABLE_LAPACK_TESTS` option. No LAPACK/test compilation inputs appeared.

## Exact native outputs

| ABI | Library | Bytes | SHA-256 |
|---|---|---:|---|
| arm64-v8a | libsherpa-onnx-jni.so | 70,491,184 | `f2184ae4b1204356551c55b43d8e722658ac48ae33ed50d57b64651ba4357f0d` |
| arm64-v8a | libonnxruntime.so | 22,249,560 | `33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1` |
| x86_64 | libsherpa-onnx-jni.so | 69,695,368 | `37759e9147824134ed6da17acc5ca56a34844d2532c0f73600bc8e7a8ce7dd69` |
| x86_64 | libonnxruntime.so | 25,581,120 | `087e84297cad64acb823c3d901d6e0cc934e91add7cdb20da802cd1c0bea5c3e` |

Both ORT libraries exactly match members of the pinned csukuangfj vendor archive. This establishes byte provenance, not Microsoft binary attestation or a complete vendor SBOM. JNI dynamic dependencies are `libandroid`, `liblog`, `libonnxruntime`, `libm`, `libdl`, `libc`; ORT needs `libdl`, `liblog`, `libm`, `libc`. No shared libc++ is required.

The rebuilt JNI files are **unstripped**. Their debug sections total 65,781,828 bytes (arm64) and 65,091,999 bytes (x86_64). Summed LOAD file bytes are 3,431,082 and 3,726,338, respectively; these figures are not measured RSS or final package size. Decide the symbol-stripping/debug-artifact policy before AAR packaging and audit/hash the final bytes again.

## 16 KB boundary

All four ELF files have PT_LOAD alignment of 0x4000 and compatible offset/virtual-address congruence. Both rebuilt JNI files also pass the unrounded RELRO-end check. The unchanged arm64 ORT retains a warning:

- `p_vaddr = 21,803,840`, `p_memsz = 474,304`.
- `(p_vaddr + p_memsz) % 16384 = 12288 = 0x3000`.
- The x86_64 ORT remainder is zero.

The [Android guidance](https://developer.android.com/guide/practices/page-sizes#check_the_relro_security_flag), rechecked on 2026-10-15, states: “Formula: (VirtAddr + MemSiz) % 0x4000 == 0”. It uses the unrounded end. The [AOSP alignment script](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/tools/check_elf_alignment.sh) checks LOAD alignment and does not implement this additional RELRO test. These checks do not establish a crash in this particular retained ORT binary: no Android execution was performed. Keep the warning visible until resolved or explicitly assessed with device evidence. Final APK ZIP alignment, all packaged dependencies, and real 4 KB/16 KB load/function tests remain required.

## Remaining release gates

- Build and verify the narrow Kotlin wrapper and final AAR: actual class/JNI bindings, rejected duplicate ZIP entries before normalization, native inventory, final notice full-set/hash checks and Gradle checksum/dependency graph review.
- Apply any agreed strip policy, then verify exact final native bytes, symbols and ELF properties. Preserve debug artifacts separately if needed.
- Run actual Android acceptance and bilingual accuracy/ITN/latency/memory/timestamp/seek/cancellation regressions for this source variant. Stock A/B acceptance cannot be transferred.
- Establish clean repeat-build comparison and reproducibility evidence. The actual hosted VM image is `20261012.334.1`; its [release](https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20261012.334) is recorded, but it has no immutable OCI digest. This run remains explicitly exploratory.
- Finish exact ORT vendor build-options/transitive SBOM/notice review, Eigen covered-source availability and modification records, JSON Hedley/Abseil attribution reconciliation, static NDK runtime component mapping and final public notice packaging. Models and host-app/Kotlin/Gradle dependencies are outside this native inventory. Authenticated aggregate NDK notices include build-tool texts; this alone does not determine the generated app's licensing. No “GPL-free” or legal-clearance conclusion is asserted.
- Preserve the actual configured simple-sentencepiece Android source modifications and original notices when preparing corresponding source.

## Evidence and interpretation

`SUMMARY.json` is the concise machine-readable finding. `independent-native-check.json` contains detailed compile/export/ELF results; `independent-license-check.json` records all integrity checks and their limitations. Small `ci-*` and `input-*` files are unchanged copies from this run, not newly generated substitute manifests. `SHA256SUMS` hashes every retained file except itself. Native SO files, source/tool archives, raw link maps, models and multi-megabyte notices remain in the original artifact; their exact manifest hashes are retained here.

The historical input recipe/checklist contains preparation-phase statements such as “not built” and a recommendation to disable preinstalled ORT discovery. This successful run supersedes those earlier status statements. Its later recipe intentionally uses discovery ON with explicitly staged pinned ORT paths; compile/cache evidence and exact ORT output hashes show no observed fallback. The historical input copies have not been rewritten to disguise their chronology.

## FN-74 handoff

- Status: native build/static audit complete; packaging/runtime/reproducibility/legal gates remain open.
- Paths: this documentation directory only. No recipe, workflow, app dependency or source code changed for this audit.
- Validation: independently parsed ELF headers; inspected `readelf -d/-Wl`, `nm -D/--defined-only/-a/-C`, strings, CMake cache, compile commands, link maps and build logs; rehashed manifests, official archives and notice origins. Independent native audit assertions and all 30 notice-integrity checks passed. JSON parse and retained-file SHA-256 validation passed.
- Gradle/UI tests: not run for this documentation-only audit. No AAR, emulator or device acceptance is claimed.
- Decision records: no accepted product or integration decision changed.
- Integration: final packaging work may rely on the verified source/native provenance and no-TTS compile/link evidence, while retaining every remaining gate above.
