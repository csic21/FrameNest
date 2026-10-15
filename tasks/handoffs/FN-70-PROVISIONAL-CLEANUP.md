# FN-70 provisional cue cleanup follow-up

- Status: implemented locally; experiment remains off by default.
- Scope: cue selection, repository/DAO commits, adaptive session commits, and focused tests. No ASR segmentation or scheduling changes in this follow-up.
- Defect: a failed attempt left rev=3 source text at 1000–4200 ms. A successful retry at 500–5500 ms could lose overlay priority because the old cue had a later start and larger numeric revision. Equal-range replacement also left stale tails when retry bounds changed.
- Fix: final speech has priority over provisional speech before comparing starts/revisions. A successful owned span atomically removes only rev=3 records whose starts belong to that span, then stores its replacement cue. The entire superseded provisional record is removed, including a stale tail beyond the new end; that tail is not certified as completed. Completed cues, outside provisional records, and other media/language jobs are preserved. Confirmed-silence completion performs the same cleanup; failed/unrecognized work does not.
- Safety: generation validation and coroutine cancellation are rechecked under the repository write lock; existing cache leases remain checked. Session generation changes cannot authorize cleanup or publish a stale final. The in-memory cache applies the same cleanup rule as Room.
- Tests: the existing Kotlin session harness, extended with progress and diagnostics tests, passes 82 tests. The pure JVM harness passes 70 tests. Actual DAO selection/deletion SQL was extracted and executed against in-memory SQLite: final selection, scoped deletion, completed-cue retention, and absence of the old tail passed. `git diff --check` passes.
- Android limit: local Gradle cannot obtain its wrapper distribution because the network is unreachable. Room code generation and Android integration remain for normal Android CI; pure JVM/fake-DAO results do not establish those checks.
- Acceptance cases: changed end and changed start; old provisional tail extending beyond the new final; actual failed-MT disable/re-enable retry; confirmed-silence replacement; completed and other-language cue retention; stale generation and retired lease rejection.

## Deferred real 1 ms gaps

A true 4200–4201 ms gap remains incomplete; it must not be skipped, rounded away, or automatically called silence. The earlier mock scheduler test proves gap bookkeeping only, not that a 16-sample ASR core can recognize anything useful.

No tiny-gap workaround is implemented here. Continuous-context recognition should separate its usable read/core span from the interval it owns and certifies, with an explicit text cursor. A bounded repair spanning neighboring cached audio was considered, while retaining existing completed cues and requiring actual new progress; it is deferred with the timestamp-restart architecture. Completion still requires successful owned text or independently confirmed acoustic silence, never mere adjacency to cached coverage.
