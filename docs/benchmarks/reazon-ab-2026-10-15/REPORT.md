# FN-70 ReazonSpeech k2-v2 controlled A/B: do not promote

2026-10-15. Actual host CPU inference, pinned sherpa-onnx 1.12.32. No product files/defaults/runtime changed. The proposed mixed-precision Reazon pack works with the existing runtime, but is not a safe drop-in replacement for SenseVoice.

## Outcome

On identical nonoverlapping 15-second windows, Kishida first-114-second edited-caption CER worsens from **14.45% to 26.15%**. Reazon fixes important local wording but omits whole clauses. On identical 3-second windows it also trails, **22.94% vs 20.18%**, with slower host compute. These failures block the proposed default/model-adapter promotion.

A deliberately oracle-aligned control using the independent caption boundaries improves Reazon to **8.49% vs SenseVoice 14.91%**. This is evidence of segmentation sensitivity, not a deployable result: production does not know these caption boundaries, final syllables remain missing in several cues, and the Reazon process reaches a higher peak RSS on longer windows. Emoji still loses the language list and the answer even in the selected cue-boundary control. There is no quality-without-regression win across the required cases.

## Measurements

Three passes per profile/model; raw text/tokens/PTS identical across all three. Full fixtures used for fixed windows: Emoji 113.960 seconds and Kishida 175.7866875 seconds. The oracle diagnostic is deliberately bounded to Emoji 0–42.22 seconds and Kishida 0–114 seconds. CER always scores only Kishida 0–114 seconds, 436 normalized reference characters. Emoji has no verified gold and no claimed CER.

| Windows/model | Kishida CER | Emoji median RTF | Kishida median RTF | Peak RSS KiB |
|---|---:|---:|---:|---:|
| 15 s / SenseVoice | 14.45% | 0.0257 | 0.0287 | 597,468 |
| 15 s / Reazon | 26.15% | 0.0273 | 0.0265 | 539,820 |
| 3 s / SenseVoice | 20.18% | 0.0318 | 0.0307 | 595,388 |
| 3 s / Reazon | 22.94% | 0.0423 | 0.0395 | 421,192 |
| Oracle / SenseVoice | 14.91% | 0.0294 | 0.0294 | 594,472 |
| Oracle / Reazon | 8.49% | 0.0288 | 0.0283 | 697,368 |

These are AMD EPYC 9V74 cloud measurements, CPUs 0–1 affinity, two inference threads. Neither phone performance nor power/thermal stability is established. Small RTF differences are not a controlled mobile speed claim. RSS is process high-water including Python/runtime/model/PCM and retained output, not model allocation alone.

For the main 15-second profile, process-cold model load: SenseVoice 1.175 s, Reazon 1.494 s. First Emoji decode: 0.374 s vs 0.377 s; load plus first decode: 1.549 s vs 1.870 s. This excludes Python import, source download, audio extraction and input collection. A live offline recognizer still needs the full 15-second input window; these numbers are not live first-caption latency. Warm-pass timing and each decode are in the evidence JSON. Filesystem caches were not flushed, so “cold” means a newly constructed recognizer/process, not disk-cold.

## Critical passages

- Arrest/released: the independent first caption says 容疑者が逮捕され. Main 15 s Reazon matches this phrase; SenseVoice says 容疑者やが逮捕をさされ. Neither produces 釈放 in this controlled run. No MT was run, so this does not prove the previous release/arrest translation failure resolved end to end.
- 根幹を成す: main 15 s Reazon outputs 根幹をなす; SenseVoice outputs 根幹をなく. Reazon is locally better semantically, while the declared CER deliberately does not normalize kanji/kana spelling into equivalence.
- Emoji question/list/answer: main 0–15 s Reazon returns only このイラストの意味分かりますか, omitting the language question and list. Oracle 5.41–9.65 s recovers the question; oracle 9.65–17.08 s returns only それはビジネス文章では使われない言葉です, still omitting 英語、スペイン語、中国語; 17.08–28.30 s starts at 絵文字はインターネット上…, dropping 正解は絵文字. These are observed cross-window omissions compared with retained source context/SenseVoice output, not an independently scored Emoji gold transcript.
- Hollywood: main 15 s windows omit the Hollywood clause in Reazon. The bounded 28.30–42.22 s oracle control does recover 絵文字の映画がハリウッドで作られるほど…. This shows window dependence, not fixed discourse translation. No punctuation repair, prompt, beam/hotword search, or MT change was used.

## Reproduction and integrity

See PROTOCOL.md, model-files.json and fixtures/manifest.json. Official Reazon revision 291488c8151be24d7da4bf7af26e533fad96e407; mixed INT8 encoder/joiner and FP32 decoder; 169,180,699 bytes including tokens. Three ONNX hashes match official Hugging Face LFS metadata. Tokens are pinned to the same immutable revision and locally SHA-256 recorded. Apache-2.0 model card preserved. App-pinned SenseVoice and both videos exactly match their pre-existing accepted hashes.

Independent reference was freshly retrieved from the Commons TimedText page, first 8 cues through 00:01:54,000. The unspoken parenthesized question is removed. Normalize both sides by NFKC, lowercase, whitespace removal and removal of Unicode punctuation/symbol categories; no filler, spelling or recognition corrections. The reference is edited community subtitles and omits fillers, not adjudicated verbatim gold. Attribution/rights and links are in PROTOCOL.md.

Run in a disposable folder:

    python -m venv venv
    venv/bin/pip install -r requirements.txt
    venv/bin/python prepare.py
    venv/bin/python benchmark.py sensevoice fixed15
    venv/bin/python benchmark.py reazon fixed15
    venv/bin/python benchmark.py reazon oracle
    venv/bin/python benchmark.py sensevoice oracle
    venv/bin/python benchmark.py reazon fixed3
    venv/bin/python benchmark.py sensevoice fixed3
    venv/bin/python summarize.py

The original first 15 s experiment files are named sensevoice.json and reazon.json locally; the current runner labels new files with the profile. Summarize accepts either naming. Evidence JSON deduplicates identical raw text/tokens/PTS across three passes without removing any per-pass timing, load or memory measurement. All original uncompressed raw outputs remain in the experiment workspace. No large model/video binaries are committed.

## FN-70 handoff

- Status: bounded A/B complete; promotion rejected on measured quality regressions.
- Branch: experiment/FN-70-reazon-ab, based on da49192. Docs/reproduction/evidence only; stable PR4 and main untouched.
- Checks: 36 fixture passes; all model/video accepted hashes verified; 436 reference chars verified; all repeated raw output identical; token counts, monotonicity and within-input range pass; Python compilation passes.
- No Android build needed for docs/scripts-only change. No Android execution, phone/NAS, rendering, energy or MT acceptance claimed.
- Decision: retain SenseVoice and experimental defaults as-is. Reazon would require a separately justified causal segmentation/coverage solution and a fresh controlled comparison before an adapter or default switch. Oracle boundaries are not that solution.
- Shared-file edits required: none.
