# Source ASR app: ordinary build and exact APK static acceptance

[Successful CI](https://github.com/csic21/FrameNest/actions/runs/38728412567), source `6216ecc3ad6cfe7a0cbb2100140ff4a7f9c788d0`, run attempt 1. The independent source profile is compiled and statically verified, and the exact debug app/test binaries are retained.

- 891 JVM tests in 154 suites passed. Instrumentation compilation and 101 JUnit method signatures in 35 classes passed. Lint: 0 errors, 68 warnings, 5 hints. No device test ran.
- arm64, x86_64 and universal APKs contain the exact reviewed JNI/ORT bytes from AAR SHA `6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`. All 18 distinct app native ELF LOAD alignments and stored ZIP offsets pass static 16KB checks. Official `zipalign` and all four APK signatures pass.
- Each app has the 48 retained sherpa classes plus the separately structurally checked empty AGP R holder; all 29 original notices, supplemental text and provenance assets are exact (37 assets total). No TTS/GeneratedAudio/diarization wrappers are present. JNI still exports 117 methods/ABI against 32 packaged native declarations, leaving 85 additional compiled exports.
- The actual compiled app BuildConfig verifies the source SHA/cache identity and Hy-MT disabled; expected JNI version remains plain `1.13.8`. Native VersionInfo has not been observed yet.
- The retained test APK contains the exact frozen public fixture/model bytes, 7 assets and 33 windows. Model/MT and stock branches were not changed.

Both downloaded archives were independently verified against official run/head/artifact metadata, lengths and SHA-256. APK artifact paths, duplicate rejection and the exact nine-member fullset passed; every member hash matched its pair manifest, which is byte-identical to the evidence copy. A second local audit of the three real app APKs matched CI apart from filesystem paths. The actual test assets and retained compiled app class/constants were independently rechecked.

## Exact retained pair

[APK artifact](https://github.com/csic21/FrameNest/actions/runs/38728412567/artifacts/11969082716): 482,671,323 bytes, SHA `052799959778364fc98d44a1acd966648400cf6f1270e0a44189acb4577470c2`. The canonical member layout and exact APK hashes are in `source-app-pair.json`. Future runtime work must install that retained x86_64 app/test pair, not a rebuild.

[Evidence artifact](https://github.com/csic21/FrameNest/actions/runs/38728412567/artifacts/11969237436): 215,451 bytes, SHA `de3a664c332181dfc20c9ef48af76f33794d927e655da5223df631a0ef71b952`. It includes raw logs, all JVM XML, lint output, official ZIP/signature checks and the actual compiled BuildConfig class. Artifact expiry is October 29, 2026; do not silently substitute later runs if unavailable.

The preceding failed runs are preserved separately. This ordinary job used no emulator/KVM and did not install or release an app. Remaining work is device ASR/lifecycle/UI acceptance, same-binary performance/quality comparison and real arm64 16KB acceptance. arm64 ORT's unrounded RELRO-end static warning remains, with no demonstrated runtime failure. Static tests and stock-runtime ABBA results cannot establish these properties for this binary. This report does not assert complete legal clearance, vendor-build reproducibility or transitive SBOM completeness.
