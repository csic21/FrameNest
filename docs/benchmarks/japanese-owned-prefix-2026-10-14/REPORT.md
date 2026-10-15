# Exact Kotlin continuous segmentation: FAILED

2026-10-14. Executed supplied `/tmp/run-japanese-policy-cli.sh` with actual SenseVoice 12 s core / 15 s context decodes. No Python rewrite of the selector. Raw S16LE, full raw token TSV (including spaces), core text, raw results, CLI stdout and timing are retained per segment. Exact CLI JAR SHA256: c936a28b718c47bd9bbd92eb7b68c4990782bdaa106beb1e322335774de4d029.

## Useful improvement

Emoji starts with coherent complete cues at 0→5.410→9.660→17.080 s. The first source word この and the language name 中国語 are no longer split in these cues. This is ASR-only evidence; it does not establish correct Chinese translation.

## Blocking failures

1. **Lost leftover syllable after a forced token cut.** At 52.260 s, both core and context ASR contain 伝えられるから. Policy selects only 伝えられるか and commits through 64.019 s, exactly around the unselected ら token start (64.019999 s). Fresh ASR from 64.019 s begins 絵文字は昔から and never recovers that ら. A token start is an approximate acoustic estimate, not a safe fresh-decoder boundary.
2. **Another word split.** At 95.639→97.499 s, ForcedTokenBoundary commits only 絵文. The next decode begins ちの影響. This splits and corrupts 絵文字 despite coherent text earlier in the longer block.
3. **No progress on another speaker.** Kishida’s first step commits 0→7.980 s using ForcedTokenBoundary, ending 容疑者や. At 7.980 s, shorter/core ASR begins 逮護さされ while context ASR begins 介護さされ. The first character mismatch makes the exact stable prefix zero. CLI returns `NONE	0`; the run stops immediately. Most subsequent words agree, but the exact-prefix policy cannot make progress. No fallback or fake skipped coverage was applied.

## Throughput and coverage

Emoji covered its true 113.960 s PCM duration in 16 selected segments. Two ASR passes per step total 11.166 s, RTF 0.098. CLI subprocesses total 2.538 s separately; this includes JVM startup and is not the in-app policy runtime. The previous single-pass 3 s baseline was about RTF 0.045–0.053 on this clip, so coherence checks have real extra model cost.

Kishida used its full audio duration (175.787 s), not an artificial 36/114 s EOF. It stopped at committed coverage 7.980 s; the blocked second attempt is preserved. Do not report a successful 36 s or 114 s cross-fixture result.

## Recommendation

Keep this path off by default. The next revision needs explicit bounded handling of unstable first text and retention of uncommitted text/audio across forced boundaries. Starting a fresh recognizer exactly at a token estimate is not enough. Do not merely skip NONE or discard an unmatched suffix. Re-run both fixtures before any default switch; preserve coarse phrase PTS and forced/incomplete labeling. A change that eliminates NONE must still be checked for dropped or duplicated characters.

## Files and provenance

`SUMMARY.json` is the concise review artifact. `emoji-results.json` and `kishida-results.json` retain all raw text/tokens and CLI outputs. Per-start folders retain exact CLI inputs; `run_policy_continuous.py` in the parent benchmark directory is the executed driver. Model: previously pinned SenseVoice int8 / sherpa-onnx 1.12.32, Japanese ITN, 2 CPU threads, process affinity CPUs 0–1, cloud AMD EPYC 9V74. No MT, Android timing, phone speed or listening audit is claimed.

Emoji: Simpleshow Japan, CC BY-SA 4.0, https://commons.wikimedia.org/wiki/File:Emoji.webm . Machine-derived text may contain recognition errors and is retained under CC BY-SA 4.0. Source SHA256 ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956.

Kishida: Cabinet Public Affairs Office / Prime Minister’s Office of Japan, CC BY 4.0 via Commons GJSTU declaration; https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm . Historical primary publisher links previously returned 404; retained Commons metadata supplies attribution. Source SHA256 f99bc9087c05ba69ad6b33ac7fbe35856a4c8ca0d261a38b7f76f5b8a1f60de9. Audio extraction is an adaptation for testing, not an official transcript or endorsement.
