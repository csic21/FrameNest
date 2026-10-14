# Recorded source-unit coordinator

`RecordedSourceUnitCoordinator` is a serialized pure-Kotlin component under
`feature/listen_translate/recorded/`. See decision 0033 for the boundary and failure
policy. It uses the existing `RecordedOverlapAlignment`; it does not change that
stitcher's interpretation of frozen fragments.

## Integration

1. Construct with one reused `KuromojiJapaneseMorphology`, generation and fixed-grid
   starting position. Construct/load the dictionary off the UI thread.
2. Inspect `snapshot().nextAction`. Decode the requested 15-second window, or the one
   requested union; ASR receives the full fixed PCM window, never a text-owned suffix.
3. Pass `RecordedWindowInput(RecordedHypothesis(...), measuredQuietSpans)` to `push`
   or `recoverUnion`. Token start estimates must be absolute media milliseconds.
   `RecordedPcmQuietDetector.detect` accepts actual normalized Float PCM and sample rate.
   A caller may use its existing equivalent -35 dBFS / 160 ms detector instead.
4. Persist each `records` event. `SOURCE_READY` is not cache completion. Preserve
   generation/sequence, policy version, source status/reason and `supersedes` identity.
   `pendingSource` additionally exposes retained text, anchors and tentative bounds.
5. For a Translate action, call `translationSucceeded(ticket, nonemptyTarget)` or
   `translationFailed(ticket, reason)`. Only the former for a FINAL source yields
   `completedCoverage`. A failure returns at most one explicit retry ticket; it does
   not block fixed-grid reading indefinitely. The integration may decline uncertain
   source MT because a generative translator can invent completion for fragments.
   Use `deferUnconfirmed(ticket)` for that deliberate decision: it dequeues only a current
   PROVISIONAL/UNCONFIRMED ticket and returns SOURCE_READY with
   `deferredReason = "source_pending_confirmation"`, without an MT error, retry or coverage.
   FINAL and stale tickets are rejected; optional actual MT of uncertain units still works.
6. When decoding reliably reports physical EOF, call `finish(generation, eofMs)` after
   any union is resolved. If backpressured, drain translation work first, then retry
   finish. A one-millisecond PCM rounding difference is tolerated; completion ends at
   the supplied EOF. Player duration alone is not physical-EOF evidence.
7. Reset/recreate with a newer generation on seek/source/track/model/language changes.
   Do not persist old-generation responses. Persist/retry failed I/O before accepting
   the next coordinator mutation, since the coordinator itself is not a database.

`displayStartMs` uses the first retained character's original estimated token onset,
clamped to the source span. `displayEndMs` is a measured quiet point or explicit EOF.
`coverageStartMs/EndMs` are separate tentative source bounds. They become completed
only with the returned successful-MT completion event. None of these is forced word
alignment. Do not use the old stitcher's coarse labels as rendering timestamps.

## Exact standalone tests

A Java runtime and Python 3 are sufficient; no Android SDK or system installation:

```sh
python scripts/experimental/fetch-source-unit-test-tools.py
bash scripts/experimental/run-recorded-source-unit-tests.sh
```

The explicit bootstrap downloads pinned, SHA-256-checked Maven Central test tools to
`/tmp/fn70-kotlin` and `/tmp/fn70-kuromoji`. Test compiler: Kotlin 2.1.20, JUnit 4.13.2;
the app's Android build uses its existing Kotlin/Gradle toolchain. Override
`KOTLIN_TEST_LIB_DIR` / `KUROMOJI_TEST_LIB_DIR` for existing caches. No download occurs
inside the test/CLI runners.

The suite covers the first two unpunctuated Emoji questions, the false terminal
`では。`, uncut `中国語`, noun lists, code-point/raw offset preservation, actual PCM
quiet detection, isolated speech followed by silence, stitch seams, one union recovery,
empty/late union results, provisional rollover, explicit EOF and 1 ms rounding,
source retention over 200 weak windows, bounded queues/retries and stale generations.
These are source-boundary/control-flow checks, not semantic acceptance of a model.

## Real-fixture CLI

```sh
bash scripts/experimental/run-recorded-source-units-cli.sh < captured-input.tsv > units.jsonl
```

Tab-separated protocol (the old stitcher H/T format remains compatible):

- `H generation startMs endMs`: fixed window, followed by T/Q lines and END.
- `X generation startMs endMs`: requested union result, same T/Q/END body.
- `T absoluteEstimatedStartMs base64(UTF-8 original token text)`.
- `Q quietStartMs quietEndMs`: measured PCM quiet interval within that exact window.
- `END`: submit the current hypothesis.
- `EOF generation endMs`: explicit reliable media EOF.
- `FAIL_UNION generation`: one bounded union failed; retain source and resume grid.
- `MT_OK sequence base64(UTF-8 actual translated text)` / `MT_FAIL sequence base64(reason)`.
- `DEFER sequence`: retain uncertain source and release its queue slot without inventing an MT failure.
- `RESET nextGeneration startMs`: discard all current work and old response identity.

Comments begin with `#`. Initial generation is zero. The CLI never runs ASR or MT and
never invents PCM quiet. Missing quiet simply cannot establish a non-EOF acoustic
boundary. JSONL returns source units with FINAL/PROVISIONAL/UNCONFIRMED status, reason,
original text/character anchors, pending text, persistence records, independent read
cursor, next action, and optional completed coverage. An anchor array contains:
`[unitRawStart, unitRawEnd, hypothesisStartMs, hypothesisEndMs, hypothesisRawStart,
hypothesisRawEnd, estimatedTokenStartMs]`.

A real runner should parse each result, drain actual MT tickets or explicitly DEFER
uncertain units, fulfill one DecodeUnion when requested (or FAIL_UNION), and
continue DecodeWindow starts exactly as returned. Review concatenated source and final
translations against the audio; do not treat a source status as a semantic PASS.

## Public-text attribution

The question/list regression text is an adapted machine transcript excerpt from
[Emoji.webm](https://commons.wikimedia.org/wiki/File:Emoji.webm), Simpleshow Japan,
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The tests assign synthetic
token timing/quiet spans to isolate logic. They are not a human reference or a replay
of the original waveform. Original media is not committed. Existing recovered reports
did not contain the full original fixed-window token/PCM-quiet payload; the CLI is the
entry point for independently regenerated real-audio evidence.

## Verified locally, 2026-10-14

- 29 JUnit tests pass on the standalone Kotlin 2.1.20 / Java 21 runtime.
- CLI JSON/Unicode smoke passes: two final question units, retained list/false-period
  tail, then provisional incomplete EOF; no completed coverage without an MT success.
- Pinned test-tool checksum verification passes for all 12 Maven artifacts.
- No Android build, actual public PCM replay, MT generation, phone/tablet UI, or NAS
  measurement was performed by this source-unit change. Those remain integration gates.
