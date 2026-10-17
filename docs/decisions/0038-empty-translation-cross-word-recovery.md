# 0038: Empty-translation-only cross-word recovery

## Status

Implemented behind `RealListenTranslateEngine(emptyTranslationRecoveryEnabled = false)`.
Production enablement remains gated on the same-version Android/runtime checks.
This is failure recovery, not an accuracy claim or a new default segmentation policy.

## Decision

Keep the baseline 3-second ASR windows, ±750 ms context, canonical midpoint ownership,
source-first callbacks, and already-produced nonempty MT results unchanged. A nonempty baseline
target does not read a following window or initialize Kuromoji.

Only a successful empty target for nonblank Japanese source and a differing target
language may request recovery. Only that empty window and its uncompleted adjacent
window may form a changed two-window result; the adjacent window might have produced
a nonempty translation if processed independently. All unrelated nonempty results stay
exact. The session explicitly grants the next uncompleted
canonical window; it refuses any overlap with existing translated/completed content.
The engine recognizes that window using the unchanged baseline extractor, then asks
Kuromoji NORMAL morphology whether a morpheme crosses the exact original-string seam.
Whitespace may be omitted in the analysis view; the original output is never normalized,
deleted, deduplicated, or supplemented from a full recognition hypothesis.

When the seam crosses a morpheme, issue at most one MT call on the exact concatenation.
The pair owns at most two original windows and 6 seconds (shorter at a known EOF).
A success carries both source witnesses. The session separately validates the exact
concatenation, original speech endpoints, granted end, and successful target before
allowing this one explicit fixed-window span replacement. Normal callback/final span
validation is not relaxed. A dedicated recovery-only Room transaction rechecks current
rows, refuses translated/covered or crossing-boundary conflicts, removes contained
source-only rows (including old rev=1 rows), and commits original-PTS speech, exact
coverage, and progress together. Cancellation or insert failure rolls back the entire
replacement. Ordinary pipeline persistence is unchanged; no Room schema change is required.

Any empty recovery, absent next source, no crossing, decoding/ASR/MT failure, or
cancellation keeps the original source visible and never certifies next-window coverage.
Recovery does not recursively combine a third window. Two baseline source-cache entries
avoid rerunning ASR on repeated empty MT. Attempt keys are generation-local, include
source window/language/track and target language, and are never evicted to rearm a retry.
The bounded 64-key budget fails closed for further recovery in that generation while
ordinary baseline MT retries remain available. Seek/lifecycle/model/language/track reset
invalidates pending work and clears state; all state access uses a short shared lock,
never held across suspending work. Callbacks and commits retain coroutine/generation
checks. Kuromoji is lazy and runs off the UI thread only after a real empty-MT failure.

Enabled engines use the `|empty-ja-crossing-v1` MT cache-policy suffix to distinguish
recovered spans from previous subtitle ownership. Disabled/direct baseline processing
retains the existing model IDs and fixture outputs.

## Evidence and limits

The triggering diagnostic had one actual empty-MT success: a split Japanese question.
The broader all-pairs morphology strategy was rejected for semantic regressions and
is not part of this decision. A crossing morpheme does not prove the translation is
correct. Deterministic tests cover exact nonempty preservation, finite repeated empty
results, no crossing, errors, EOF, cancellation/reset, original PTS, strict replacement
validation, and absence of false coverage. Android model execution and Room SQL behavior
require the separate runtime/Android acceptance checks; JVM annotation stubs do not
validate Room SQL.
