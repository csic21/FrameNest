# Decision: reject this Qwen3-ASR candidate

The official 2026-03-25 Qwen3-ASR-0.6B int8 sherpa path did not improve this controlled two-video Japanese benchmark. First114s Kishida CER85/436=19.50%, versus same-runtime SenseVoice36/436=8.26%. Edited Commons captions omit some fillers, so this CER is not absolute verbatim accuracy; full raw outputs support the negative conclusion. Emoji has no independent reference score. No new MT tests, Android measurement or production change.

Qualitative checks under the same VAD envelopes:
- Arrest: Qwen improves to 容疑者が逮捕され; SenseVoice still 介捕.
- Democracy: Qwen says 民衆議会の根幹; current SenseVoice preserves 民主主義の根幹をなす.
- Emoji language question: both preserve the question and English/Spanish/Chinese choices.
- Hollywood: Qwen still says 絵文字の英語 where the intended film/movie is 映画.
- Qwen also turns 容疑者確保 into 漁業者過去 and introduces other errors.

No word timestamps are returned for any Qwen segment. Input VAD envelopes give speech-region positions only; using them as word alignment would be misleading. Qwen's larger memory and latency therefore come with an additional alignment integration gap.

The newer runtime's SenseVoice improvement is a separate hypothesis, not proof that VAD caused it. Same original weights, PCM, VAD windows, language, ITN, thread count and affinity have been checked. Legacy1.12.32 same-script replay completed: all segments on all three passes exactly reproduce the previous1.12.32 results, including44/436=10.09%CER. Current1.13.8 gives36/436=8.26%. This isolates the observed difference to the runtime package stack on this host, not script/window/model changes. Root numerical cause and Android behavior are not established. See RUNTIME-REPLAY.json and CONTROL-VERIFICATION.json. No comparison here mixes fixed3-second chunks with VAD.

Final three-pass Qwen text is identical; warm ASR RTF medians: Emoji0.2841, full Kishida0.2867,114s0.2962. SenseVoice corresponding0.0405/0.0504/0.0471. First process model-load Qwen5.979s vs Sense2.344s; OS cache not controlled. Full process peakRSS Qwen3282.49MiB vs Sense468.60MiB. Qwen first Emoji speech segment decode1.179s vs Sense0.131s, with5.28s of source input required before VAD yielded it. These exclude live-player/VAD costs and are not device promises.
