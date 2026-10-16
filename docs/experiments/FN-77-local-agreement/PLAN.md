# FN-77 bounded same-origin stability diagnostic

Baseline: released 0.6.11 source `ab6dfafdb010b62dffbceced79661e31c1d48e08`. This is an offline, script-only diagnostic. It is not connected to the application or a release workflow.

The preceding fixed acoustic-cut candidate conserved every sample but still damaged words at forced limits and split related phrases at quiet boundaries. It is not adopted. Do not tune its threshold or infer a complete sentence from low PCM energy.

## Mechanism under test

Within one fixed audio origin, recognize successively larger 3/6/9/12-second prefixes. Compare two consecutive complete hypotheses using exact Unicode code-point prefixes, with no space/punctuation normalization or inferred CJK lexical boundary. Confirmation is only stability evidence. It does not authorize a standalone translation, final cue, or completed coverage.

If a later full hypothesis no longer starts with the previously confirmed prefix, retain both the historical confirmation and the complete current hypothesis and report CONFLICT. Never silently revise an already claimed prefix or discard the current tail. Budget exhaustion and EOF retain the latest full hypothesis and explicit pending text. Reject malformed, stale, changed-origin, non-growing, overflow, or overlong inputs atomically. Reset reports that an old diagnostic state was discarded; it cannot publish stale source.

Bound each episode to four observations, twelve seconds of source, and 4096 Unicode code points per hypothesis. Keep no unbounded hypothesis history in the component. The external diagnostic records all inputs/results for review. Empty output is not evidence of silence.

## Fixed full-sample replay

Use only the same MCDC 47.72-second holdout and complete Emoji 113.96-second samples with the exact existing SenseVoice model and Python 1.13.8 configuration. Divide each sample into predeclared contiguous 12-second diagnostic episodes; grow context within each episode, clipping only the final observation to EOF and never repeating an identical observation end.

**An episode reset is an unconfirmed evaluation boundary, not a production audio-rollover policy.** Retain every episode's latest full text, inspect cross-episode damage, and do not concatenate confirmed prefixes into a purported final transcript. This stage does not solve bounded continuous acoustic context or translation-unit construction.

Record exact PCM hashes, all raw hypotheses, confirmation/retraction/tail statistics, earliest confirmation input time, no-progress episodes, and ASR CPU/wall-clock measurements. Compare with the already measured same-Python 3-second baseline. Four full prefix decodes cost 30 decoded audio-seconds per full 12-second episode, versus roughly 18 with the existing 3-second +/-750 ms schedule: input volume is about 67% greater, not a measured time ratio. NAS ahead-reading does not remove that cost.

## Go/no-go

A useful result requires stability evidence that survives later context without systematically cutting/retracting Japanese text, explicit accounting of every pending tail, and tolerable measured work. Passing pure state tests is not passing ASR or semantic quality. If this diagnostic is not worthwhile, report why before building Android or MT integration. If worthwhile, separately design context-preserving MT units, finite deadlines, and a validated rollover/fallback; do not import an upstream discard-on-no-progress policy or the rejected Hy stitcher.
