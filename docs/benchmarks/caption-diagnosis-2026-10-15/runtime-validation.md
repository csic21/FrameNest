# Retained FN-75/FN-76 runtime preparation

Status: prepared and **disabled**, with no Android execution or new security
permission granted. Both workflow job and current-UID KVM step are `if: false`.
The input lock pins the verified successful build run 38770936718 at source
54849318ccaaf8788f6cd3cd25d683407f3fec60. The downloaded ZIP and all three
members were hash-checked locally; staging helper passed. Ordinary source CI
reported 896 JVM tests across 155 suites, zero failures/errors/skips, plus
Android assembly, instrumentation compilation, lint and static APK checks.
No device tests have run. Ordinary PR CI 38770944540 also completed successfully.
Historical FN-74 approval does not authorize this new run.

## Owner's remaining inputs

After the final ordinary `caption-diagnosis-build.yml` run succeeds, review and
pin `runtime-input-lock.json`:

- `status`: `pinned-reviewed`
- `repository`: `csic21/FrameNest`
- `run_id`, `artifact_id`, `zip_size_bytes`: positive JSON integers
- `run_attempt`: exactly `1`
- `commit`: full 40-character build commit
- `artifact_name`: `caption-diagnosis-apks-{commit}-1`
- `zip_sha256`, `pair_manifest_sha256`, `app_apk_sha256`, `test_apk_sha256`:
  lowercase SHA-256 of the actual downloaded ZIP, `apk-pair.json`, `app.apk`, and
  `test.apk`, respectively
- `input_lock_is_execution_authorization`: stays `false`

The artifact must contain exactly `app.apk`, `test.apk`, and `apk-pair.json`.
The helper validates the successful ordinary CI run and artifact metadata,
archive digest, manifest source identity, both APK hashes, compiled source-profile
constants, and packaged diagnosis metadata. It does not rebuild or repeat the
native/DEX audit. The Android harness rechecks actual packaged PCM/model/native
members and installed APK hashes.

Only after separate approval for this exact candidate/run may the owner replace
both closed gates with the same exact run-number/attempt-one expression. This
preparation does not dispatch a workflow or grant standing permission.

## Bounded execution scope if separately approved

- One official API35 `google_apis;x86_64` AVD, KVM required, no TCG fallback.
- Save the original `/dev/kvm` ACL; grant `rw` only to the current UID; restore
  the exact saved ACL on exit and in the workflow's always-run cleanup.
- Install `libpulse0` and the previously proven official runtime libraries
  before invoking emulator version checks. No new SDK-license acceptance.
- One retained app/test pair installed once. No Gradle, model preparation,
  alternative models, AAR replacement, or fixture regeneration on this runner.
- Phone viewport: 1080×1920 at 480 dpi (360 dp wide). Tablet-sized viewport:
  1600×2560 at 240 dpi (about 1067 dp wide). Both use the same disposable AVD;
  this is not physical-tablet, arm64, or target-device performance acceptance.
- On each viewport run `ListenTranslateOverlayTest`,
  `ListenTranslateDiagnosticsControlsTest`, and `CaptionDiagnosisFixtureTest`:
  39 terminal events, 35 passes, four explicit Hy-MT-only assumption skips.
  Hy-MT stays false. Both 360/840 dp parameter sets run on each viewport.
- Return to the phone viewport and run `CaptionDiagnosisTest` exactly once with
  `captionDiagnosis=true`, `captionDiagnosisNetwork=true`, and both APK hashes.
  Real ML Kit may download its existing Japanese/Chinese packs. No new ASR model.
- Retain 4 reference→MT rows, 4 raw-span ASR→MT rows, and 16 production-window
  rows from `files/caption-diagnosis.json`. No old source ABBA rerun.

## Evidence and interpretation

The artifact `caption-diagnosis-runtime-{validator_commit}-{attempt}` retains the
exact selected APK pair, lock, validator/host identity, image properties, emulator
log, ACL save/grant/restore records, session exit codes, raw instrumentation,
logcat, display records, screenshots, final memory snapshots, diagnosis JSON, and
`validation-summary.json`. Failures are retained and sibling sessions continue
where possible. A diagnosis retry in the same results directory fails closed.

Every ADB call is bounded. Emulator boot, sessions, workflow, and cleanup are
bounded independently; cancellation can never guarantee cleanup after the host
itself disappears, so the workflow also disables automatic concurrency cancel.

The checker rejects missing/skipped/repeated diagnosis, missing UI tests,
unexpected skips, incomplete rows, different hashes, and unsupported acceptance
claims. Real MT failures are reported as observations rather than converted into
fake translations. `evidence-collected` means structurally complete evidence,
not accurate or usable captions. Semantic acceptance stays null until the actual
reference, recognition, adjacent windows, and translations receive human review.
The published human-corrected reference is not independently listened-to gold.

## Disabled fixed 3s/6s extension (2026-10-16)

The consumed run-1 gates are closed again. The next retained pair must come from
successful ordinary CI that packages `fixed-window-comparison.json`,
`generated/comparison-prepared.json`, and the full pinned `generated/emoji.s16le`.
The existing run-1 APK lock is historical input, not an input for this extension;
its APKs lack the new assets and will fail staging before SDK/KVM access. The
owner must lock the new successful build's actual artifact and hashes.

The runner installs that new pair once and runs phone UI, tablet-sized UI, the
existing three-path diagnosis once, then
`FixedCaptionWindowComparisonTest#compareThreeAndSixSecondWindows` once, with
`fixedCaptionWindowComparison=true`, `captionDiagnosisNetwork=true`, and both
retained APK hashes. Only the two existing fixed audio inputs and the 3000/6000 ms
conditions are allowed: MCDC 16/8 rows, full 113.96-second Emoji 38/19 rows.
No condition sweep, replacement model, old source ABBA, or product-window change.

The comparison has an external 20-minute hard watchdog (1185 seconds to TERM,
15 seconds to KILL), a bounded session allowing evidence collection afterward,
and the existing always-run cleanup/ACL restoration. Its incremental report is
pulled from `files/fixed-caption-window-comparison.json` along with session logs.
Partial, skipped, repeated, or interrupted execution cannot pass collection.

The runtime summary explicitly requests `--fixed-window-comparison`, adding both
the single-test session result and the new collection result. It checks actual
input/preparation/APK/source identities, all 81 ordered core windows, unchanged
750 ms context, full coverage/EOF, actual ASR/MT call records, texts, and timing
fields. Blank-source and failed-translation observations remain in coverage;
collection never implies semantic, word-retention, or performance acceptance.
The old helper's default summary mode remains available for historical run-1
reports; it does not manufacture the new comparison result.
