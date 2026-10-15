# Adaptive listen interval foundation (opt-in only)

This FN-72 follow-on supplies pure scheduling helpers for an adaptive-span experiment.
It does not change `ListenTranslateSession`, production defaults, model selection,
PCM decoding, cache persistence, or the existing fixed-window completion contract.

## Interface

`ListenCompletedInterval` is a nonnegative, nonempty, half-open media interval.
`ListenCompletedIntervals` provides:

- `union`: sorted, disjoint completed coverage; merges overlap and exact adjacency.
- `firstGap`: first uncovered range within caller-supplied bounds. Stops at the next
  completed future boundary, preventing an adaptive request from consuming that cue.
- `contiguousAheadMs`: exact completed coverage immediately ahead of the playhead,
  clipped to a caller-supplied media/horizon end.
- `fromCues`: conservative existing-record bridge. Requires source and target by
  default. Source-only rendering must explicitly set `requireTranslation = false`.
  Only existing rev=1 blank completion records count; legacy, unrecognized and unknown
  blank revisions do not. Invalid cue ranges are excluded.

Inputs must already belong to one validated content/track/model/language cache.
These helpers do not validate cache identity and do not deduplicate subtitle text.
A display cue proves coverage only for its own bounds. Whole attempted-span coverage
still requires an explicit completion record from a successfully finished pipeline.
Read-ahead PCM, provisional recognition, and failed MT do not establish completion.

Example: completed [0, 4200) + [4200, 7800) resumes at 7800 ms; a 1 ms gap
between intervals remains work. Querying [3000, 12000) with future completion
[6250, 9000) returns only [3000, 6250). The caller must further cap a returned gap
by its maximum recognition budget and media duration.

## Required before production integration

The adaptive result contract must identify actual `processedThroughMs`; validate it
before source publication, clamp cues to the actual span, and commit only completed
coverage. Update source-cache policy identity, generation/seek cancellation, retry
ownership and atomic coverage writes together. No such changes are included here.
Do not wire this helper alone into the current fixed-window Session.

## Verification (2026-10-14)

- Kotlin 2.4.0 standalone compiler compiled the new helper, its actual production
  model/window/UI/diagnostic dependencies, and its JUnit tests without source stubs.
- JUnit 4.13.2: **15 tests passed**, 0 failures.
- `./gradlew testDebugUnitTest --tests '*ListenCompletedIntervalsTest' --offline`:
  default wrapper cache not writable. Repeated with writable
  `GRADLE_USER_HOME=/tmp/fn-adaptive-gradle`; wrapper distribution download failed
  with `java.net.SocketException: Network is unreachable` before Gradle started.
- Android build, lint, device/UI, NAS performance and Japanese quality not validated
  by these pure JVM checks. No production behavior has changed.

## FN-72 handoff

- Status: pure helper/test foundation complete; adaptive product integration deferred.
- Branch/commit: current assigned worktree; no commit created.
- Changed paths: this document, `ListenCompletedIntervals.kt`,
  `ListenCompletedIntervalsTest.kt`.
- Decision: existing accepted default remains unchanged; no new model or service.
- Integration owner changes required: none to retain this as an unconnected experiment.
- Dependable behavior: exact interval union/gap/horizon semantics described above.
