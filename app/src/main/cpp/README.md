# Optional Hy-MT2 JNI backend (FN-70)

This backend is **off in ordinary builds**. `-PhyMtNative=true` enables native
packaging and `BuildConfig.HY_MT_NATIVE`. It does not select a model, install a
model, change the default translator, or remove ML Kit. The composition layer
must require an explicit model choice and preserve its failure fallback.

## Reproducible build

- llama.cpp official commit: `d81235049384534c167caea52b85a694f6103d14`
  (v0.6.0/b11429), MIT.
- Official source: https://github.com/ggml-org/llama.cpp/tree/d81235049384534c167caea52b85a694f6103d14
- Source archive: https://codeload.github.com/ggml-org/llama.cpp/tar.gz/d81235049384534c167caea52b85a694f6103d14
- Archive SHA-256: `6b58785f0a82898f4c3442417ff962e2d4b231b1bee5d033902ad90b27901e14`.
  CMake checks this before extracting/building; corruption stops configuration.
- Android NDK r28c `28.2.13676358`; CMake `3.22.1`; min API 26.
- Supported APK ABIs remain arm64-v8a and x86_64. CPU-only static llama/ggml is
  linked into one JNI library; static C++ avoids conflicting STL packaging.
- No CLI, server, HTTP, TLS, RPC, external kernels, GPU runtime, subprocesses or
  model-download code is linked. No listener or API key is used.
- ARM uses the Android toolchain's portable baseline. x86 uses SSE4.2 without
  host-specific AVX/BMI2 instructions. Android linking requests 16 KiB pages.
- The MIT notice ships as `assets/listen_models/llama.cpp-LICENSE.txt`.

On a CI runner whose stable Android SDK terms are already accepted:

```
sdkmanager --install "ndk;28.2.13676358" "cmake;3.22.1" < /dev/null
test -f "$ANDROID_HOME/ndk/28.2.13676358/source.properties"
test -x "$ANDROID_HOME/cmake/3.22.1/bin/cmake"
./gradlew -PhyMtNative=true assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Do not auto-accept additional SDK agreements. Missing tooling is a build blocker.

## Model/lifecycle contract

`HyMtEngine(verifiedModelPath: suspend () -> String, threadCount: Int = 2)`
accepts an installer-verified **absolute app-private GGUF path**. The installer
owns full SHA-256/length verification and atomic installation; this engine never
fetches or trusts an arbitrary user-selected GGUF. Use its
`HyMtModelInstaller.verifiedModelFile()` result, or the verified result of an
explicit authorized installation. Changing the model requires a new engine.

The pinned model is Tencent `Hy-MT2-1.8B-GGUF`, Q4_K_M, revision
`a0c709d9fac510f2c807aa3af52872340dc37a4a`, 1,133,080,448 bytes, SHA-256
`dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`, Apache-2.0.
Only Japanese → Simplified Chinese is enabled by this adapter. Use the model
installer's version plus `HyMtEngine.RUNTIME_VERSION` in cache identity.

A mutex serializes native loading and inference. Close signals the active call
without freeing memory under it; its finally block releases the context/model.
Coroutine Job cancellation and close are polled during model load, CPU decode,
and every generated token. Kotlin checks cancellation again before returning.
The caller must also maintain its existing seek/session generation publication
guard. An idle context is reused; each call resets and clears KV state before
and after inference. The process-wide backend is initialized once, never freed
under another engine.

Limits: 2,048 UTF-16 source chars, 8,192 UTF-8 source bytes, 1,024 context tokens,
768 prompt tokens, 256 sampled output tokens and 16,384 output bytes. Oversized
input is rejected, not truncated. Output without EOG at the cap is rejected.
Native and Kotlin strings cross JNI as UTF-8 byte arrays to preserve emoji.
Neither captions nor model paths are written to logs.

## Exact prompt and tokenizer

The user message is the official tested instruction:

```
将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：
{source}
```

The GGUF chat template must exactly match `tests/official-template.json`.
`hy_mt_prompt.h` renders its single-user, no-system, generation-prompt branch.
This small specialized renderer is intentional: the pinned runtime's legacy
`llama_chat_apply_template` detector classifies this template as Hunyuan-VL and
emits different role order. There is no heuristic fallback. The native renderer
is checked against Jinja on the official fixture. It renders BOS once;
`llama_tokenize(add_special=false, parse_special=true)` resolves the real token
IDs. Source text containing the Hy special-token delimiter is rejected.

Sampling matches the effective tested CLI chain: repeat penalty 1.05 over 64
accepted tokens, top-k 20, top-p 0.6, min-p 0.05, temperature 0.7, seed 42.
EOG is decided by `llama_vocab_is_eog`; no stop token ID or decoded-text marker
is guessed. The pinned runtime itself recognizes the model's EOS despite its
metadata warning. This does not use the rejected fragment-guard prompt.

## Verification and limits (2026-10-14)

Passed locally:

- Eight JVM lifecycle/bounds tests (Kotlin 2.1.20/coroutines 1.8.0 test harness,
  not the complete Android dependency graph).
- `python app/src/main/cpp/tests/check_template.py`: seven exact official Jinja
  template parity cases; missing/changed template metadata rejected. Requires
  Jinja2 3.1.6 and g++ for this host check.
- CMake 3.22.1 builds pinned official source and the JNI bridge on Linux x86_64.
- A corrupted cached source archive fails CMake configuration as intended.
- Real model JNI smoke: load, Japanese→Chinese translation, active coroutine
  cancellation, unchanged output after context reuse, rejected context overflow,
  recovery after overflow, close during real native generation, and idempotent close.

Host smoke source is `tests/NativeSmoke.kt`; compile alongside `ListenMtEngine`,
`HyMtEngine`, `HyMtPrompt`, and `HyMtNative` with Kotlin/coroutines/AndroidX Keep
on the classpath, then run with the JNI library directory in `java.library.path`
and the already verified model path as its sole argument. Host JNI headers can
be supplied to CMake through `HY_MT_JNI_INCLUDE` and
`HY_MT_JNI_PLATFORM_INCLUDE`; those options do not override the source pin.

The local full Gradle attempt could not bootstrap its uncached 9.6.1 wrapper
(`Network is unreachable`) and no Android SDK/NDK is installed. Android compile,
R8 packaging, 16 KiB ELF verification, emulator/phone lifecycle, sustained
latency/memory/thermal testing and real NAS accuracy remain required. The host
smoke is functional evidence, not phone latency evidence or release approval.
Incomplete ASR fragments remain unsafe to translate, regardless of this model;
use finalized caption units and retain the release acceptance gate.
