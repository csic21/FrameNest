# Ordinary native-only CI: exploratory, not a release

This extension runs the existing pinned source recipe in `.github/workflows/asr-native-source.yml`. It does not execute Gradle, build/publish an AAR, change FrameNest's app dependency, run an emulator/KVM, change security settings, sign artifacts, or accept SDK agreements. The original stock runtime A/B remains a separate experiment.

## Trigger and permissions

- Pushes only on `experiment/FN-74-asr-only-recipe`, and only when this recipe or its dedicated workflow changes.
- Pull requests targeting `main` with the same path filter. Opening such a PR also starts the repository's existing unfiltered `ci.yml` PR workflow, including its Gradle jobs. For the first native-only run, use the dedicated experiment-branch push instead; this change does not alter `ci.yml`.
- Manual workflow dispatch when GitHub makes this workflow available on the selected ref. A new workflow need not be dispatchable until it exists on the default branch; the dedicated branch-push trigger supports the first run.
- No generic `agent/**` push trigger. An unrelated stock PR such as PR 6 does not match these recipe paths and therefore does not start this workflow.
- `contents: read` only. Checkout and upload-artifact reuse the exact action SHAs already present in `ci.yml`; checkout does not persist credentials. No repository secrets, write token, OIDC, custom runner privileges or signing keys are needed.
- GitHub Actions must be enabled and the pinned official actions permitted. The runner needs outgoing HTTPS access to official GitHub, GitLab and Google archive URLs, and normal artifact storage allowance.

## Fixed tool provenance and actual notices

The official Ubuntu 24.04 image inventory was inspected at runner-images commit `573fa3ad959907712ce8bf6a5f91ab82b03e7f2d`, image `20261004.327.1`. It includes CMake 3.31.6, but Ninja 1.13.2 and NDK 27.3/29/30, not the recipe's NDK r28c or Ninja 1.12.1. Default PATH/ANDROID_NDK must not silently select those versions.

`toolchain-lock.json` records exact official archive URLs, sizes, SHA-256s and metadata sources:

| Tool | Official archive bytes | SHA-256 |
|---|---:|---|
| NDK r28c, 28.2.13676358 | 722,261,334 | `dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f` |
| CMake 3.31.6 Linux x86_64 | 55,010,959 | `5a1133ff103c71eb5120e2cc3de922733e7d8a26a98ae716397e8676adb367bf` |
| Ninja 1.12.1 Linux | 123,084 | `6f98805688d19672bd699fbbfa2c2cf0fc054ac3df1f0e6a47664d963d530255` |

All three complete official archives were downloaded and hashed during preparation. NDK additionally matches the publisher's release SHA-1 and byte size; the recorded SHA-256 is independently computed from those official bytes, not falsely attributed to a publisher SHA-256 list. CMake matches the publisher's SHA-256 file. Ninja's release API did not supply a digest; its SHA-256 was computed directly from the official release ZIP. The archive caches are not committed.

The workflow records installed tool versions but extracts fresh, checksum-verified official distributions under RUNNER_TEMP. This pins CMake's accompanying modules too, rather than trusting only an installed executable version string. ZIP extraction preserves NDK's internal clang symlinks and executable modes while rejecting path escapes, special files and duplicate entries. It does not alter system installations or device permissions.

The exact NDK distribution supplies four original notices: root NOTICE, root NOTICE.toolchain, LLVM prebuilt NOTICE, and sysroot NOTICE. Their sizes/hashes, plus the deterministic combined notice hash, are locked. Both originals and combined text are uploaded with the evidence. The short Apache notice from the NDK source repository would not substitute for these files. These broad aggregate notices include build-tool licenses, including GPL text; their presence is not itself a conclusion about the generated app's obligations. Static C++ runtime coverage and final license interpretation still require review.

The runner must already have a nonempty `ANDROID_HOME/licenses/android-sdk-license`. Its checksum is recorded, but this workflow never writes/accepts a license or runs `sdkmanager --licenses`. If the record is absent or an agreement needs explicit acceptance, preparation fails and must be escalated, not bypassed.

## Honest hosted-image provenance

GitHub-hosted `ubuntu-24.04` is a rolling VM image, not an immutable OCI digest. The workflow reads `/imagegeneration/imagedata.json`, keeps the exact installed "Runner Image" detail, and records ImageOS/ImageVersion. It extracts actual release and included-software URLs from that file. Missing or inconsistent metadata causes failure instead of an invented digest.

`build_native.py --exploratory-host-provenance ...` is a separate, explicit mode. It records `immutable_host_image_digest: null`, `reproducible: false` and `release_gate: closed`. The original `--host-image-digest` mode remains available for a genuinely pinned environment, and neither mode claims reproducibility before independent clean builds compare. This native CI mode does not relax AAR packaging's future Gradle checksum/reproducibility gate.

Official schema evidence: [image-data generation](https://github.com/actions/runner-images/blob/573fa3ad959907712ce8bf6a5f91ab82b03e7f2d/images/ubuntu/scripts/build/configure-image-data.sh), [environment version fields](https://github.com/actions/runner-images/blob/573fa3ad959907712ce8bf6a5f91ab82b03e7f2d/images/ubuntu/scripts/build/configure-environment.sh), [image inventory](https://github.com/actions/runner-images/blob/573fa3ad959907712ce8bf6a5f91ab82b03e7f2d/images/ubuntu/Ubuntu2404-Readme.md).

## Build and retained evidence

A single ordinary runner builds arm64-v8a then x86_64 with two compiler jobs to limit memory. The same ten native input archive pins and TTS-off/JNI-on options remain in force. Native FetchContent is disconnected and every selected dependency has a local verified source override. The workflow does not claim OS-level network isolation; no security/network setting is changed.

The static checks inspect compile inputs, link maps, native exports, required SO inventory, ELF architecture, PT_LOAD and PT_GNU_RELRO. Successful native compilation is not proof that an Android process can load the result. The prebuilt ORT arm64 RELRO static warning remains visible; it is not promoted into a claimed runtime failure or silently waived.

An always-run collector uploads the two SOs per ABI when present, output hashes, CMake cache/options/compile commands, configure/build logs, JNI map/symbols, host/toolchain provenance and notices. It also preserves the configured simple-sentencepiece files modified by upstream Android CMake. It excludes downloaded archives, models, object files and full temporary source/build trees. Failure evidence is retained even if configure/compile fails. The artifact manifest expressly says no AAR, Android runtime acceptance, reproducibility proof or legal clearance.

## Local preparation validation and remaining gates

Local tests cover provenance mode separation, missing image metadata, unsafe tool ZIPs, internal compiler symlink/mode preservation, refusal without an existing SDK license, missing/tampered notice rejection, and failure-artifact filtering. The earlier exact JNI/ABI, XML trust exclusion and duplicate-ZIP tests remain required. These fixtures do not compile or execute an Android binary.

Before claiming this source-built runtime is usable, obtain a successful run for the exact commit, inspect its native outputs and remaining warnings, complete license/SBOM/source-availability review, prepare the independently reviewed Gradle graph and AAR, compile the app against its narrowed API, and perform separate Android 4 KB/16 KB and functional/latency/accuracy acceptance. No stock A/B result transfers automatically.
