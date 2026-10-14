# Japanese owned-prefix selector experiment

## Scope and status

`JapaneseOwnedPrefixPolicy` is an independent, pure Kotlin experiment. It is not a
Japanese parser, a semantic acceptance gate, a model VAD, or a change to production
Session behavior. No model, default or existing engine file is changed by this work.
There are no fixture vocabulary exceptions. Generic terminal forms and particles
are deliberately incomplete heuristic evidence, not guarantees.

## API / time and ownership contract

`select(tokens, quietRuns, startMs, decodedThroughMs, endOfMedia, ownedLimitMs,
stableTokenEndExclusive)` uses absolute media milliseconds throughout.

- `JapaneseTimedToken(text, startMs)` retains whole input tokens and their order.
  Timestamps are estimates; no inferred token end or midpoint is treated as a word boundary.
- `JapaneseQuietRun(startMs, endMs)` must come from actual PCM analysis. Token gaps
  must not be submitted as measured quiet runs.
- At most 15 seconds may be decoded. Ownership is at most 12 seconds and at most
  `ownedLimitMs`, for example the next already completed future cache boundary.
- Non-EOF candidates need 750 ms of right-context PCM after the cut.
- `stableTokenEndExclusive` optionally limits the owned prefix to the common stable
  prefix of two ASR passes. It counts this input's original tokens, including spaces
  and punctuation. The caller computes it; omission explicitly means unchecked.
  The first unstable token's start also limits media completion. Zero returns null;
  EOF cannot flush an unstable tail.
- Result `tokenEndExclusive` uses the same indexing. `text` contains only that prefix;
  `processedThroughMs` is the next request start. Unowned tokens/read-ahead must not
  become completed coverage. Null means no safe progress, not silence completion.

## Selection order

1. Earliest supported terminal form (including question endings without punctuation)
   paired with a real quiet interval of at least 160 ms. Cut at quiet midpoint.
2. A terminal form may instead use an estimated lexical-token gap of at least 700 ms,
   leaving 200 ms before the next estimated onset. This gets a distinct weaker reason.
   All terminal candidates compete by earliest media boundary, not evidence type.
3. If no terminal candidate, explicit EOF may flush the bounded stable remainder.
4. Otherwise choose the latest eligible real pause, marked **forced**. Known connective
   suffixes and comma continuations are excluded. A noun answer may remain combined
   with a following sentence instead of being treated as a guaranteed complete sentence.
5. Fall back to a delimiter before the cap, marked **forced**; commas following
   hiragana-ending phrases are weak continuation evidence. ASR spaces are not word
   boundaries: they can occur inside Japanese words.
6. Last resort: cut at an estimated token start and leave that entire token unowned.
   Mark **ForcedTokenBoundary**. No general word-boundary or later ASR fidelity guarantee
   is possible from character token starts alone. A native recognizer can still change
   or omit words on a restarted decode; measure this end to end.

Punctuation is never sufficient for a semantic terminal. Late punctuation at the read
edge cannot pull an earlier phrase's endpoint to that punctuation timestamp. Forced
cuts must never be described as semantically final. The engine should emit diagnostic
reason counts and validate withheld tails and restart continuity on independent audio.

## Verification, 2026-10-14

Kotlin 2.4.0 + JUnit 4.13.2 standalone run: **23 tests passed**. Reproduce in the current
workspace using `bash /tmp/run-japanese-owned-prefix-tests.sh`.

Tests cover unpunctuated questions, split Japanese token spacing, weaker token-gap
endings, connective/case-particle exclusions, noun answers and delayed punctuation,
whole-token forced fallback, ownership limit, right guard, EOF, repeats and four
stable-prefix boundaries. These are logic regressions, not semantic accuracy metrics.

A local replay of all tokens and actual -35 dBFS quiet runs in the provided
`emoji-semantic-blocks.json` (no stability bound supplied) produced:

- Block 0: 5,410 ms; first question; acoustic-pause terminal.
- Block 6,000: 9,650 ms; second question; acoustic-pause terminal.
- Block 12,000: 17,080 ms; ends in 言葉です; estimated-token-gap terminal.
  The input starts with 語, already missing preceding characters from 中国語. A selector
  cannot restore that loss. Production experimentation must restart at actual ownership.
- Block 18,000: 28,300 ms; noun answer combined with following sentence ending in
  人気です; acoustic-pause terminal.

Fixture: [Emoji.webm](https://commons.wikimedia.org/wiki/File:Emoji.webm),
Simpleshow Japan, [CC-BY-SA-4.0](https://creativecommons.org/licenses/by-sa/4.0/).
Excerpts are machine transcripts adapted into synthetic tests, not an independent
human-reviewed reference. No new ASR, MT or device execution was performed by this
selector test. Independent Japanese footage and manual semantic review remain required.

## Handoff

- Status: independent policy and JVM logic tests complete; product integration pending.
- Paths: `JapaneseOwnedPrefixPolicy.kt`, `JapaneseOwnedPrefixPolicyTest.kt`, this document.
- No commit created. No existing engine, Session, defaults or dependency files edited.
- Shared integration owner must map ASR timestamps to the absolute timebase, extract
  real quiet runs, supply two-ASR stability bounds, preserve generation/cache leases,
  and advance only to validated actual ownership after successful completion.
- Android build/lint, real NAS/phone timing and semantic acceptance not established.

## Integrated experiment, 2026-10-14

`RealListenTranslateEngine(experimentalJapaneseSegments = true)` now opts into the
adaptive contract **for explicit Japanese only**. Product construction still uses
false. Cache identity includes `ja-owned-prefix-v1`; older clipped text is not reused.

One PCM request reads at most 15 seconds. Two native recognitions share that PCM:
the requested core (at most 12 seconds) and the core plus at most 3 seconds right
context. Both recognitions are included in the ASR stage timer. Exact, language-neutral
normalized character-prefix agreement bounds eligible tokens; punctuation/spacing
is ignored, substitutions are not silently aligned away. Quiet runs come from the
same real PCM (-35 dBFS / 160 ms). The initial policy is a heuristic, not a general
Japanese grammar parser or independently verified stable transcript.

Session resumes at actual processed boundaries, unions completed spans, rejects
source/final span mismatches, and excludes provisional source from completed coverage.
Uncached seeks use a 3-second-stable anchor about 9–12 seconds before the playhead
(for a 12-second budget), processing earlier prefixes until the current cue is covered.
Adaptive overlay selection is strict half-open, so a prior prefix is not displayed
at its exclusive end. Empty decoder output cannot complete coverage. A missing stable
prefix raises a retryable stage error rather than certifying the unread tail.

`ListenMtEngine` separates the existing ML Kit lifecycle from the pipeline for
control-flow testing; the product still constructs ML Kit. `SherpaTokenMapping` is
the same pure mapping previously embedded in SherpaAsrEngine, now shared with the
exact experimental command-line replay. No model, dependency or security default
was replaced.

Local execution: 33 selector/quiet/stability tests, 5 actual RealEngine control-flow
tests with fake ASR/MT/audio (not quality/performance measurements), 54 Session tests
and 69 pure pipeline/window tests passed. Standalone RealEngine checks extract the
unchanged audio/model support interfaces and exception declaration to avoid Android
installer dependencies; Session harness uses annotation-only Room stand-ins. Real
Android compilation and repeated semantic/latency acceptance are still required.
