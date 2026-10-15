# 0036: Source-ASR 0.6.10 release candidate (runtime accepted; final signed build pending)

FN-74 prepares version 0.6.10-internal / code 21 on a separate branch after a fresh
read of main `daaff97bc9d32043b4fbc15b420511c8361e7cc2` and published v0.6.9-internal.
No main change, upload, signing, release request or publication is authorized by
this local candidate itself.

The intended release dependency is the exact reviewed source ASR-facing/no-TTS
AAR SHA `6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`.
Release requires explicit source=true, Hy=false, and an approved runtime provenance
record. The record now pins the independently reviewed run 38739397163 and scoped report hash. Development keeps explicit
legacy source=false available and its existing default; stock PR6 is untouched.

The first signed release consumes the existing locked Actions artifact, then publishes
that unchanged AAR plus deterministic full notices and source provenance alongside the
app. A separate fixed-release-URL importer permits later reproduction without an
Actions artifact expiry dependency. There is no automatic fallback or new hash trust.
The only token scope addition is read-only Actions access on the existing Android
build job for the same pinned repository artifact; no token/key or persistent grant.

Model/MT/native bytes, signed-app identity and updater APK schema remain unchanged.
All existing signer/source/ABI/immutability/feed guards remain. The ordinary source
app is statically verified; the exact-source runtime result is independently accepted for the frozen inputs; final signed APK checks
and publication remain separate. No measured speedup or arm64 16KB runtime
acceptance is claimed. See docs/RELEASING.md for the finite build/distribution contract.

Runtime acceptance on 2026-10-15: two source sessions each contain 198 rows identical
to stock 1.13.8 except decode_ms. On the 114-second independently annotated Kishida
reference, Android CER improves from old-runtime 10.78% to 38/436 = 8.72%; this is
not a full-video or general accuracy estimate. Known word regressions remain
(広島さと / 握りこ). No measured source speedup is accepted. The final signed/R8
build and public asset/feed checks remain pending CI.
