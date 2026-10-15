# Disabled retained source-runtime validation

Preparation only. `.github/workflows/asr-source-runtime.yml` has literal false
gates on both the entire job and the current-UID KVM ACL step. There is no active
run-number grant and no device execution in this change. The input lock remains
pending with null final CI/artifact/hash fields.

## Intended bounded action for review

- One isolated API35 `google_apis;x86_64` AVD using the existing accepted SDK
  license, the same image family/lifecycle as stock run8. No license acceptance,
  additional image architecture, system-wide KVM access, persistent group change
  or published release is requested.
- Temporarily grant only the current hosted-runner UID read/write ACL access to
  `/dev/kvm`; save the complete original ACL first and restore/compare it after
  stopping the owned emulator. Shell exit/interrupt cleanup and an independent
  `always()` workflow cleanup both attempt restoration. Restore-command or diff
  failure cannot create a success marker.
- Two fresh app sessions on that one booted AVD. Each force-stops, uninstalls,
  verifies package removal, rechecks the exact retained APK hashes, reinstalls
  the same x86_64 app/test pair and executes only
  `SenseVoiceRuntimeComparisonTest`. The second process cannot reuse the first
  app's recognizer or private files. Android/host filesystem caches can remain
  warm; this is not a cold-storage benchmark.
- Per session: the frozen 33 windows, three passes each through raw JNI float
  PCM and the real production adapter, then the existing cancellation/reuse/close
  probes. Total: 396 main result rows over both sessions. No MT, player workload,
  VAD rerun, gap coverage or new model is included.
- After ASR, one separate run of the seven
  `com.framenest.ui.screens.SourceAsrLicensesDialogTest` cases checks the notice
  dialog on the same phone-sized AVD/pair. It needs no special args; the real
  asset-reading case assumes the verified source profile. Coverage includes
  visibility, all 29 notice entries, pagination, error navigation, cancellation,
  and reconstruction of the 30 actual pinned notice texts. Results/logcat remain
  separate from ASR. No tablet UI acceptance or legal clearance is claimed.
- Record upstream native `VersionInfo` separately from the SHA-based source AAR
  identity. Compare output rows between these two source sessions after excluding
  only decode wall time. Do not infer a timing improvement from historical stock
  runs on a different hosted environment. No arm64 or 16 KiB runtime claim.

## Resource and failure bounds

The emulator requests 4 GiB RAM, two cores and an 8 GiB data partition. Boot is
bounded to 600 seconds; instrumentation to 420 seconds per session; a complete
session to 900 seconds with a 60-second forced-exit grace period. Routine ADB
commands use 10–120-second TERM bounds and 5-second KILL grace periods.
UI instrumentation is capped at 300 seconds and its session at 600 seconds. The
replay step is capped at 45 minutes and the whole job at 90 minutes, including
artifact download, SDK setup and cleanup. The job ceiling leaves room for cleanup
after step timeout. These are ceilings, not promises that every individual maximum can be reached and still finish. Actual source decode time is
not yet measured. Only one emulator is started; the second app session is still
attempted after a first-session failure, within the same bounds.

Cancellation is not scheduled deliberately. An abrupt hosted-runner loss cannot
be proven to execute shell cleanup; normal failure/cancellation paths retain the
restore command result and exact ACL comparison. Concurrency does not cancel an
active run in favor of a newer one. Unowned emulator PIDs are never killed.

## Final-artifact validation before any KVM step

The packaging owner produces one nine-member ordinary-CI artifact: three app
variants, the prepared instrumentation APK, `source-app-pair.json`, and the four
root evidence JSON files. The disabled consumer accepts that exact format.

Before execution can be considered, independently review the successful final
ordinary source-app CI and fill `runtime-input-lock.json` with its repository,
run ID/attempt, exact commit, artifact ID/name, ZIP size/SHA, pair-manifest SHA and
selected x86_64 app/test SHA values. Pinning those inputs does not authorize KVM.

`prepare_source_runtime_pair.py` checks:

1. Same-repository official API metadata: the exact completed successful ordinary
   source-app workflow run, commit/attempt and non-expired artifact.
2. Exact outer ZIP bytes, canonical bounded member inventory, pair source/profile,
   all APK/evidence sizes and hashes, and the three app variants' static audit.
3. Frozen fixture/profile JSON and preparation status; exact model/tokens/PCM
   assets inside the actual retained test APK, not a newly prepared replacement.
4. Source identity, upstream expected version, Hy-MT disabled and supplemental
   notice verification. An observed upstream JNI version remains unknown until
   the actual instrumentation run.

It stages only the retained selected app/test pair and evidence, atomically after
validation, refusing to overwrite an existing destination. API credentials never
cross into storage download requests and signed URLs are not logged. No Gradle,
AAR compilation, model download or fixture regeneration occurs in the consumer.

Preserved output includes the selected APK pair and checksums, input lock/manifest
and verification JSON, host/image/validator identity, per-session install and
instrumentation output, full/partial recognition JSON, logcat, memory snapshot,
emulator diagnostics, session status, repeatability summary, separate seven-test UI JSON/logcat/output,
and original/granted/
restored ACL evidence. A partial JSON or instrumentation timeout is never accepted
as a passed session even if the last persisted report said `passed`.

## Local preparation checks

- 130 Python tests pass on the isolated preparation branch, including corruption,
  different-model, wrong-run/profile, incomplete evidence, timeout-report and
  output-repeatability cases.
- Cleanup success/failure is exercised only with temporary mocked `sudo`,
  `getfacl` and `adb` executables. No real permission or device command was run.
- Workflow YAML and every inline shell block parse; runner passes `bash -n`.
- Final ordinary-CI artifact verification is pending. No source device outcome,
  runtime duration, KVM execution or ACL change is claimed.

Activation requires a separate owner-reviewed, narrowly scoped one-run change.
The tests accept either both gates closed or identical positive numeric
`github.run_number == N && github.run_attempt == 1` gates. They reject broad,
missing or mismatched gates; recognizing this syntax is not authorization.
