# Hy-MT2 native optimization audit (FN-70, 2026-10-15)

## Result and acceptance boundary

The same real JNI bridge, verified official model, CPU2 configuration and
portable x86 SSE4.2 baseline took **130.512 / 130.868 seconds at O0**, versus
**16.355 / 16.518 seconds at O3**, for the same authored complete sentence:

- Source: `日本語で質問してもいいですか。`
- Every target: `可以用日语提问吗？`
- Mean elapsed ratio: **7.95×**. Two consecutive translations per resident model,
  run sequentially O0 then O3 on the same Linux cloud host. This is a targeted
  reproduction, not a statistically controlled or physical-phone benchmark.

This establishes that unoptimized portable kernels can reproduce minute-scale
inference and that compilation optimization materially improves this path.
It does **not** establish the precise flags of the run21 APK, attribute all of
its delay to O0, or show optimized Android latency is acceptable. Even this
optimized portable x86 host still takes about 16 seconds. The previous official
cloud binary's 1–3 second timings used a different binary/ISA environment and
cannot be transferred to Android or this portable build.

No emulator was run for this audit. Android/phone performance, memory, thermal,
NAS and complete-video semantic acceptance remain open. Run21 remains failed.

## Verified build cause and correction

1. The pinned llama.cpp only chooses `Release` when `CMAKE_BUILD_TYPE` is empty.
   It does not upgrade AGP's explicit `Debug` setting.
2. Official AGP **9.2.1** source `CreateCxxVariantModel.kt:69–100` maps the `debug`
   variant to CMake `Debug`, and `release` to **`RelWithDebInfo`**, not `Release`.
3. NDK **r28c** supplies debug symbols but no Debug optimization. Its explicit
   CMake Release flags include `-O3 -DNDEBUG`. CMake's RelWithDebInfo uses `-O2`.
   The old host configurations were independently generated and audited:
   Debug O0, RelWithDebInfo O2, Release O3. Run21 did not archive its actual
   compiler commands, so its O0 classification is source/configuration inference.
4. SSE4.2 was already enabled. Disabling AVX did not disable all SIMD. However,
   the official x86 `ggml_vec_dot_q4_K_q8_K` falls back to its generic kernel
   without AVX/AVX2, making compiler optimization especially relevant here.
5. `HY_MT_OPTIMIZE=ON` now appends `-O3` to **all five** inference targets:
   ggml-base, ggml-cpu, ggml, llama, and the JNI wrapper. Debug symbols,
   assertions, sanitizer options and Java/Kotlin debuggability remain intact.
   `HY_MT_OPTIMIZE=OFF` remains available for source debugging/diagnosis.

The CMake correction exports `compile_commands.json`. The auditor requires the
last effective optimization flag to be O3 for every inference target; Debug
also requires symbols and retained assertions. This is a build gate rather than
a claim that an unexamined APK is optimized. AGP's standard output roots are
`app/.cxx/Debug` and `app/.cxx/RelWithDebInfo`; explicit CMake Release builds may
use `app/.cxx/Release`.

No host-native AVX/AMX or newer ARM instructions were enabled. Android's baseline
is retained; native cancellation, source/model pins, prompt, sampling, context,
output bounds, app defaults and Android acceptance timeouts are unchanged.

## Host evidence and reproduction

Machine-readable evidence: [host-results.json](host-results.json).

- CMake 3.22.1, GNU C/C++ 14.2.0, Linux x86_64, AMD EPYC 9V74 cloud CPU.
- Same pinned runtime/model as production; model length and SHA-256 checked.
- Two decoder threads and two batch threads; context 1024, batch 64, output 256.
- The original and optimized **Debug** builds both retain `-g` and assertions.
  All 70 normalized compilation commands differ only by the added `-O3`.
  The JNI source file itself is identical between the two builds.
- Separate model initialization: 914 ms O0, 220 ms O3. These are fresh engine
  initialization measurements after SHA verification warmed filesystem caches,
  not cold-disk or cold-device measurements; excluded from inference timings.
