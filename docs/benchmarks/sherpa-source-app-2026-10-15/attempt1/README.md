# Source-ASR app: first ordinary CI attempt

Run [38723292529](https://github.com/csic21/FrameNest/actions/runs/38723292529),
exact source `b576107f9c170cb9aa416e256def3c10d9841d01`.

Actual `assembleDebug`, all 881 JVM unit tests (153 suites),
`assembleDebugAndroidTest` compilation and `lintDebug` succeeded. No device was
started. The pinned source AAR imported successfully and its hash remained fixed.

The final APK checker then failed closed on one additional class definition:
`Lcom/k2fsa/sherpa/onnx/R;`. The empty AAR resource namespace produces this AGP
resource holder; it was absent from the original Kotlin classes.jar whitelist.
The repair must permit only this exact generated resource holder with structural
checks, not arbitrary extra classes or TTS wrappers. The existing 48-class AAR
whitelist remains unchanged.

Because the checker failed, external ZIP alignment and qualified-APK retention
were not run. This attempt does not establish final APK static acceptance or
native execution. No APK from this attempt was retained as an accepted candidate.
The evidence ZIP (165 entries) was independently downloaded and its exact server
SHA-256 verified; the compact JSON files here preserve the result and provenance.
Original XML/log/lint evidence remains in artifact `11966894268` (14-day retention).

Next candidate also includes the separately reviewed supplementary notice and
source-profile-only reader, with its own ordinary CI and exact APK hashes.
