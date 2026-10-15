# Released-default boundary probe, 2026-10-15

Read-only host diagnostic, retained before any FN-76 implementation. Production
files at release source `5a16f1cc4dc7875dd1237c898e795007dc461e94` and audit checkout
`982254053ba8203d1ca8c828f04bc18033975422` were identical for this path. No repository
or model changes were made by the probe.

## Evidence

`host-probe/raw.json` retains 20 actual stock sherpa-onnx **1.13.8 host** decodes:
Emoji 0–42 seconds and Kishida 0–18 seconds, three-second owned windows with
the default ±750 ms context. It uses the same pinned SenseVoice int8 model,
Japanese ITN, two CPU threads, CPUs 0–1. This is not the Android source binary.

`run.py` is the executed host capture script, preserved unchanged with its original
workspace paths. It verifies model and public source fixture SHA-256 values before
inference. `raw.tsv` retains the original tokens/timestamps as the Kotlin input.
`Probe.kt` and `Selection.kt` were compiled with the repository's actual
`SherpaTokenMapping.kt`/`AsrEngine.kt`; `selected.tsv` is the exact Kotlin output.
TSV token/text fields are UTF-8 base64; milliseconds remain numeric.

The select helper source was extracted verbatim from RealListenTranslateEngine;
the probe's cue-bound calculation reproduces its existing 600 ms minimum then
window-end clamp. The wrapper is not an Android/ML Kit/playback run.

## Findings

- Emoji 0–3s selects `こ`, display 2879–3000 ms (121 ms); 3–6s starts `の`.
  The demonstrative remains split even though both recognitions contain `この`.
- Emoji 9–12s ends `中`; 12–15s begins `国語`. The language name is split before MT.
- Kishida 12–15s ends `から`; 15–18s starts `ら`. The shared syllable is emitted twice.
  Its first inferred interval is 14490–15090 ms (midpoint14790); its next independently
  decoded interval is 14609–16350 ms (midpoint15479). Both pass their own window's
  midpoint selector. The existing public edited reference contains `私の方から`
  once; this is not an independent word-for-word listening acceptance.

`SherpaTokenMapping.kt:33` derives ends from the next token's onset.
`RealListenTranslateEngine.kt:320` assigns each token using that inferred interval's
midpoint. The existing single-hypothesis synthetic selection test does not prove
unique ownership across independent overlapping recognitions. Changing to onset
ownership alone is not an accepted fix: boundary drift, lost context and splitting
still require proper evidence. The default was deliberately left unchanged.

No new ML Kit output or universal ASR accuracy score is claimed. Historical complete
reference-text ML Kit controls also fail independently of slicing; see the existing
Japanese Android context semantic review. FN-76 adds held-out three-column evidence
instead of enabling the known-failed Japanese forced-token experiment.

## Attribution

Emoji: Simpleshow Japan, [Emoji.webm](https://commons.wikimedia.org/wiki/File:Emoji.webm),
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), source SHA-256
`ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956`.
Machine-derived text may contain errors; these derived excerpts retain CC BY-SA 4.0.

Kishida: Cabinet Public Affairs Office / Prime Minister's Office of Japan,
[Commons source](https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) via Commons GJSTU declaration,
source SHA-256 `f99bc9087c05ba69ad6b33ac7fbe35856a4c8ca0d261a38b7f76f5b8a1f60de9`.
Audio extraction and machine-derived text are testing adaptations, not an official
transcript or endorsement. Existing edited Commons captions are not verbatim gold.
