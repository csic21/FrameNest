# Android native acceptance: failed, not release approval

Source: `5be20a367674624d840b9228c4f984c5d85f643d`.
Validation-only PR5 isolates the Actions concurrency group from delayed PR4 events;
the application and workflow source are unchanged.

- [CI105](https://github.com/csic21/FrameNest/actions/runs/38677680881): passed compilation, JVM tests, lint, both native ABIs and 16 KiB checks.
- [Runtime21, attempt1](https://github.com/csic21/FrameNest/actions/runs/38677681072): failed both Hy tests; startup, existing PCM/SenseVoice/ML Kit path and ten phone/tablet overlay tests passed.
- Artifact11947997340 ZIP SHA256: `7cb470d4cb7624ca792ba1aad9905571d0c04fae99409b5ffd7b6a5c2db90086`.
- API35 x86_64 emulator, two CPUs, KVM enabled. The owner-approved current-UID ACL grant and restoration both succeeded. This is not an OPPO/NAS benchmark.

## Decisive native result

Verified 1,133,080,448-byte official Hy Q4 model downloaded/installed in148,526ms;
native load took1,535ms. The first authored control exceeded180,000ms, so the
dedicated cancellation/reuse assertions were not reached.

The separate real recorded-file test produced the first two correct question
translations but took268,871ms and345,764ms respectively. The longer language-list
translation took771,303ms and retained the previously documented relationship
error. First finalized bilingual callback was279,513ms from test entry
(271,391ms after the prepared producer clock). The test exceeded its30-minute
limit after reading through51s and finalizing coverage only through28.3s.
Increasing timeouts would not satisfy fast-caption acceptance.

PCM extraction for15s took664ms and ASR1,348ms on the first window; native MT is
the measured bottleneck. Build optimization and portable CPU-kernel choices
require diagnosis; these results alone do not prove a specific root cause.

Android total PSS snapshots were1,400,503KiB after Hy load and1,713,149KiB with
both models. After close they fell to229,347/347,329KiB in the respective tests.
These are snapshots, not peak memory or phone-budget certification.

## Existing engine and UI

ML Kit path first-install bilingual eligibility60,533ms; downloaded-model fresh
session4,010ms. Same-run warm scheduling comparison median serial744.5ms versus
overlap718ms (four samples each). This small, noisy schedule comparison is not
an old-APK, cold-download, phone, NAS or physical-rendering speedup claim.
Semantic review remains separate from functional assertions.

All ten overlay tests passed at360dp and840dp, including source/target visibility,
full-text expansion, retained snapshot, reset/exit clearing and locked controls.

## Provenance and limits

The accompanying fixture license identifies Simpleshow Japan's public Emoji
video, its fixed SHA256, CC-BY-SA4.0 attribution and machine-derived excerpts.
There is no user NAS text or private media in these files. Authored controls are
test content. Partial reports intentionally remain marked failure; no final
coverage, semantic acceptance, cancellation acceptance or release success is
inferred from unfinished tests. Keep the new native path unaccepted until a
targeted correction is measured.
