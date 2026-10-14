# 0034 — Explicit recorded Japanese quality mode

Status: implemented candidate; real-audio/Android acceptance pending.

The default playback/listen path remains available. A native-enabled build may
expose an explicit Japanese→Chinese quality choice when SenseVoice is selected.
Saving that choice does not download anything. Enabling listening subsequently
uses the existing metered-download policy and progress UI to install the pinned,
SHA-256-verified 1.06 GiB Hy-MT2 Q4 model. Source-only mode never requires this model.
The model is Apache-2.0 and the pinned llama.cpp runtime is MIT. Neither model
weights nor user content are bundled or sent to an inference service.

The optional producer separates its fixed 15 s / 9 s audio-read grid, retained
unconfirmed text, and successfully translated coverage. Seek generations recreate
bounded state with preceding acoustic context. One bounded 24 s union may repair a
weak overlap; failure retains unconfirmed source and continues the fixed grid.
Kuromoji morphology and measured quiet propose source units. Punctuation alone
cannot certify a sentence. Only FINAL units are translated; uncertain source uses
an explicit deferral, remains visible, and cannot complete subtitle coverage.
Long uninterrupted or uncertain speech may therefore remain untranslated in this
candidate. This limitation is preferable to the measured invented completions.

Only actual extractor EOF plus decoder drainage closes the retained tail. Output
buffer PTS, not rounded resampled sample count, supplies the audio timeline. Native
inference, dictionary work and PCM analysis run off the UI thread. Decode horizon,
translation queue, context/output size, recovery and generation checks are bounded.
Returned records survive a failed cache callback and replay before further producer
mutation. Provisional supersession can remove old provisional rows, never finals.

Performance must include download/load, decode, ASR, MT prepare, generation and
cache/display consumption. Cloud CPU measurements are not OPPO measurements. The
warm ML Kit scheduling experiment did not show an overall speed win. Direct Hy-MT
preserved six targeted complete controls but failed truncated contexts, motivating
this source-unit change rather than more prompt tuning.

Release gate: real full-video source/translation review including unconfirmed
fractions; optional Android compile and native packaging/alignment; actual lifecycle
and inference validation; exact-release CI and existing signing-certificate check.
The previous one-run KVM authorizations are not extended by this implementation.
