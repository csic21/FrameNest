# FN-74: explicit source-ASR app profile

This independent branch is based on the reviewed source recipe. Stable app
builds retain the existing `1.12.32` Maven dependency and cache identity. PR #6's
stock `1.13.8` comparison branch is not modified. No version bump or release is
authorized by this profile.

## Exact input and identity

`artifact-lock.json` pins the successful source-AAR workflow run, head commit,
artifact ID/name/size, outer ZIP SHA-256, and AAR SHA-256. The candidate is
`sherpa-onnx-1.13.8-asr-no-tts.aar`, 59,632,871 bytes, SHA-256
`6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`.

The artifact expires on **2026-10-29**. A missing/expired/mismatching artifact
blocks the build. Do not substitute another run or automatically approve a new
hash; rerun the separately reviewed recipe and review any changed output first.
The binary itself is ignored, not committed or published to a package registry.

The three generated fields intentionally mean different things:

- `SHERPA_RUNTIME_VERSION`: `1.13.8`, for comparison with actual JNI
  `VersionInfo.version` after removing an optional leading `v`.
- `SHERPA_RUNTIME_IDENTITY`: `1.13.8-asr-no-tts-sha256-<full AAR SHA>`, for
  experiment/cache identity. Never assert native `VersionInfo` equals this label.
- `SHERPA_AAR_SHA256`: the exact pinned AAR hash (empty for ordinary stock builds).

`SHERPA_ASR_SOURCE` is true only for this explicit profile. The cache model label
adds the full source identity, distinct from both stable and stock `1.13.8`.
SenseVoice model bytes, downloads, model directory, language/ITN/CPU configuration,
recognizer adapter, Vosk and all MT code/dependencies are unchanged. The debug
application ID is also unchanged: a later installation may replace another debug
build; this workflow performs no installation.

## Build

For a previously downloaded exact artifact ZIP:

```sh
python3 scripts/prepare_source_asr.py --archive /path/to/reviewed-artifact.zip \
  --report /path/to/source-aar-import.json
./gradlew -PsherpaAsrSource=true --dependency-verification strict \
  assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

On same-repository CI, `prepare_source_asr.py --download` uses the existing
short-lived Actions read token only against the official GitHub API. The signed
storage download uses a separate unauthenticated request. Run identity, successful
status, artifact metadata, ZIP full inventory, all member sizes/hashes and the AAR
hash are checked before staging the one AAR. Existing staged files are not
overwritten. No raw ZIP extraction is used. Gradle checks the AAR independently at
configuration and execution because local file dependencies bypass Maven
verification metadata. There is no stock-runtime fallback or arbitrary path/hash
override. Source-profile release packaging is explicitly refused.

The `.github/workflows/asr-source-app.yml` job runs only ordinary hosted build
tools. It does not run an emulator, change KVM/security settings, accept SDK
licenses, install/sign/release an app, prepare large model/PCM fixtures, or run
instrumentation. Existing SDK acceptance must already be present.

## Static APK audit and retained output

The exact stripped JNI and unchanged ORT bytes are preserved with narrowly scoped
AGP `keepDebugSymbols` patterns. The checker compares each final APK's supported
ABI members to the pinned AAR, verifies every attribution/provenance asset,
parses actual DEX class definitions to compare the retained Kotlin class set,
and records APK SHA-256 and ELF/ZIP alignment. CI separately executes official
`zipalign -c -P 16 -v 4` on each app APK.

The AAR contains 48 classes from eight official Kotlin files, with 32 native
methods. JNI still exports 117 JNI symbols per ABI: additional VAD/keyword/etc.
entry points remain compiled but have no matching packaged Kotlin wrappers.
No TTS/GeneratedAudio/diarization Kotlin classes are packaged; neither stock
native feature completeness nor complete native dead-code elimination is claimed.

Successful CI retains the exact universal/arm64/x86_64 debug APKs and compiled
instrumentation APK for a later same-binary device test. Evidence is retained
even on failure. Artifacts have a 14-day retention window. Download and verify
the manifest before later testing; rebuilding is not proof of identical APKs.

## Validation boundary

At initial implementation: local exact artifact import and six importer tests
pass. Android app assemble/unit/lint/instrumentation compilation are pending the
new CI; there is no local Android SDK. The separate APK checker has its own tests.

Compilation and static checks do not establish native execution, real 4KB/16KB
device compatibility, performance, accuracy/ITN, timestamps, cancellation, long
media or bilingual playback. The retained vendor ORT arm64 unrounded RELRO-end
warning remains unresolved; it is not demonstrated runtime failure. Full app/model
license review, exact ORT vendor build/transitive SBOM and reproducibility gates
also remain open. Stock PR #6 ABBA measurements cannot be attributed to this
different source-built binary. The exact stock fixture/PCM/parameters may be
reused in a separately authorized later harness without activating its workflow.
