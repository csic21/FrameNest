# 0035: Controlled SenseVoice runtime upgrade

Status: experimental; not release acceptance. Base: stable 0.6.9 source ec9ff089.

Compare sherpa-onnx 1.12.32 and 1.13.8 with the same existing SenseVoice weights,
Japanese language hint, ITN, greedy decoding, two CPU threads, input PCM and windows.
No Hy enablement, new model, ML Kit change, version bump or stable-branch change.

The official 1.13.8 Android AAR is 50,129,134 bytes, SHA-256
633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96.
The Android-only JitPack child module is
com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8; its AAR is byte-identical to
https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar .
Its dependency-free POM SHA-256 is
755acf5ea7ce090b6b30768ed5dd95c52a5ebc7ba983fb3d944b1efaf574a263.
Do not use the old root coordinate at 1.13.8: it became an aggregator that pulls
unrelated JVM/desktop native modules. Strict verification remains enabled.

A fixed -PsherpaRuntimeBaseline=true selects the already verified old Android
coordinate and exposes the selected version in BuildConfig. Both complete APKs
must be rebuilt against their respective classes and native libraries. Replacing
only native binaries under old compiled Kotlin classes is not a valid comparison.
Runtime version participates in subtitle cache identity; installed model paths,
weight checksums and model version remain unchanged. No migration/deletion is needed.

The cloud Python control reproduced old text byte-for-byte and observed CER
44/436 (10.09%) vs 36/436 (8.26%) with the new package stack. This is not Android
or OPPO evidence, and no speed/quality improvement is promised. Native audit found
Android ORT 1.23.2 -> 1.28.2 and changed centered, edge-padded LFR feature framing;
therefore differences cannot be attributed to ORT alone. Timestamp semantics must
be evaluated on real output rather than assumed unchanged.

The core project/POM declares Apache-2.0. The prebuilt AAR may also statically
include espeak-ng/piper-phonemize dependencies with additional licensing duties;
upstream issue https://github.com/k2-fsa/sherpa-onnx/issues/3731 requires explicit
review before any new distribution. This is not a license clearance.

Gates: exact Android API/ABI/16KB audit; existing JVM/lint/build/instrumentation
compilation; same real PCM/model native A/B; full outputs, timestamps, model-load
and decode time; no VAD gap credited as completed coverage. The separate emulator
job stays disabled until a new single-run device-permission approval. Public
fixture content may appear only in opt-in test artifacts, never production logs.
