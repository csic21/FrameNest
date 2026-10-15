# FN-76: three-column caption diagnosis

This is a test harness, not a production accuracy fix or a semantic release pass.
SenseVoice, source AAR, ML Kit, three-second windows, token mapping, silence policy,
cache behavior and player behavior are unchanged. Hy-MT remains disabled.

## Held-out public reference

- Video: TCappelletto (WMF), *MCDC Onboarding "Ask me Anything" session - Japanese
  language* (2022), [Commons source](https://commons.wikimedia.org/wiki/File:MCDC_Onboarding_%22Ask_me_Anything%22_session_-_Japanese_language.webm),
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
- The original 78,139,308-byte WebM SHA-256 is
  `fd5f4acad07dce6c67a0e200e79b535300452b3fdb5c1f6929ddd2d3d29ee90a`.
  Its locally computed SHA-1 matches the publisher's file metadata.
- Excerpt: original-media **113.460–161.180 seconds**, duration **47.720 seconds**.
  Original Opus is decoded directly to 16 kHz mono signed PCM16: 763,520 samples,
  1,527,040 bytes, SHA-256
  `be9bfbefe12f1ac32694e4c20667dc1428bdaa5f96a8124bd723de5f7fbf4fb9`.
  No intermediate AAC or additional lossy compression enters the diagnostic PCM.
- [Japanese timed text revision 711665459](https://commons.wikimedia.org/w/index.php?title=TimedText:MCDC_Onboarding_%22Ask_me_Anything%22_session_-_Japanese_language.webm.ja.srt&oldid=711665459)
  was created by CKibelka (WMF). Syunsyunminmin's
  [revision 711665194](https://commons.wikimedia.org/w/index.php?title=TimedText:MCDC_Onboarding_%22Ask_me_Anything%22_session_-_Japanese_language.webm.ja.srt&diff=711665194&oldid=703925007)
  explicitly corrects obvious mistakes. The caption source and adaptations retain
  CC BY-SA 4.0. Original subtitle wording is retained; layout newlines are removed
  when combining whole published cues into four fixed semantic spans.

**This is human-corrected published timed text, not independently audited verbatim
gold.** The initial caption-generation method is undocumented. The excerpt has not
been independently listened to. Subtitle timing is not forced word alignment, and
the four whole-cue groups were selected manually by the assistant before viewing
new ASR output. Unit 3 contains multiple related statements over 20.140 seconds.
Do not silently reinterpret subtitle differences as measured ASR errors or claim a
definitive CER/WER. `fixture-provenance.json` preserves the research evidence and
limitations; `source.ja.srt` preserves the published source text.

## What each column actually does

1. `reference_to_mt`: each fixed published reference span is translated by real ML
   Kit Japanese→Chinese. This isolates MT behavior on the provided reference text.
2. `raw_span_asr_to_mt`: native SenseVoice uses the exact same span's PCM, no padding
   or token filtering, followed by the same real ML Kit instance. Raw text, native
   tokens, relative/clip/original-media token onset estimates and native duration
   arrays are retained. This column changes context boundaries intentionally.
3. `production_3s_to_mt`: the actual `RealListenTranslateEngine`, actual
   `SherpaAsrEngine` and actual ML Kit process the clip-local default three-second
   grid, with existing ±750 ms ASR context and existing final-window clipping. An
   `AsrEngine` delegate observes the actual production recognition call and returns
   it unchanged. It records full recognition text and **mapped** timed tokens;
   mapped ends are next-token estimates, not measured token durations. There is no
   second recognition standing in for the production call.

The sixteen production windows retain reference-overlap IDs. They are not cut or
retranslated to imitate the four reference spans. Original media offsets are
reported separately; the clip-local grid differs from the full video's grid.
Input PCM hashes, actual read bounds, source callback time, output cue bounds and
production stage times are retained for diagnosis.

ML Kit is prepared once before the columns, which run in fixed reference/raw/
production order. Each call's real time is recorded, but this is not a controlled
cold/warm or speed comparison. The observer's hashing overhead is included in the
production ASR time. PCM-copy time is not codec/decode time. No MediaCodec, SMB,
Room, session playback or physical caption rendering is measured.

`status: collected` and `collection_complete: true` mean all requested diagnostic
rows were collected. Individual MT failures/empty results remain visible. Every
semantic-acceptance field stays null; there is no keyword, non-echo, Chinese-script,
or equality assertion pretending to be a quality review.

## Reproduction contract

Static assets are under `app/src/androidTest/assets/caption-diagnosis/`. The fixed
`manifest.json` pins the original media, PCM, reference JSON and the existing
released SenseVoice pack. `source-profile.json` pins the exact released AAR identity
and installed native/provenance APK members. No source/model hashes are refreshed.

Prepare the two existing model files in a model directory. CI can reuse
`download_verified` from `prepare-sensevoice-runtime-comparison.py` with that
script's fixed manifest `models` entries; it must not prepare/package the old
comparison corpus as well. The new generated directory contains only one model
pack, one PCM and one reference file, plus preparation metadata.

```sh
# Explicit download of the fixed public original, followed by strict PCM reproduction.
python3 scripts/prepare-caption-diagnosis.py --download \
  --input-dir /tmp/fn76-inputs --model-dir /tmp/fn76-models

# Or reuse already verified files; this command never fetches anything.
python3 scripts/prepare-caption-diagnosis.py \
  --input-dir /path/to/fixed-inputs --model-dir /path/to/sensevoice

python3 -m unittest discover -s scripts/tests -p 'test_caption_diagnosis.py' -v
```

The download mode verifies the complete original before running the exact seek,
duration and PCM parameters. Different PCM bytes fail closed, including a decoder
version difference; there is no resampling tolerance or automatic repinning.
WAV is optional review material: its format, sample count and PCM bytes must match,
but a different metadata/header hash alone is not a failure. Preparation refuses
to overwrite an existing generated directory. Inspect and remove only that
explicit test-generated directory before intentionally restaging.

Ordinary instrumentation discovers but skips `CaptionDiagnosisTest` before runtime,
asset, model or network access. The small `CaptionDiagnosisFixtureTest` parser tests
are safe without generated files. The real test requires an existing authorized
device/emulator and retained, verified source-profile APKs:

```sh
adb shell am instrument -w \
  -e class com.framenest.feature.listen_translate.CaptionDiagnosisTest \
  -e captionDiagnosis true -e captionDiagnosisNetwork true \
  -e expectedAppApkSha256 APP_SHA256 -e expectedTestApkSha256 TEST_SHA256 \
  com.framenest.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.framenest cat files/caption-diagnosis.json > caption-diagnosis.json
```

The test fails before inference unless `SHERPA_ASR_SOURCE=true`, `HY_MT_NATIVE=false`,
the exact reviewed AAR hash/runtime identity matches, JNI reports 1.13.8, the installed
APK hashes match, and native/provenance members match the source pinset. The network
flag authorizes this test's ML Kit model preparation/downloads only. No emulator,
KVM, permission or network setting is changed by preparation or this harness.

## Verified in this implementation turn

- 12 Python schema/preparation tests pass, including false-gold rejection, unchanged
  published wording, range/type/path/hash gates, no default networking, oversize
  downloads, corrupt existing media, wrong decoded PCM, atomic staging, and WAV metadata.
- Exact local fixed inputs/model pack staged successfully. Real FFmpeg reproduction
  from the complete original produced the exact locked 1,527,040-byte PCM again.
- Kotlin syntax/API compilation passed against the actual 1.13.8 source AAR
  `classes.jar`, actual production RealEngine/Sherpa/pure helper source, and small
  Android/ML Kit/JSON stubs. The disabled-opt-in smoke passed before JNI or asset
  access; JUnit entrypoint returns JVM void. This is not an Android build or runtime
  test, and stubs were not used to generate model or translation results.
- No actual Android inference/MT run, new KVM authorization, phone/tablet rendering,
  NAS test, independent listening, or semantic acceptance was performed here.
  The integration owner is responsible for real Android compile/CI/device execution.
