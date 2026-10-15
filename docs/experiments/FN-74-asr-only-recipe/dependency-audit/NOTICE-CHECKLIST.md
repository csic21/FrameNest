# sherpa-onnx 1.13.8 no-TTS Android dependency and notice checklist

Audit date: 2026-10-15 UTC. Main source commit: `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`.

## What was verified

- Downloaded eight source dependency archives directly from their official project or the exact maintainer fork selected by sherpa. Every complete archive matched the SHA-256 literal in the effective CMake dependency declaration.
- Independently rehashed the parent-provided ONNX Runtime Android vendor archive against sherpa's CMake SHA-256 pin.
- Inspected the nested CMake dependency chain, actual license text, vendored header attributions, and excluded download paths.
- Copied 27 license/attribution records, with origin, size and SHA-256 recorded in `license-file-manifest.json`.
- Retrieved Microsoft ONNX Runtime `LICENSE` and `ThirdPartyNotices.txt` at official tag `v1.28.2`, resolved with `git ls-remote` to `33ca9628233dc8f002435e868d4c2e9f82766ca1`, and re-downloaded/verified the same bytes at that immutable commit. See `onnxruntime-notice-provenance.json`.

This is a static build-input/notice inventory. No Android configure, compile, link, APK/AAR execution, legal clearance, full binary SBOM, model-license review, or NDK-runtime notice review is claimed.

## Exact scope and selected dependency graph

Configuration: Android JNI ON, shared sherpa libraries ON, TTS OFF, speaker diarization OFF, C API OFF; Python, tests, binaries, websocket, portaudio, WASM and optional provider extensions OFF. Use a clean cache. The plan exposes ASR-facing wrappers; these flags do **not** remove every other non-TTS native capability from sherpa's core/JNI.

```text
sherpa-onnx (Apache-2.0)
├── kaldi-native-fbank 1.22.3 (Apache-2.0), static
│   └── kissfft febd4caeed32e33ad8b2e0bb5ea77542c40f18ec (BSD-3-Clause), static
├── kaldi-decoder 0.3.0 (Apache-2.0), static
│   ├── kaldifst 1.8.0 (Apache-2.0 plus its Legal Notices preface), static
│   │   └── OpenFst 1.8.5-2026-07-09 (Apache-2.0), static fst/fstfar
│   └── Eigen 5.0.1 (primarily MPL-2.0; bundled compatible notices), headers
├── simple-sentencepiece 0.7 (Apache-2.0), static
│   ├── vendored Darts-clone header, version macro 0.32 (BSD-2-Clause)
│   └── original ThreadPool header (Zlib); replaced/removed on Android
├── nlohmann/json 3.12.0 (MIT plus embedded attribution evidence), headers
└── csukuangfj/onnxruntime-libs Android 1.28.2 vendor binary archive
    └── libonnxruntime.so; upstream MIT plus ThirdPartyNotices
```

`sherpa/cmake` is first in `CMAKE_MODULE_PATH`; nested `include(eigen)` and `include(openfst)` therefore select sherpa's pins. The decoder's own Eigen 3.4.0 and kaldifst's own OpenFst 2026-04-10 modules are shadowed. The effective pins are 5.0.1 and 2026-07-09, respectively. This conclusion is from CMake source resolution, not an executed Android configure trace.

`dependency-inventory.json` contains each actual FetchContent name, upper-case override variable, exact URL, upstream SHA-256, independently computed SHA-256, byte size, archive filename/root and local extracted directory. The ORT ZIP has several top-level entries, so its `archive_root` is null and it must be extracted into its own destination directory.

## Required release review and packaging actions

- [ ] Include sherpa's Apache-2.0 license and the collected Apache licenses/notice prefaces for kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst and simple-sentencepiece. OpenFst's `COPYING` is a notice referring to Apache-2.0; pair it with the complete Apache license text.
- [ ] Include KISS FFT's `COPYING` and full `LICENSES/BSD-3-Clause`, including Mark Borgerding's copyright. The separate Unlicense file applies to an excluded test source, not the selected runtime library.
- [ ] Include the Darts BSD-2-Clause license extracted verbatim from the actual vendored header. Its notice is not supplied by simple-sentencepiece's top-level Apache license.
- [ ] Review Eigen's actual MPL-2.0 sections 3.1–3.3 and the covered-source/source-availability information to accompany the binary. Preserve the exact Eigen source and any modifications. Keep `COPYING.README`, `COPYING.BSD`, `COPYING.APACHE` and `COPYING.MINPACK` for relevant bundled portions. This checklist does not decide application-level legal obligations.
- [ ] Include nlohmann/json MIT text and embedded copyright attributions for Niels Lohmann, Björn Hoehrmann, Florian Loitsch and Evan Nemerson. Retain the recorded source evidence: Hedley's header says MIT while the repository's license documentation describes CC0-1.0. `cpp_future.hpp` has an MIT SPDX header and an Apache-2.0 attribution for a C++11 Abseil fallback; that fallback is not selected by the C++17 target. Resolve public notice wording without silently discarding the discrepant records.
- [ ] Include the collected Microsoft `LICENSE` and `ThirdPartyNotices.txt`, verbatim. Obtain/verify the vendor binary build provenance and precise third-party coverage before treating these as a complete runtime SBOM.
- [ ] Inspect final packaged `.so` files and any NDK `libc++_shared.so` or other runtime library; add the appropriate toolchain runtime license/notices for what is actually shipped.
- [ ] Review the actual model, tokenizer, vocabulary and other asset licenses independently. None are licensed by the native source-code inventory alone.
- [ ] Keep any source distribution's original copyright/license statements and record changes. A final compiled artifact and its notices still require verification; copying this checklist alone is not a completed public notice bundle.

