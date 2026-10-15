# One official Silero VAD control: still reject Reazon promotion

2026-10-15. This is the final bounded follow-up, using one official preprocessing configuration without threshold/prompt/model hunting. Three passes for each model on full Emoji, full Kishida, and a separately scored first-114-second Kishida prefix. Same VAD intervals for both recognizers; raw outputs deterministic.

## Result

Reazon's edited-caption CER is **23.17%**, versus **10.09% SenseVoice**. This is materially worse than the current model under identical deployable VAD preprocessing. It does not reproduce the 8.49% oracle-boundary result.

| Model | Emoji VAD+ASR median RTF | Full Kishida VAD+ASR median RTF | Prefix CER | Peak process RSS KiB |
|---|---:|---:|---:|---:|
| SenseVoice | 0.0270 | 0.0331 | 10.09% | 594,528 |
| Reazon mixed | 0.0311 | 0.0303 | 23.17% | 776,316 |

VAD computation alone: approximately 0.475–0.501 s for 113.960 s Emoji, and 0.796–0.849 s for 175.7866875 s Kishida (medians across each model's runs). ASR model load separately: SenseVoice 0.900 s; Reazon 1.204 s. Peak RSS includes Python, runtime, model, PCM, VAD and retained output; it is not a model-only or phone memory metric. Repeated model output is identical, but host timing can vary with load.

The first Emoji endpoint becomes available after input PTS 5.280 s; first decode takes 0.060 s SenseVoice / 0.068 s Reazon. Kishida first endpoint is at 5.088 s; first full-file decode takes 0.097 s / 0.104 s. These are input endpoint and compute measurements from file processing, not an actual paced/live wall-clock or bilingual latency test. No MT is measured.

## Exact official configuration and EOF handling

Sources at runtime tag v1.12.32:

- https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/python-api-examples/vad-with-non-streaming-asr.py
- https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/csrc/voice-activity-detector.cc

Use `VadModelConfig` defaults, overriding only `silero_vad.model`, sample rate 16000 and `min_silence_duration=0.25` exactly as the official example. Defaults observed from pinned runtime: threshold 0.5, minimum speech 0.25 s, maximum speech 20 s, window 512, negative threshold -1, VAD CPU 1 thread. ASR remains CPU 2 threads, process affinity CPUs 0–1. The official implementation can exceed nominal max speech while seeking a suitable endpoint; observed longest segment 24.726 s, still within Reazon's documented approximately 30 s input limit. Do not treat the nominal 20 s setting as a hard memory/latency cap.

The official example passes emitted samples directly to offline ASR, with no added ASR zero padding. The C++ detector supplies onset pre-roll using two windows plus minimum-speech history and removes the confirmed silence tail. For file EOF, zero-fill only the incomplete final 512-sample VAD input frame (never append a tuned long silent tail), then invoke the provided `flush()` method. Clamp emitted intervals back to true file length before ASR. Thus padding does not become recognized source coverage and the last active speech segment is retained. This is the documented API's file adaptation of the microphone example, not a claim that the microphone example itself has an EOF path.

Silero model downloaded from the exact official sherpa release link documented by that example:
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx

643,854 bytes; SHA-256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`. This is a measured content hash from the official source, not a separately signed upstream checksum. Future reproduction fails closed on a changed file. MIT license retained from https://github.com/snakers4/silero-vad/blob/master/LICENSE . The ASR runtime/checkpoints are unchanged.

## Coverage and critical failure attribution

Every input sample is processed by VAD, including the partial EOF frame. Exact selected intervals and complement gaps (with RMS energy) are retained and their union is verified to equal the source duration without overlap:

- Emoji: 13 speech segments, selected 95.200 s, excluded 18.760 s.
- Full Kishida: 12 segments, selected 168.932 s, excluded 6.8546875 s.
- Scoring prefix: 8 segments, selected 110.636 s, excluded 3.364 s.

This is complete accounting of input, NOT proof all speech was selected. Excluded spans are VAD decisions, not independently verified silence; no human listening adjudication of every gap was performed. They must never be marked as successfully transcribed coverage. The separate 114 s prefix is used only to align the independent edited reference; the full recording is separately processed to its real EOF. No caption/oracle boundaries choose any VAD interval.

Crucially, Reazon's remaining failures occur inside the exact PCM sent to both recognizers:

- Emoji 6.278–16.966 s: SenseVoice retains 今、世界中で使われているのは何語でしょうか plus the language list. Reazon returns では英語スペイン語中国語それはビジネス文章では使われない言葉です, dropping the question.
- Emoji 17.190–19.398 s: both recover 正解は絵文字. Reazon's earlier answer omission is fixed here.
- Emoji 21.606–32.678 s: Reazon recovers the Hollywood clause, but drops 若い世代に人気です from the preceding material, where SenseVoice retains it. No MT/discourse correctness is established.
- Kishida 7.270–23.270 s: SenseVoice attempts the arrest/investigation clause (imperfectly as 容疑者が態度をされ、そして検察において捜査中です); Reazon omits it and starts また私のほうから…. Because the same selected audio includes that clause, this omission cannot be blamed solely on excluded VAD gaps.
- Kishida 46.502–67.612 s: Reazon retains 根幹をなす while SenseVoice gives 根幹をなく. This isolated advantage does not compensate for missing consequential content.

## Reproduce / stop condition

After `prepare.py`, run:

    venv/bin/python benchmark_vad.py sensevoice
    venv/bin/python benchmark_vad.py reazon
    venv/bin/python summarize_vad.py

Checks passed: 18 fixture passes; identical VAD intervals across models; deterministic raw outputs across repetitions; exact selected-plus-excluded sample accounting; token counts, monotonicity and within-segment PTS range. Python compilation passes.

Stop Reazon exploration here. No adapter/default promotion or release change is justified. SenseVoice's VAD result motivates separately investigating causal boundaries/coverage for the existing model, but does not by itself pass arrest wording, full semantic accuracy, actual live pacing, Android memory, or phone/NAS release requirements.
