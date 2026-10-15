# FN-70: controlled ReazonSpeech / SenseVoice experiment

No product changes, default changes, runtime upgrade, Android/phone claim, or MT inference.

- Runtime: sherpa-onnx and sherpa-onnx-core exactly 1.12.32; CPU provider, 2 inference threads, process affinity CPUs 0 and 1 on cloud AMD EPYC 9V74.
- Same mono 16 kHz float32 PCM derived by ffmpeg from hash-pinned public videos. Full Emoji 113.96 s and Kishida 175.787 s. Nonoverlapping 15 s windows, with additional Kishida 114 s boundary for reference scoring. No text-driven boundaries or model-specific overlap.
- Three consecutive passes per model in separate processes. First model load is process-cold, not filesystem-cache-cold; first decode and warm passes separately retained. Wall inference times are host timings, not live streaming latency. Offline first output requires collecting its whole window; token onsets are acoustic estimates, not word-end bounds.
- SenseVoice uses app-pinned int8 revision 2365baeacb507f821a0c8120fcee3d484dba7a07, Japanese and ITN enabled. Reazon uses official mixed INT8 encoder/joiner, FP32 decoder, greedy search with defaults; revision 291488c8151be24d7da4bf7af26e533fad96e407. No prompt/hotword tuning.
- Peak RSS is process high-water mark including Python/runtime/model/audio; not allocator-only model RAM and not Android RAM.
- Kishida CER compares first 114 s against first 8 independent Commons Japanese SRT cues. Remove the first cue's unspoken parenthesized question, then normalize reference/hypothesis with Unicode NFKC + lowercase + remove whitespace and Unicode punctuation/symbol categories. No spelling/filler/homophone corrections. 436 normalized reference characters. This is edited community caption agreement, not adjudicated verbatim gold. Emoji has no verified independent transcript; report raw critical passages, not CER.
- Retain original raw text, tokens, relative onset timestamps, absolute token PTS, each decode duration, load time, exact PCM/model hashes. No full public media or models committed.

## Sources and rights

Reazon official model card: https://huggingface.co/reazon-research/reazonspeech-k2-v2/tree/291488c8151be24d7da4bf7af26e533fad96e407 (Apache-2.0). Individual model byte counts and upstream LFS SHA-256 checked in reazon-files.json.

Emoji: Simpleshow Japan, CC BY-SA 4.0, https://commons.wikimedia.org/wiki/File:Emoji.webm . Source SHA-256 ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956. Machine-derived text can contain errors and is shared under CC BY-SA 4.0.

Kishida: Cabinet Public Affairs Office / Prime Minister's Office of Japan, CC BY 4.0 via Commons GJSTU declaration, https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm . Source SHA-256 f99bc9087c05ba69ad6b33ac7fbe35856a4c8ca0d261a38b7f76f5b8a1f60de9. Audio extraction/ASR are testing adaptations, not an official transcript or endorsement.

Independent edited reference: https://commons.wikimedia.org/wiki/TimedText:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm.ja.srt . Downloaded 2026-10-15, original SRT preserved. Source page may change; local SHA-256 retained.
