# 0.6.10 public release verification

Release: https://github.com/csic21/FrameNest/releases/tag/v0.6.10-internal

Source: `5a16f1cc4dc7875dd1237c898e795007dc461e94`; release request: `d8cef219218989d6ec1994728667de04a1af9f3f`.

Publish workflow https://github.com/csic21/FrameNest/actions/runs/38745096573 completed successfully, including signed R8 builds, apksigner, exact source native checks, zipalign and public feed verification. All three public APKs were independently downloaded in full and SHA-256/size checked against the public manifest. Their embedded v2 signing certificate DER hashes match the established certificate pin. This independent certificate extraction is not a second cryptographic signature verifier; actual signatures were verified by Android Build Tools in the publication job.

The independent static audit passed for all three downloaded APKs: exact reviewed native bytes, 48 sherpa classes, original notices/supplement and stored native ZIP/PT_LOAD alignment. No Hy library or GGUF is present. The public AAR and complete NOTICE are separately pinned by the manifest. No new native bytes were built for this release.

Runtime evidence remains the two exact-source Android sessions documented in `docs/benchmarks/sherpa-source-runtime-2026-10-15/`. This release does not establish an OPPO or NAS benchmark, a speedup, full-video accuracy improvement, immediate native cancellation, or arm64 16KB runtime compatibility. Known word regressions and the unrounded arm64 ORT RELRO-end warning remain disclosed.
