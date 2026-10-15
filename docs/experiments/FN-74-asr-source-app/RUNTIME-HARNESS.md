# Source-AAR runtime harness preparation

Status: prepared, **no source-AAR Android recognition result yet**. Stock run8
results do not establish the source binary's accuracy or performance. This change
does not add or enable a device job.

## Frozen inputs and checks

The real raw-native / production-`SherpaAsrEngine` harness, input manifest,
preparation script and compiled-JUnit-signature checker are imported from stock
commit `de5377661893b74e1f4c3d42188d87ddaae78bec`. The input manifest is unchanged:
`9231712ec85ec7a16b31000f3cb960f2a15b7e3ccc28758515452a2351f66f77`.

It retains the same public licensed media, SenseVoice model/tokens, exact mono
16 kHz float32 PCM and 33 fixed VAD windows. There are three sequential passes per
backend (198 main result rows), then the same cancellation/reuse/close probes.
Preparation verifies exact source, PCM16, float32, model and window bytes. WAV
metadata-only differences are explicitly recorded and accepted only after exact
PCM verification. Gaps remain excluded, not completed coverage. Production's
short/32768f input is bit-identical to the frozen float input; no quantization is
silently introduced.

`source-profile.json` separately pins the reviewed AAR
`6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`,
all four JNI/ORT members, and three embedded build/provenance assets. Opt-in
source replay asserts:

1. Actual JNI `VersionInfo.version` is upstream `1.13.8`.
2. BuildConfig source mode, AAR SHA and SHA-based identity match the pinned source
   profile, and `HY_MT_NATIVE` is false. The upstream version is not used as proof
   of AAR identity.
3. Installed app and test APK hashes match the retained, verified pair supplied
   by the host. Whole-AAR SHA is input/build provenance; installed native and
   provenance member bytes are checked independently.
4. The installed app APK includes exact JNI/ORT bytes for the active ABI and any
   other included supported ABI. Use a retained standalone ABI or universal APK,
   not base/config split installation.

Without `runtimeComparison=true`, the test skips before JNI/assets/source
assertions. Stable default builds are not forced into source mode. A source APK
requires the additional explicit `sourceRuntimeComparison=true` flag.

## Integration into the next ordinary CI build

The packaging owner retains ownership of Gradle, CI and APK auditing. After the
final source-profile UI/notice changes are integrated, prepare the frozen assets
**before** compiling the source instrumentation APK:

```sh
python3 scripts/prepare-sensevoice-runtime-comparison.py \
  --source-dir "$RUNNER_TEMP/runtime-inputs" --download \
  --report "$EVIDENCE_DIR/runtime-preparation.json"
FN74_SOURCE_AAR=app/libs/sherpa-onnx-1.13.8-asr-no-tts.aar \
  python3 -m unittest discover -s scripts/tests -v
# Use the existing source profile's Gradle invocation to build app + androidTest.
python3 scripts/check_android_test_signatures.py app/build --path-contains androidtest
# Run the existing check_source_asr_apk.py against the resulting app variants.
```

The signature checker reads real compiled classes and fails on a non-public,
non-void, static or argument-taking `@Test`; the core harness must also have a
public no-argument constructor. Its method explicitly returns Kotlin `Unit`.
Normal parameterized/inherited test classes are not incorrectly required to have
the core harness's constructor shape.

Retain the final app APK(s), instrumentation APK, preparation/signature/static
audit reports and a host SHA manifest from **that same CI output**. Record commit,
CI run, source AAR SHA, fixture manifest SHA, each APK's filename/size/SHA and
variant. Final supplementary notices/UI will change app APK hashes, so the first
source-profile CI output without this harness is not the final replay pair.

Any future separately authorized device job must consume these retained files,
validate their host hashes/audit evidence, and install them without running
Gradle or rebuilding. After choosing the verified pair, instrumentation arguments
are:

```text
-e class com.framenest.feature.listen_translate.SenseVoiceRuntimeComparisonTest
-e runtimeComparison true
-e sourceRuntimeComparison true
-e expectedRuntime 1.13.8
-e expectedAppApkSha256 <retained app SHA-256>
-e expectedTestApkSha256 <retained instrumentation SHA-256>
```

Pull `files/sensevoice-runtime-comparison.json` from the target app. It records
both APK hashes, source identity/member verification, full raw text/tokens/times,
production output and lifecycle counters. A failed preflight is an instrumentation
failure, not a recognition result; preserve its log. A timeout/partial report is
not a pass. Source runtime duration is unmeasured; stock timings are not a source
timeout guarantee. Accuracy, phone thermal/performance, MT and 16 KiB device
acceptance remain separate.

## Local validation (2026-10-15)

- All 105 Python tests pass with `FN74_SOURCE_AAR` pointing at the exact retained
  AAR, including five new profile/corpus/native-provenance checks.
- Real existing public fixtures prepare successfully: exact frozen model, PCM
  and all 33 window hashes; generated assets remain ignored.
- The harness and real production adapter compile against both the exact source
  AAR's `classes.jar` and stable 1.12.32, and both compiled JUnit signature checks
  pass. A host invocation without opt-in skips before JNI/assets for both builds.
  Android, JSON and installer APIs use compile-only host stubs here; this is not a full
  Android build or native execution. Ordinary CI still must compile the final
  Android pair, and the source AAR still needs independent device evidence.
