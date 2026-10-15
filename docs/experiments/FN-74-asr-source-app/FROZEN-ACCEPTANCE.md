# Frozen source candidate and finite remaining steps

Status at 2026-10-15 12:35 UTC: ordinary CI and independent package checks passed; source Android execution remains unaccepted. Stable 0.6.9 is unchanged. Do not merge or release this experimental profile yet.

## Frozen executable inputs

- App/test build commit: `6216ecc3ad6cfe7a0cbb2100140ff4a7f9c788d0`.
- Successful ordinary run: [38728412567](https://github.com/csic21/FrameNest/actions/runs/38728412567), attempt 1.
- Artifact 11969082716, 482671323 bytes, SHA256 `052799959778364fc98d44a1acd966648400cf6f1270e0a44189acb4577470c2`.
- Source AAR SHA256 `6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`.
- Selected x86 app SHA256 `d1966647e9cf6bfe7bede04fadb4f74f11a7f40143a288211f37f9cf37160d64`.
- Test APK SHA256 `05e5e76f6f2b602942b6f97aa911b588ca4d826d279f9d2c752c4b1371788775`.
- The lock and stager independently verify all members, manifests, native bytes, and original public PCM/model inputs. Runtime execution must consume this pair without rebuilding.

## Next bounded runtime action

The accelerated workflow remains disabled. A separate explicit permission decision is pending. If authorized, use one API35 x86_64 AVD, two fresh source app sessions, and the seven notice UI tests. The action temporarily grants only the current runner UID read/write access to `/dev/kvm`, saves the original ACL, and restores/checks it at completion. No new models, image licenses, public release, or user computer changes are part of that action.

The unaccelerated probe reached its 900-second boot deadline; later phases did not run. See `../../benchmarks/sherpa-source-app-2026-10-15/software-run2/`. Do not retry software tuning.

## Remaining delivery decisions

1. Obtain real source-variant recognition, lifecycle and notice UI results. Keep its identity separate from upstream stock1.13.8. Stock's Android reference CER improvement and roughly3% slower production RTF are not source-variant measurements.
2. Review source output changes against the retained stock evidence. Do not require zero ASR errors or claim speed improvement. Preserve meaningful regressions and known limits.
3. Explicitly assess arm64 16KB support before any compatibility claim. All LOAD/ZIP checks passed; retained vendor ORT has the documented RELRO-end static warning. No overlap with the next writable segment was observed, but actual arm64 16KB loading is untested. The x86 run cannot close this boundary. Do not invent a demonstrated crash or silently waive the warning.
4. If accepted for a future0.6.10 candidate, make the exact source dependency durably retrievable with notices and source links, choose the production dependency/profile deliberately, and run the usual exact-source build/signature/version/update-feed gates. Current CI artifact storage expires2026-10-29; it is validation storage, not a permanent public dependency distribution path. The current app default remains stable1.12.32 and version0.6.9.

The Eigen source link and supplemental change notice are packaged and user-readable. No concrete missing/incompatible ORT notice has been identified; reconstructing all vendor build settings is not an added release requirement. No additional model search or unbounded audit is planned. The original fast bilingual-caption goal remains open.
