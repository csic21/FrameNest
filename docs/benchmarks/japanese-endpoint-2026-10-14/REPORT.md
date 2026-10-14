# Bounded acoustic-pause experiment

2026-10-14. Experimental cloud ASR evidence; not a default behavior change or bilingual release acceptance.

## Conclusion

Nonoverlapping, whole-text SenseVoice segments avoid the baseline’s visible mid-word clipping and reduce duplicate computation. On this one sample, however, the accuracy gain is small and acoustic pauses frequently occur inside sentences. Live first-output latency becomes worse. Do not promote this policy directly to the default or describe its first cue as a complete translatable sentence.

## Method

Same first 114 seconds of the previously verified Japanese Kishida video. Mono PCM16 at 16 kHz; SHA256 ce2ddb47957ff9d515e2b9226e02f5ecab5fb311f0af1dbc487b6b7651db919f. Same pinned SenseVoice int8, sherpa-onnx 1.12.32, Japanese hint, inverse text normalization, greedy decoding, 2 runtime threads; cloud AMD EPYC 9V74 with process CPU affinity 0–1. Three sequential repetitions per profile and baseline.

Endpoints use only causal audio energy, never transcript or reference captions: 20 ms RMS frames; primary quiet threshold −38 dBFS; 320 ms consecutive quiet confirmation; minimum segment 2 s with at least 200 ms above-threshold audio; maximum 9 s. Cut at the end of the confirming quiet interval. Every sample is retained exactly once, including quiet intervals; no overlap and no string deduplication. The final residual segment ends at EOF. All raw ASR text is retained. A forced 9 s cut wins only when no qualifying pause has just completed.

The −38 dBFS setting was declared before decoding/scoring; −35/−41 dBFS are sensitivity tests, not a post-hoc winning threshold. `energy.csv` exposes every frame’s RMS level. `results.json` lists every segment’s PTS, quiet tail, active duration, forced-cut reason, original text and decode time. This is auditable acoustic-energy evidence, **not a human listening audit**. No listening validation is claimed.

## Measurements

| Profile | Segments | Forced 9 s cuts | CER | Compute RTF, 3 runs |
|---|---:|---:|---:|---:|
| Baseline: 3 s output + 750 ms context each side, token-midpoint clipping | 38 | n/a | 14.68% | 0.039–0.046 |
| Primary: −38 dBFS pauses, whole text | 22 | 3 | 13.99% | 0.024–0.027 |
| Sensitivity: −35 dBFS | 22 | 2 | 12.84% | 0.024–0.025 |
| Sensitivity: −41 dBFS | 15 | 8 | 14.45% | 0.024–0.029 |

CER uses the existing independent, edited Commons reference: 436 characters after NFKC/lowercase and removing Unicode punctuation, symbols and whitespace. It omits spoken fillers and is not adjudicated verbatim gold. The primary improvement is only 3 character edits, 64 → 61. One clip does not establish general accuracy or robustness.

Primary segment median duration: 4.67 s; maximum: 9.00 s. These entire-segment timestamps are materially coarser than the baseline 3 s windows. They include lead-in/tail silence and cannot be represented as precise word timings.

### NAS / already available audio

With all PCM already decoded and model initialized, measured first cue compute completion is 0.150–0.198 s, and the first two cues finish in 0.272–0.343 s. These are genuine non-realtime batch timings, **excluding** file/network reading, video decoding, model loading, translation, rendering and playback scheduling. They show possible ahead-computation capacity; they do not establish bilingual startup latency.

### Live / endpoint must arrive

Two actual paced replays waited for each causal endpoint at 1× audio time before decoding:

- First segment PTS 0.00–6.84 s; output at 7.015 / 7.024 s from playback start.
- Second segment PTS 6.84–12.36 s; output at 12.542 / 12.485 s.
- Third segment PTS 12.36–16.18 s; output time is retained in JSON.

The separate earlier baseline paced measurement produced its first clipped window around 3.84–3.86 s, but the text was only “まず 昨”. These are different amounts and quality of content. Neither is a complete-bilingual-caption measurement. The JSON’s separately named `simulated_live_output_seconds` values, where present in full raw results, are calculated schedules, not paced measurements.

## Sentence-readiness inspection

Review here is of the actual Japanese model output, not listening-based ground truth:

- 0.00–6.84: “はい、まず 昨日の事件 につい ては。” The word is no longer clipped, but this remains a topic introduction; decoder punctuation does not make it a complete sentence.
- 6.84–12.36: contains the predicate ending “捜査中です”, with substantial recognition errors earlier in the segment. Combining context from the first two cues is more coherent, but live delivery must wait roughly 12.5 s and translation remains unmeasured.
- 12.36–16.18: “また、私の方からあの。” A hesitation pause produced an incomplete fragment.
- 16.18–25.18: forced 9 s cut ends at “漁業組合”; the next cue begins “の方々…”. Hard bounding can still split a syntactic phrase.
- 98.24–100.48: “そして。” is emitted alone.
- 113.04–114.00 EOF: “考えています。” depends on the preceding cue.

Thus full-text segments solve one clipping mechanism but do not themselves solve coherent translation units. No simple string deduplication or punctuation-based completeness assertion was introduced.

## Recommendation

Keep this experimental. NAS can explore a separate context-aware translation unit spanning adjacent source cues while preserving those cues’ true source PTS and bounding pending work. Live behavior needs an explicit latency-versus-completeness policy, tested on more speakers/noise conditions. Do not silently substitute whole-phrase PTS for word timing or claim earlier translated captions from a low compute RTF. Actual MLKit Japanese→Chinese, stale translation rejection, seek behavior, display scheduling and OPPO performance remain separate acceptance gates.

## Reproduction and provenance

`experiment.py` is the exact executed script. It expects the existing benchmark fixture/model files beside it (`kishida-114s.wav`, `kishida-reference-114s.txt`, `models/sensevoice/*`) and the pinned benchmark requirements. Run it with the benchmark virtualenv; it emits `endpoint-results.json` and `endpoint-energy.csv`. The concise `results.json` here is a faithful selection of its measured fields; full raw results retain all runs, transcripts and token timestamps.

Media: Cabinet Public Affairs Office / Prime Minister’s Office of Japan, 2023-04-16. Commons declares GJSTU 2.0 / CC BY 4.0; attribution 首相官邸ホームページ. Source video was downloaded and content-hash checked; first 114 s extracted for this experiment. No government endorsement or official machine transcript implied.

- Media and license record: https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm
- Independent reference: https://commons.wikimedia.org/wiki/TimedText:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm.ja.srt
- Media license: https://creativecommons.org/licenses/by/4.0/
- Historical publisher page/terms currently returned 404 in prior verification; do not claim freshly verified primary publisher text beyond retained Commons evidence.
- SenseVoice model revision: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07
- Weight license: https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE . Do not infer weight permissions from the sherpa runtime’s license. No weights are included in this report bundle.
