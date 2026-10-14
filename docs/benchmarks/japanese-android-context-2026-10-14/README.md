# Real Android Japanese context/MT diagnostic — semantic failure

Run: https://github.com/csic21/FrameNest/actions/runs/38583335209

- Tested commit: `1e59e026aa1552c5242efc7ed7e2b0b1816c9a98`.
- Artifact: `11904571192`, SHA-256 `64e20cc09f9e3ae171a39ecaefdfbd23f4a07d5a6d7e2989a71961ed1b85c48a`.
- API 35 x86_64 emulator, temporary runner KVM. This single run's current-user ACL grant and original ACL restoration both succeeded.
- Functional pipeline, whole-block diagnostic, controls and timing comparison all executed successfully. This is **not semantic acceptance** or phone/NAS performance evidence.

## Measurements

The existing production session selected its first bilingual overlay after 64,480 ms on first installation and 4,030 ms with downloaded models but a fresh native recognizer/session. ASR preparation took 48,755 / 3,149 ms respectively. These are distinct cache conditions, not before/after software versions.

Four 15-second public-fixture blocks ran real Android PCM decode → complete SenseVoice text → ML Kit. Total warm time was 2,075–2,129 ms: PCM 586–601 ms, ASR 1,279–1,303 ms, MT 183–246 ms. These overlapping diagnostic blocks were not stitched, cached or displayed. Their coarse window bounds are not sentence timestamps.

Eight same-emulator timing trials used ABBA×2 serial-preparation controls versus the production overlapping stage helper, each with a fresh MT client and downloaded models. Every trial had identical PCM (71,736 samples) and identical recognized source text. Four observations per schedule are exploratory:

- Serial total: 715–745 ms, median **729.5 ms**.
- Overlap total: 688–841 ms, median **729 ms**.
- Exposed preparation wait: serial median 16 ms; overlap 0 ms.
- ASR median: serial 415.5 ms; overlap 460 ms.

There is no demonstrated overall warm speed benefit in this small run. Eliminating preparation wait did not materially lower the observed total median; CPU scheduling/ASR variation matters. Do not claim a causal regression either from four samples per arm. This is not an old-APK, cold-download, physical-rendering or OPPO comparison. `source_callback_ready_ms` is a callback, not a Room/UI publication event.

## Meaning remains the release blocker

The eight existing three-second windows reproduce fragmented words and mistranslations. Longer ASR context does not by itself solve the problem. Examples from complete, authored linguistic controls (no ASR involved):

- 中国語 was rendered as 中国人 in the language-list question.
- 正解は絵文字です was rendered as 正确的答案是象形图.
- A broader-age popularity/message-use statement lost its relations and fluency.

The complete question and contrastive negation controls preserve their central meanings. There is no claim that every translation fails, but the tested MT route does not meet reliable Japanese-to-Chinese acceptance. The four ASR whole blocks additionally contain partial-window starts/ends and recognition errors; do not attribute every error in those blocks solely to MT.

Controls are authored natural-language probes, **not independently verified audio gold or a listening audit**. Example Chinese translations are semantic review aids, not exact-string assertions. See the independent review alongside these raw results. No fixture-specific dictionary corrections were applied.

## Provenance and next gate

Emoji fixture: Simpleshow Japan, [Commons source](https://commons.wikimedia.org/wiki/File:Emoji.webm), [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The exact original file is pinned in `fixture-license.json`; derived machine text may contain errors and is retained with the same attribution/license. No user/NAS media or credentials were collected.

Keep the experimental Japanese selector/stitcher disabled. Next step is a bounded, real direct Japanese-to-Chinese model comparison using these same inputs, with model license/integrity, cold load, inference cost and peak memory recorded. A successful functional job must not be converted into a release or a fast-bilingual claim before that quality gate.