## ONNX Runtime provenance limitation and packaging detail

The exact vendor archive is:

`https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip`

SHA-256: `01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d`.

Its 36 ZIP entries include **no license, NOTICE or COPYING file**. The nested `classes.jar`'s 69 entries also contain no such file. The official Microsoft notice files were separately retrieved at [the resolved upstream source commit](https://github.com/microsoft/onnxruntime/tree/33ca9628233dc8f002435e868d4c2e9f82766ca1). Their computed checksums authenticate the recorded copies; they are not vendor-provided notice-file digests and do not attest that the vendor binary used exactly that source/configuration. The broad Microsoft notice file contains optional components; its presence alone does not establish they are in this Android binary.

Upstream Android CMake installs `libonnxruntime*`, which also matches `libonnxruntime4j_jni.so`. For a sherpa-only JNI package, verify an explicit final-library whitelist rather than copying the vendor ZIP's Java JNI bridge and `classes.jar` accidentally. This audit does not establish which libraries the final AAR contains.

## Reproducibility and inactive downloads

No active source-archive input with a missing or non-SHA-256 CMake pin was found in the selected graph. No unconditional unpinned nested download was found in that graph. The following **excluded** code paths still exist in the downloaded sources:

| Excluded path | Input/risk | Guard to keep off |
| --- | --- | --- |
| Eigen LAPACK reference tests | HTTP `lapack_addons_3.4.1.tgz`, `EXPECTED_MD5=ab5742640617e3221a873aba44bbdc93`, direct `tar` extraction | `EIGEN_BUILD_LAPACK=OFF`, `EIGEN_ENABLE_LAPACK_TESTS=OFF` |
| JSON test data | `git clone --branch v3.1.0` of `nlohmann/json_test_data`, no archive digest | `JSON_BuildTests=OFF`; do not build standalone benchmarks |
| JSON benchmark | Google benchmark `GIT_TAG origin/main`, mutable branch | Do not build `tests/benchmarks` or enable JSON CI |
| OpenFst optional Abseil | `find_package(absl CONFIG REQUIRED)`, no selected source pin | `OPENFST_USE_ABSL=OFF` |
| Eigen BTL benchmark sources | GPL-2.0 sources in full archive, not selected runtime | `EIGEN_BUILD_BTL=OFF` |
| Python/unit-test dependencies | pybind11/googletest downloads | Parent and nested Python/tests OFF |

The JSON documentation also includes an independent Hunter integration example; it is documentation/example code and not called by the selected build graph. Vendored legacy FetchContent implementation files describe arbitrary Git/download operations but do not themselves introduce another selected dependency.

Explicit OFF flags are recorded in the machine inventory. In addition to normal top-level feature flags, keep `BUILD_TESTING`, `JSON_BuildTests`, `JSON_CI`, `EIGEN_BUILD_TESTING`, `EIGEN_BUILD_BLAS`, `EIGEN_BUILD_LAPACK`, `EIGEN_ENABLE_LAPACK_TESTS`, `EIGEN_BUILD_BTL`, `EIGEN_BUILD_SPBENCH`, `EIGEN_BUILD_DEMOS`, `EIGEN_BUILD_DOC`, `OPENFST_USE_ABSL`, `KISSFFT_TEST`, `KISSFFT_TOOLS`, `KISSFFT_PKGCONFIG` and `KISSFFT_OPENMP` OFF. Keep preinstalled ORT discovery OFF to prevent an ambient SDK from replacing the pin.

For offline use, verify every complete archive **before extraction**, provide all nine `FETCHCONTENT_SOURCE_DIR_<NAME>` overrides, and enable `FETCHCONTENT_FULLY_DISCONNECTED`. The source-directory overrides bypass FetchContent's URL_HASH download check, so the external verification step is essential. Maintain a pristine archive/source copy: sherpa's Android CMake intentionally overwrites `threadpool.h` and patches `ssentencepiece.h`/`.cc` in the simple-sentencepiece source directory. The original Zlib ThreadPool notice is retained separately as excluded-Android source evidence. The simple-sentencepiece v0.7 archive's own CMake variable still says `SBPE_VERSION 0.2`; do not use that stale variable as the input identity.

Some root dependency modules declare `hf-mirror.com` alternatives. Their literal URLs are recorded in the inventory, but **none was used or independently validated**. Downloads in this audit used the exact project/selected maintainer-fork archive URL and its expected SHA-256. Retain that origin policy; do not silently fall back to mirrors or unverified directories.

## Files

- `dependency-inventory.json`: pins, graph, exclusions, license paths and remaining release gates
- `license-file-manifest.json`: provenance and SHA-256 for all copied texts/excerpts
- `licenses/`: actual texts, not replacements or summaries; files marked `EXCLUDED-` or `BUILD-ONLY-` are deliberately separated evidence
- `download-ledger.json`: source-archive download/hash results
- `onnxruntime-notice-provenance.json`: official upstream ORT notice origins and resolved commit
- `cmake-download-scan.txt`: raw search evidence; includes inactive tests/examples and vendored CMake helpers
- `archives/`, `sources/`: verified original source archives and extracted source trees
- `finalize_inventory.py`: reproducible local manifest/notice-copy generator; performs no download or Android build

Before release, verify a real build's generated dependency/link information and final distributable contents against this inventory. A static source audit cannot establish binary inclusion/exclusion or complete license compliance.
