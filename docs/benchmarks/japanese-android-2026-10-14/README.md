# Japanese Android pipeline: functional pass, semantic failure

Verified source: `51cf12a6dedb9164fd3b0fd5d171d1fe62e5694a`.
Run: https://github.com/csic21/FrameNest/actions/runs/38552085413
Artifact: https://github.com/csic21/FrameNest/actions/runs/38552085413/artifacts/11887477157
Artifact ZIP SHA256: `14f7bb46c496b75efcfeb07e9fe6ed4c0be9e274ce62442c54d3eb2507bdbdb9`.

Real Android API35 x86_64 emulator with KVM, actual MediaCodec PCM, SenseVoice,
ML Kit Japanese→Chinese, Room and eligible overlay selection. No mocks in this
instrumented chain. App display-name smoke tests and two pipeline passes succeeded.
The one-run KVM device ACL was restored successfully by the workflow.

## Measured results

- First installation: eligible bilingual overlay at **66,115 ms** after test startup;
  ASR installation/check/load **49,896 ms**; PCM **1,736 ms**; ASR **2,027 ms**;
  overlapping MT preparation **14,021 ms**, exposed wait **11,973 ms**; MT **287 ms**.
- Download-cache-warm, fresh native model/session: eligible bilingual overlay at
  **4,396 ms**; ASR prepare **3,466 ms**; PCM **237 ms**; ASR **450 ms**;
  MT preparation **23 ms**, exposed wait **0 ms**; MT **224 ms**.
- Full window RTF versus scheduling RTF: cold **5.358 / 1.367**, warm
  **0.3077 / 0.3077**. Cold preparation subtraction did not falsely claim this
  particular first window was faster than real time.

This is a paused playhead at 3.5 s in a local licensed file. It is not physical
rendering, sustained playback, NAS transport, OPPO timing or a before/after speed
comparison. The two passes are cold/warm conditions, not old/new versions.

## Acceptance decision

**Functional integration PASS; semantic release acceptance FAIL.** Eight original
3-second windows show mid-word clipping (`こ` / `の`, `中` / `国語`), loss of the
language question/negation context and an incorrect emoji term. The first 0–3 s
window has source text but no translation. The pipeline must not treat it as
completed bilingual coverage. See the independent transcript-only semantic review.
No listening-based accuracy, independent golden transcript or phone acceptance is
claimed. Do not publish these results as a successful usable-bilingual release.

Only excerpts from the SHA256-pinned public Simpleshow Japan fixture are included.
The source/translation JSON carries CC-BY-SA-4.0 attribution and modification notes.
No application/user media, credentials, general device logs or model weights are
included here. Production diagnostics remain content-free.
