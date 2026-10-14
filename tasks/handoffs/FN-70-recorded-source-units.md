## FN-70 recorded source-unit handoff

- Status: pure coordinator, morphology adapter, persistence contracts, tests and CLI complete;
  Android integration and real-audio semantic/MT acceptance remain with the integration owner.
- Branch/commit: current shared integration worktree; local commit `Add bounded recorded Japanese source units`.
- Changed paths: new `feature/listen_translate/recorded/**`, matching tests, two Kuromoji
  license assets, source-unit CLI/runners/bootstrap, decision 0033 and source-unit docs.
- Checks: 29 standalone JUnit tests pass; CLI JSON/Unicode/source-status smoke passes;
  all 12 tool JAR checksum verifications pass; no production-default change or remote write.
- Commands:
  - `python scripts/experimental/fetch-source-unit-test-tools.py`
  - `bash scripts/experimental/run-recorded-source-unit-tests.sh`
  - `bash scripts/experimental/run-recorded-source-units-cli.sh < captured-input.tsv`
- Decision: fixed PCM grid with morphology + real quiet source-unit selection; failed
  union retains unconfirmed source and advances reading; coverage only after final MT.
- Remaining issues: morphology is heuristic; acoustic time tests are synthetic until
  actual fixtures are supplied. Android packaging/startup/memory and semantic acceptance
  remain unverified. Do not default-enable based only on these tests.
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