- Each call clears the KV context. Same sampled target after resident reuse.
- Seven build-auditor tests and seven exact official-template parity cases pass.
- Optimized real-model JNI smoke passes: cancellation/join **17 ms** on this
  host, no late publication, unchanged context-reuse output, oversized-context
  rejection/recovery, close during generation and idempotent close. This is not
  an Android cancellation measurement.
- Actual configuration checks cover Debug O0, optimized Debug O3, original
  RelWithDebInfo O2, original Release O3, optimized Debug preserving address
  sanitizer flags, and explicit optimization-off Debug O0. Sanitizer execution
  was not performed; that check proves flag preservation only.

For a local reproduction, obtain the already pinned source/model and use the
JNI include paths described in `app/src/main/cpp/README.md`. Configure two clean
host build directories with `CMAKE_BUILD_TYPE=Debug`, `CMAKE_C_FLAGS_DEBUG=-g -O0`
and `CMAKE_CXX_FLAGS_DEBUG=-g -O0`, then vary only `HY_MT_OPTIMIZE=OFF/ON`.
Build both before measuring to avoid compilation contention. For each:

```
python3 app/src/main/cpp/tests/check_build_flags.py BUILD/compile_commands.json --debug --optimization=-O0 # OFF
python3 app/src/main/cpp/tests/check_build_flags.py BUILD/compile_commands.json --debug                   # ON
scripts/experimental/run-hy-native-host-probe.sh BUILD MODEL KOTLIN_JARS perf
scripts/experimental/run-hy-native-host-probe.sh OPTIMIZED_BUILD MODEL KOTLIN_JARS smoke
```

The helper uses actual production Kotlin/JNI, a host-only AndroidX annotation
stub, and local Kotlin/coroutines jars. It does not download files, start a
listener or Android emulator, or modify permissions. The longer probe bound is
host-diagnostic only; Android's 180-second control and 30-minute producer limits
have not been increased.

## Run21 cancellation interpretation

The authored-control test timed out after 180,000 ms before any control row or
dedicated cancellation/reuse/close probe completed. The separate recorded test
then completed MT calls of 268,871 / 345,764 / 771,303 ms before its independent
1,800,000 ms overall timeout. Those are inference durations, not cancellation
latencies. Both tests exited without a recorded native crash.

Report starts differ by 331,414 ms. Subtract verification 2 ms, installation
148,526 ms, load 1,535 ms and the control timeout 180,000 ms: only 1,351 ms remain
for other work, timeout overshoot, cleanup and test transition. This inferred
envelope assumes stable clocks and contradicts a minutes-long cancellation
hang, but is not a direct cancellation measurement or safety acceptance.

Native review found cooperative abort polling between graph nodes and checks
around every prefill batch/generated token, plus serial ownership and deferred
close. This has no fixed per-node latency guarantee. Android cancellation/reuse
acceptance remains unverified because run21 never reached its dedicated probe.

## Primary build sources

- [Pinned llama.cpp](https://github.com/ggml-org/llama.cpp/tree/d81235049384534c167caea52b85a694f6103d14),
  top-level CMake and `ggml/src/ggml-cpu/arch/x86/quants.c`.
- [Official AGP 9.2.1 source artifact](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.2.1/gradle-9.2.1-sources.jar),
  SHA-256 `2b6fbf6fa80642759fa0ac32ccc6bfae2aa1d9ce27051162635dcae36eb5adcc`.
- [NDK r28c legacy toolchain](https://android.googlesource.com/platform/ndk/+/refs/tags/ndk-r28c/build/cmake/android-legacy.toolchain.cmake),
  lines 460–467 and 522–527 (generic/debug/release flags).
- [Android ABI baseline](https://developer.android.com/ndk/guides/abis#x86-64)
  and [target-scoped CMake flag guidance](https://developer.android.com/ndk/guides/cmake#manage-compiler-flags).
