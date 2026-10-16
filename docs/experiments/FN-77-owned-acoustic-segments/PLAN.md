# FN-77: bounded acoustic ownership experiment

Baseline: released `v0.6.11-internal`, source `ab6dfafdb010b62dffbceced79661e31c1d48e08`.
Status: candidate rejected after bounded real ASR replay; retained as an unused ownership/state prototype. No production switch, model, MT provider, release, or security permission changes.

## Problem and narrow hypothesis

FN-76 reproduced lost/duplicated tokens at independent ASR midpoint ownership boundaries. A fixed 6-second candidate was rejected. Correct reference text also exposed ML Kit semantic errors; input ownership alone cannot solve those.

Test one alternative: assign contiguous PCM samples to bounded acoustic spans and recognize/translate each span's complete source once. Do not join independently decoded texts or clip them with token midpoint estimates. A measured quiet gap is a possible acoustic cut, **not evidence of a complete sentence**. This is deliberately narrower than the previously failed stable-prefix/grammar/Hy path: one ASR pass per owned span avoids its alignment deadlocks and repeated inference, but loses some cross-span context. No claim of word preservation follows from sample preservation.

## Fixed initial policy

- Existing energy heuristic: normalized PCM16 RMS at or below -35 dBFS, fixed 20 ms frames.
- A 320 ms quiet observation permits a midpoint cut, retaining at least 160 ms on either side. No quiet samples are deleted.
- Earliest legal cut after 2 seconds; maximum owned span 12 seconds. A fixed 320 ms read lookahead may resolve a quiet observation at the limit; pending audio remains bounded.
- With no qualifying acoustic boundary, emit the exact capped span with `FORCED_LIMIT`, retaining every suffix sample. This is an explicit bounded fallback, not a claim that the word/sentence was complete. Forced-boundary damage counts against acceptance.
- EOF flushes every remaining sample, even below the minimum. Empty EOF produces no invented caption. Reset/generation rejects old input and results.
- Partial-consumption/backpressure avoids unbounded queues or copying arbitrarily large caller buffers.

The implementation tests must lock the precise frame/decision ordering before real replay. Different caller chunk partitions must produce identical boundaries.

## State and integration boundaries

Keep sample read/owned cursors separate from ASR output, translation availability, and persistent completion coverage. Audio assignment never certifies recognition or translation correctness. No sample-only duration may fabricate media EOF; future player integration must use `pcmWindowDetailed` and its explicit media/EOF contract.

Reuse existing 6-second sequential PCM cache, SMB block cache, 12/30-second RTF-based caption prefetch, generation cancellation, and lifecycle. These are already shipped, not new performance features. A proposed 10-second recognition context or 15–30-second ready reserve is not a measured promise.

Stage 1 is a pure bounded state machine plus reconstruction, arbitrary-chunk, silence, continuous speech, EOF, reset, and malformed-input tests. Stage 2 replays the fixed MCDC 47.72-second holdout and complete Emoji 113.96-second sample with the exact existing SenseVoice model; then the unchanged ML Kit engine requires real Android validation. No mock translation can accept the experiment. No model/threshold/window search.

## Acceptance and stopping rules

- Every input sample belongs exactly once or remains explicitly pending; all EOF tails resolve. Zero missing/duplicated sample ownership, unbounded memory, or state deadlocks.
- Inspect every emitted source/translation and all forced cuts. Report processed audio, recognized text availability, translated availability, and semantic review separately; never call processed coverage semantic coverage.
- Compare both fixed samples against baseline for existing boundary failures and new meaning loss. Fixing selected phrases while introducing equal or worse important losses does not pass.
- Record cold model preparation separately, PCM/decode cost, actual ASR and MT costs, first usable bilingual time, and steady lag. Predecoded/cloud timing cannot establish OPPO/NAS performance.
- Seek/exit/background must reject stale output and clear pending state. MT failure preserves source without falsely finalizing coverage.
- If the single candidate does not show a useful quality/latency balance, retain 0.6.11 defaults and report the negative result. Do not release UI changes as a substitute for precision improvement.

## First fixed candidate result (2026-10-16)

The pure component passes 26 focused JVM tests and an independent 780-case conservation/chunking oracle. Real replay produced 14 MCDC and 16 Emoji spans, with complete sample reconstruction. Emoji required six forced-limit cuts. Those cuts still damaged a word that the baseline retained; quieter MCDC cuts also split related phrases. Some earlier word fragments improved, but this does not establish a reliable net quality gain. The energy policy is rejected without threshold/window search or ML Kit rollout.

A direct-ASR zero-audio EOF artifact is not a new production finding: the existing production digital-zero gate was intentionally absent from this ASR-only replay. Low energy alone does not prove silence. No Android, MT, NAS, or phone-speed acceptance is claimed. Full local evidence and existing Actions baselines remain separate; no raw transcription/token report is uploaded here.
