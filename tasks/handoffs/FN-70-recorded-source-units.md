## FN-70 recorded source-unit handoff

- Status: pure coordinator, morphology adapter, persistence contracts, tests and CLI complete;
  Android integration and real-audio semantic/MT acceptance remain with the integration owner.
- Branch/commit: current shared integration worktree; local commit `Add bounded recorded Japanese source units`.
- Changed paths: new `feature/listen_translate/recorded/**`, matching tests, two Kuromoji
  license assets, source-unit CLI/runners/bootstrap, decision 0033 and source-unit docs.
- Checks: 36 standalone JUnit tests pass; CLI JSON/Unicode/source-status smoke passes;
  all 12 tool JAR checksum verifications pass; no production-default change or remote write.
- Commands:
  - `python scripts/experimental/fetch-source-unit-test-tools.py`
  - `bash scripts/experimental/run-recorded-source-unit-tests.sh`
  - `bash scripts/experimental/run-recorded-source-units-cli.sh < captured-input.tsv`
- Decision: fixed PCM grid with morphology + real quiet source-unit selection; failed
  union retains unconfirmed source and advances reading; coverage only after final MT.
- Remaining issues: morphology is heuristic. Tests include captured actual token/PCM
  evidence but no fresh model execution. Android packaging/startup/memory and fresh
  full-video semantic acceptance remain integration gates. Do not default-enable based only on these tests.
- Shared-file work: app owner adds `implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")`;
  keep packaged license assets. Cache identity includes `RecordedCaptionPolicy.VERSION`.
  Session handles durable records, supersession, provisional/source status and generation.
- Interfaces: `RecordedSourceUnitCoordinator` accepts fixed ASR + measured quiet;
  `RecordedNextAction` is DecodeWindow / DecodeUnion / Translate / Finished. Source units
  expose display bounds, independent tentative coverage, original UTF-16/token anchors,
  status/reason, generation/sequence and supersedes. Never render stitcher coarse labels.
- Integration review reported: persist returned records before advancing after I/O failure;
  handle SUPERSEDED records for provisional revisions instead of retaining stale overlays.

- Follow-up: `deferUnconfirmed(ticket)` / CLI `DEFER sequence` explicitly drains a nonfinal
  ticket with SOURCE_READY + source-pending reason, no fake MT failure, retry or coverage.

- v2 bugfix: stitch/release before the tail time cap; exact raw ownership on forced
  consume and hard character-cap fallback; 250 ms bounded estimated-onset tolerance;
  lexical lookahead across commas; plain-predicate attributive-continuation safeguard.
  Captured Kishida regression preserves 35.57/50.11/64.13/98.22/115.3 s boundaries.
  Actual Emoji `使う 新しい表現スタイルです` stays joined. Missing newly requested union
  captures are explicitly failed; benchmark owner must regenerate them before acceptance.

- Restored v3 ownership correction after workspace rollback: archive only the owned old
  prefix at a hard character cap, retain newer suffix once, preserve the seam anchor.
  Four regressions cover disjoint concatenation, stable text, repeated caps/EOF and union.
  Original local commit was lost; this restoration has no model/morphology changes.
- Revalidated after restoration: `bash scripts/experimental/run-recorded-source-unit-tests.sh`
  passes 39 tests; `git diff --check` passes. Android/real model quality not rerun.

- Temporary bilingual restoration: adapter translates bounded whole PROVISIONAL units;
  UNCONFIRMED still DEFER. Session persists nonfinal source only and keeps target text in
  eight bounded memory entries, with generation/seek/exit/expiry/supersession cleanup.
  Final-only completed coverage is unchanged. No model, prompt or morphology edits.
- Restored standalone Session runner is now committed rather than relying on /tmp files;
  bootstrap pins the matching coroutines-test JAR. Added seven Session regressions for
  no durable target/coverage, next-session isolation, eviction/seek, replacement/final
  completion, late callbacks and rejection of legacy persisted provisional targets.
- Remaining quality risk: earlier real provisional MT had a factual error; this candidate
  is not a semantic PASS. Actual full-video/phone/NAS quality was not rerun after rollback.

- Final restored checks: 39 source-unit tests, 16 adapter tests and 141 Session/Repository/
  scheduling tests pass. New engine-replacement late-callback and real two-thread
  select-before-seek regressions pass. UI selection/publication and temporary-memory
  clearing share an atomic lock; writes recheck the volatile work generation.
- Commands: `bash scripts/experimental/run-recorded-source-unit-tests.sh`,
  `bash scripts/experimental/run-recorded-adapter-tests.sh`,
  `bash scripts/experimental/run-recorded-session-tests.sh`, and `git diff --check`.
