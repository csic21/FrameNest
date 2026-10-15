# One bounded source-runtime software-emulation probe

This separate ordinary-CI probe consumes the already retained app/test pair from
`runtime-input-lock.json`. It never builds APKs, native libraries, models or PCM.
Only run 2, attempt 1 of `asr-source-software.yml`, on its exact source-acceptance
branch and listed path changes, can execute. This is the bounded setup retry for
run 1, which failed before boot while loading the emulator version command. A failure ends this
probe; changing time limits, retrying, or enabling acceleration needs a new decision.
The accelerated workflow remains disabled.

The official API 35 Google APIs x86_64 image runs once with `-accel off`, two emulated
cores, 4096 MiB RAM, no snapshots and an empty app/data installation. The route has
no device-acceleration access or permission commands. Existing SDK licenses are
required; the workflow does not accept new ones.

Boot polling is limited to 900 seconds (1080-second setup/boot wrapper). Each ASR
instrumentation call is limited to 1200 seconds (1500-second installation/session
wrapper). Any boot, first-session, or identity/report validation failure stops before
the second session or UI suite. Only a successful first session permits a second
fresh install/process using the same exact pair, followed on success by the seven
`SourceAsrLicensesDialogTest` cases (300-second instrumentation/600-second wrapper).
The replay process has a 61-minute ceiling plus a 60-second termination grace
inside a 63-minute step. All step ceilings sum to 88 minutes, including one minute
for cleanup and four for artifact upload, within the 90-minute job limit. This
global limit may end later phases before every individual phase maximum is consumed.

Every session checks installed APK SHA-256, JNI version 1.13.8, distinct source AAR
identity/hash, frozen fixture hash, 33 windows and 198 main raw/production rows,
bit-identical production PCM, lifecycle balances and explicit acceptance limits.
The source-profile preflight also requires Hy MT disabled. The same OS/AVD may keep
caches warm across fresh app installs. All timings describe software-emulation cost
only; they are not product performance or a comparison with historical stock runs.
No accuracy, release, tablet, arm64, 16 KB device or translation acceptance follows.

The artifact retains exact selected APKs/input lock and verification, host/image
identity, emulator output, bounded logcat, instrumentation output, partial/full raw
JSON, per-session status and checks, separate UI result, and final probe status.
Only the emulator PID recorded by this run is stopped. Offline tests replace all
launches with mocks and verify failure short-circuiting, exact-identity rejection,
explicit software timing labels and absence of acceleration/permission commands.

## Run 1 setup failure

Run [38731905439](https://github.com/csic21/FrameNest/actions/runs/38731905439)
failed before boot: `emulator -version` ran before the runtime-library installation
step and the loader could not find `libpulse.so.0` (exit 127). The retained input
pair was already verified, but no AVD/ASR/UI session ran. Evidence is in
`software-run1/failure.json` and the preserved `emulator-version.txt`; the original
artifact archive SHA-256 is recorded there. The narrow retry moves the version
command after the existing library installation in both workflows. APK pins,
launch flags, time limits and the disabled accelerated gates are unchanged.
