# Independent source no-TTS exact-binary review

Two real Android sessions validated against the frozen manifest and prior stock1.13.8 results. Each session198 rows (raw/production ×3passes ×33windows). All row fields except decode_ms exactly equal stock1.13.8 session2 in run8, including tokens, raw relative/absolute timestamps, durations, language/emotion/event, production words and text. Two source sessions also agree. No changed-word review is needed beyond preserving existing stock regressions.

Kishida114 CER38/436=8.7156% in every pass/backend/session; Emoji/fullKishida have no complete independent gold score. Prior stock improvements and regression examples remain:逮捕/民主主義/妨げられる improved versus1.12.32, while広島サミット→広島さと and握り拳→握りこ persist. This proves observed equivalence for these exact inputs, not universal source/stock equivalence or full subtitle accuracy.33windows include7 repeated Kishida windows and are not independent examples.

Identity checked: manifest9231712ec85ec7a16b31000f3cb960f2a15b7e3ccc28758515452a2351f66f77; native1.13.8/git8c8e275d; appAPK d1966647e9cf6bfe7bede04fadb4f74f11a7f40143a288211f37f9cf37160d64; sourceAAR6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503. Pair-manifest hash verified against input lock, source-profile hash against pair manifest, reported installed APK hash against lock. Installed hash-check logs say app/test OK. APK bytes are not included in this validation artifact, so this independent review does not rehash APK binaries.

APK source6216ecc3ad6cfe7a0cbb2100140ff4a7f9c788d0; validator95a08e2f6ba8cd1c630fe926743b4c61b3e50ba1; validationrun38739397163/job116436847992/artifact11976112287. ZIP SHA c582c3564ffafbae9051b5fad29081fbd744a0c370e9a06d94415971f74b690b was verified by engineering owner before extraction. TargetAPI35 x86_64 Google sdk_gphone64_x86_64; no ARM64/OPPO test.

Both sessions raw resources balanced1recognizer/99streams; production102recognize,3ensure,3close; close/reload equality passes. Caller cancellation publication suppressed, cancel/join1858.567094ms and1822.260858ms; native interruption unproven. No bounded cancellation/player-seek claim. No cross-environment speedup inference.

UI result says7/7 notice-navigation/asset-read tests passed on phone-sized AVD; this is separate from ASR evidence and not tablet or legal clearance.

Local independent-comparison.json retains all per-sample/backend/pass summaries and zero-difference comparisons. No new inference/tests, uploads, or app/repository changes performed by this review. Public fixture attribution is unchanged from the frozen manifest and previous ACTUAL-REVIEW.md.
