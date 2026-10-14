# Fixed-window recorded transcript stitching (independent experiment)

## Scope

This pure Kotlin helper accepts fixed overlapping audio hypotheses; it never calls a
model or selects an audio restart from token PTS. Production defaults, RealEngine,
Session, MT and cache coverage are unchanged by these files.

**`RecordedFrozenText` / JSON `frozenText` means a frozen transcript fragment, not a
sentence or an MT unit. It may end inside a word. Concatenate fragments to evaluate
continuous transcription; do not translate each fragment separately.** Later MT units
must be assembled independently from continuous frozen text plus available pending
context. The `coarseLabelStartMs` / `coarseLabelEndMs` fields are grid labels, not rendering
timestamps, semantic boundaries, actual word ownership or bilingual readiness. A frozen
fragment can contain words spoken after its grid label; do not render directly from it.

## Bounded mechanics

The caller supplies 15-second windows on a 9-second grid (6 seconds overlap). Actual
EOF can shorten the last window. Tokens retain original text (including punctuation,
spaces and supplementary Unicode) and absolute estimated start times.

- One pending hypothesis; a new hypothesis must advance while overlapping its audio.
- Normal input at most 15 seconds; optional externally generated union input at most
  24 seconds. At most 4,096 tokens / 16,384 UTF-16 units per hypothesis.
- Align only text near the actual audio overlap, with 1,500 ms timing slack; no more
  than 512 normalized code points on either side.
- NFKC normalization per original code point retains raw UTF-16 start/end mappings.
  Letters, numbers and combining marks anchor alignment; punctuation/spacing/symbols
  are retained in raw output but do not anchor it. This per-code-point normalization
  does not equate every possible multi-code-point canonical spelling.
- Bounded local edit alignment permits initial disagreement and internal edits. Initial
  experimental scores: match +3; substitution/gap -2; at least 8 matched code points;
  edits no more than one quarter of matches. Matched positions require temporal
  consistency within 1,500 ms. This is a heuristic, not timestamp ground truth.
- Unique contiguous anchors must supply at least two four-character units. Exact ties,
  near-tied paths with conflicting mappings, periodic long phrases and missing distinctive
  subanchors are rejected. Candidate-path exploration is capped at 256.
- A raw character seam within a shared anchor freezes the old prefix and retains the
  new suffix as pending. The seam never splits a surrogate or normalized expansion.
  It is a text seam only; the audio grid does not change.

Each splice reports old retained/replaced raw ranges, omitted new prefix, pending start,
match/edit counts, and a bounded list of original character correspondence ranges.
Retain the input hypotheses alongside these events for independent audit. This proves
which recognizer text was used/replaced, not that the audio was transcribed correctly.

## Conflict, reset, EOF

A conflict returns no frozen text, keeps pending text, and issues at most one union
request if the union fits 24 seconds. Later pushes remain explicitly unresolved rather
than repeating requests or silently discarding text. No real decode is performed here.

`replacePendingWithExpansion` accepts externally produced union text. Before any text
has been frozen, the whole uncommitted hypothesis can be replaced. At later boundaries,
the helper retains the actual last frozen fragment's source and raw range as an immutable
anchor (not the newer hypothesis's presumed equivalent prefix). It searches for a unique,
time-consistent complete normalized suffix of 32/24/16/12/8 characters in the union. The
search and target are bounded to 512 characters; original trailing punctuation/spacing
must agree exactly, and at least one right-side lexical character must remain. Ambiguous,
missing, mistimed, too-short or raw-inconsistent anchors keep the conflict unresolved.

Successful recovery changes only pending text and its raw cursor; previously emitted
frozen fragments never change. `recoveryAudit` records old frozen-source and replacement
raw ranges, matched length and new pending cursor. The abandoned hypothesis is returned
for audit. No extra model request is initiated. This conservative recovery can still fail
on genuine ASR changes and is not an algorithmic proof of transcription accuracy.

Generation reset returns the abandoned pending tail for audit and rejects late results.
Explicit EOF flushes a conflict-free pending tail once. Repeated EOF is idempotent.
An unresolved boundary cannot be turned into successful EOF or completed audio coverage.

## CLI

`bash scripts/experimental/run-recorded-stitcher-cli.sh`

The script uses existing compiler jars from `KOTLIN_TEST_LIB_DIR` (default current
workspace cache `/tmp/fn70-kotlin`); it does not install or download dependencies.
Input is tab-separated lines:

- `H <generation> <startMs> <endMs>` starts a normal hypothesis.
- `T <absoluteStartMs> <base64 UTF-8 token text>` adds one original token.
- `END` submits the hypothesis.
- `X <generation> <startMs> <endMs>` starts an external union replacement, followed by
  the same T/END lines; this does not invoke ASR.
- `RESET <newGeneration>` resets state; generations must increase.
- `EOF <generation>` is an explicit reliable end-of-media signal.

Initial generation is 0. Output JSONL separates `frozenText`, `pending`, `conflict`,
`expansion`, `audit`, `eof`, and `abandonedPendingText`. Raw offsets refer to the exact
concatenation of input tokens, not a separately normalized ASR text string.

## Verification, 2026-10-14

- Kotlin 2.4.0 / JUnit 4.13.2: **25 tests passed**.
- Reproduce: `bash scripts/experimental/run-recorded-stitcher-tests.sh`.
- CLI subprocess smoke passed: JSON parsing, UTF-8/base64, continuous reconstruction,
  raw character audit mappings and duplicate EOF suppression.
- Cases include first-character substitution, internal edits, repeated phrases,
  non-overlapping/time-inconsistent text, normalization/raw mapping, bounds, generation,
  one-time expansion request and explicit unresolved EOF.
- No model execution, Android build, NAS performance or semantic/translation accuracy
  is established by these tests. Real fixed-window fixture runs are a separate experiment.

## Handoff

Independent helper, tests, CLI and runners only; no default, model or Session changes.
Downstream must not equate frozen transcript fragments with final bilingual subtitles.
Anchored later-boundary recovery is implemented; independent MT unit assembly and
full-length multi-speaker audio evaluation remain separate work.

A read-only replay of the supplied Kishida fixed-window protocol plus the already
recorded 117–141 second union hypothesis successfully recovered its later conflict:
24 normalized characters anchored immutable prior output to raw offset 40 of the union,
without changing emitted text or invoking ASR. This is one concrete recovery example,
not full-media completion or semantic acceptance. The continuation experiment is owned
by the integration task. Fixture source/attribution is recorded in the benchmark report.
