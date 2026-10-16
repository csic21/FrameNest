# FN-76: bounded fixed 3-second versus 6-second diagnosis

This is a second, opt-in Android diagnostic. It does not change the product's
three-second default, the existing ±750 ms context, token midpoint selection,
silence policy, model, ML Kit, AAR, or player behavior. No window sweep is added.
The previous three-column diagnosis remains available and unchanged.

## Inputs and boundaries

Both conditions consume the identical complete pinned PCM for each fixture:

| Fixture | Duration | 3-second windows / last range | 6-second windows / last range |
| --- | ---: | --- | --- |
| Existing MCDC excerpt | 47,720 ms | 16 / 45,000–47,720 ms | 8 / 42,000–47,720 ms |
| Full legacy Emoji | 113,960 ms | 38 / 111,000–113,960 ms | 19 / 108,000–113,960 ms |

MCDC retains its published human-corrected reference, original 113,460 ms offset,
and four reference span IDs. This reference is not independently listened-to
verbatim gold. See the adjacent README and provenance file.

The additional regression is the whole [Emoji](https://commons.wikimedia.org/wiki/File:Emoji.webm)
video by Simpleshow Japan, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
Its existing `runtime-comparison/manifest.json` pins are reused unchanged:

- Original WebM: 9,487,128 bytes,
  SHA-256 `ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956`
- Full mono 16 kHz PCM16: 1,823,360 samples, 3,646,720 bytes,
  SHA-256 `e3e4e1de9395948bc3e03c0f88a93cb55bbbb0768280344bd7f4bd3f164700a6`

There is no independent Emoji reference transcript or semantic acceptance. The
old VAD ranges are not reused: they omit gaps and do not establish whole-file
coverage. Source/target text derived in this test retains the fixture attribution
and license and can contain errors. No audio, window, token, or output row is
removed to improve a score. Existing production midpoint selection still decides
which recognized context tokens form each source cue; all returned mapped tokens,
including those not selected, are preserved for review.

## What is timed and retained

One actual `SherpaAsrEngine` and one actual `MlKitMtEngine` are prepared before
condition timing and shared until final cleanup. No new model is downloaded.
Each fixture/condition gets a fresh `RealListenTranslateEngine` and non-owning
observers, preventing retry-source cache reuse across conditions. Each eligible
window performs one ASR and at most one MT call; existing digital-zero silence
and blank recognition rules can skip inference/translation. Every actual call's
input/output is retained, including empty targets and failures; there are no
translation retries.

Fixed order is MCDC 3s, MCDC 6s, Emoji 3s, Emoji 6s. Shared preparation time is
reported separately. `first_window_wall_ms`, `first_source_ready_ms`, and
`first_translation_ready_ms` are condition-relative diagnostic times, not cold
startup or display latency. Per-window wall time, source callback time, stage
times, actual copied PCM bounds/sample count/hash, full ASR text, mapped tokens,
actual MT input/output, source/target, cue bounds, and blank/error status are saved.
The model observer does not call ASR a second time. Mapped token ends remain
next-token estimates, not native measured token durations.

All PCM is available ahead, analogous only to having a readable recording. There
is no paced 3s/6s sleep. A six-second processing chunk is not evidence of six
seconds of necessary viewer waiting. This does not benchmark NAS transfer,
MediaCodec decoding, Room, playback scheduling, display, target-phone latency,
or a controlled cold/warm speedup. Fixed condition order remains a confounder.

The output is `files/fixed-caption-window-comparison.json`, schema 1:

- Root identities pin installed app/test APKs, source AAR/native members, static
  manifests, prepared files, runtime/device, shared preparation, and limitations.
- `fixtures[]` retains duration, full PCM identity, reference status, attribution,
  and exactly two `conditions[]` (`window_ms` 3000 and 6000).
- Each condition includes every ordered `rows[]`, `expected_window_count`,
  contiguous core `coverage`, and `final_eof`. Read-ahead context overlap is
  recorded in PCM reads and is not counted as duplicated core coverage.
- `final_eof` means the exact pinned PCM array was fully traversed and the final
  contextual read clipped at its known end. It is not a real extractor EOS event.
- Reports atomically checkpoint a fresh validation marker, running row, actual-call
  and source-ready updates, and every completed/failed window. `collection_complete` only
  means all 81 requested window rows were processed. It is not word completeness,
  accuracy, MT success, or a release pass. Semantic acceptance remains null.

The coroutine has a 20-minute total timeout. Native/platform calls are cooperative
with cancellation, so a hung external/native call may still require the owning
runner's independent wall-clock watchdog; it should collect the incremental report
and stop the app/test packages even on timeout. Ordinary instrumentation skips before JNI, generated
assets, models, or network unless both explicit opt-ins are supplied.

## Preparation and authorized execution

First use the existing `prepare-caption-diagnosis.py` command. Then add only the
full Emoji PCM and `comparison-prepared.json` to that generated directory:

```sh
python3 scripts/prepare-fixed-caption-window-comparison.py \
  --input-dir /path/to/existing/emoji-fixtures
# Or explicitly fetch only the pinned original public Emoji if absent:
python3 scripts/prepare-fixed-caption-window-comparison.py --download \
  --input-dir /tmp/caption-emoji-inputs
python3 -m unittest discover -s scripts/tests -p 'test*caption*.py' -v
```

The new script revalidates existing MCDC/reference/model/profile pins without
copying another model or preparing the FN-74 corpus. Existing WAV sample bytes
are extracted directly if present; otherwise full original-media FFmpeg decode
is used, without seek, duration clipping, normalization, or automatic repinning.
Every derived PCM byte must match the old pinned hash. Existing corrupt media,
PCM, or generated comparison outputs fail closed instead of silent replacement.
Only `--download` can fetch the exact public original.

For an already-authorized retained source-profile APK pair/device:

```sh
adb shell am instrument -w \
  -e class com.framenest.feature.listen_translate.FixedCaptionWindowComparisonTest \
  -e fixedCaptionWindowComparison true -e captionDiagnosisNetwork true \
  -e expectedAppApkSha256 APP_SHA256 -e expectedTestApkSha256 TEST_SHA256 \
  com.framenest.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.framenest cat files/fixed-caption-window-comparison.json \
  > fixed-caption-window-comparison.json
```

The existing source build workflow adds Emoji preparation only. No new runtime,
KVM, network-security, or device permissions are enabled here.

## Local verification, 2026-10-16

- 21 unique Python schema/preparation tests passed (12 existing + 9 new), covering
  fixed extent/conditions/pins, no default network, no model duplication, refusal
  to overwrite, corrupted source/model/PCM, untruncated decode, WAV extraction,
  and oversize public downloads.
- Exact local MCDC/model inputs and full Emoji were successfully staged. Both
  direct WAV sample extraction and full WebM FFmpeg decode produced the same
  existing Emoji PCM hash.
- New Kotlin sources compiled with real production engine/Sherpa/helpers and the
  exact retained source AAR's `classes.jar`; Android, JSON and ML Kit API stubs
  were used only for local compile. Disabled-opt-in smoke passed before native or
  asset access, and the JUnit entrypoint returns JVM void.
- Small Android fixture parser tests were added and compiled. They have not been
  executed on Android in this implementation turn. No actual inference/MT,
  device, rendering, or NAS result is claimed. Owning integration must run the
  real Gradle checks and authorized retained Android candidate next.
