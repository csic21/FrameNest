# FN-74 actual Android runtime ABBA review

## Decision

The real Android JNI/production-adapter test reproduces the direction of the Python runtime improvement on the scored Kishida excerpt, but does not establish an across-the-board subtitle-quality improvement or a speed win. Keep the named regressions and timestamp changes visible in the release decision. This is an API35 x86_64 Google sdk_gphone64_x86_64 emulator result, not an OPPO/ARM64 measurement.

Run38718249045/job116366132840, artifact11964549455. Downloaded ZIP SHA256 e858a2ccb5c805e6437229defb81e8d0a53c2706556156bb49545ad0c3743617; source artifact size536358129bytes verified by engineering owner. Workflow head de5377661893b74e1f4c3d42188d87ddaae78bec; tested merge checkout6c1bdb29f2dbfe890963560985155ae0baf8dc22. Owner checked the merge differs from head only in release-request metadata; app/test/script/workflow unchanged. Individual report hashes/APK hashes/native revisions are in ACTUAL-SUMMARY.json.

## Quality

Kishida114 independent edited Commons reference,436 normalized characters:
- Runtime1.12.32:47 edits,10.7798%CER.
- Runtime1.13.8:38 edits,8.7156%CER.
- Difference:9 edits,2.0642 percentage points. Not a confidence interval or general-language accuracy estimate.
- Each result repeats exactly for raw and production, three passes per backend, two ABBA runs per version. Same-version texts and timestamp/word structures are identical across repeats.
- Python was44→36 edits,10.09→8.26%; Android is47→38,10.78→8.72%. Thus direction, not exact output, reproduced. Full per-window Python differences retained; they are not treated as failures merely because the platforms differ.
-33 windows/pass are13 Emoji,12 full Kishida,8 Kishida114. Seven Kishida windows repeat within the114s subset. No pooled CER or independent-sample claim is made.

Material checks in raw and matching production:
- Improved: 容疑者が態捕をされ → 容疑者が逮捕され.
- Improved: 民主義の根幹をなく → 民主主義の根幹をなす.
- Improved: 妨られる → 妨げられる.
- Regression outside scored114s: full Kishida window8,114.630–123.046s, 広島サミット → 広島さと, with an added ござ at the beginning. The edited Commons reference replaces this spoken question with a parenthetical topic, so the114s score does not cover this regression.
- Regression: Emoji list window6, 握り拳 → 握りこ; コマり顔 → 小マり顔. Both still misrecognize ハート as パート. Emoji lacks independent complete gold, so these are qualitative diagnostic comparisons, not an Emoji CER.
- New duplication: 最後まで → 最後までで. Other errors remain, including 容疑者確去 and distorted courtesy wording.
- Preserved: Emoji language question and English/Spanish/Chinese options; both retain 絵文字の映画がハリウッドで作られるほど. No Qwen outputs enter this Android comparison.

## Performance and timestamps

Production-only warm-pass descriptive medians across the two runs per version:
- Emoji RTF0.07676→0.07944, +3.49% elapsed time.
- Full Kishida0.08863→0.09146, +3.20%.
- Kishida1140.08993→0.09245, +2.80%.

Do not advertise acceleration. Initial baseline raw pass is especially slow (full Kishida RTF0.1803), and later baseline raw is much faster; this makes a naive first-baseline/first-candidate comparison misleading. Raw runs before production, storage is already warmed by staging and pack verification, and timing boundaries differ between backends. Keep same-backend comparisons. These are not phone/bilingual/player latency figures.

All33 window timestamp structures change across versions, but no monotonicity, bounds, token/count or absolute-offset violations were found by the parser. Within-window matching of identical tokens gives approximately+60ms median onset shift for each sample, with outliers −660ms to+360ms in Kishida and0–120ms in Emoji. Exact-token SequenceMatcher alignment is descriptive, not a timing gold reference; changed/repeated words can make matches ambiguous. No claim that the new timings are more accurate. Raw durations arrays remain empty. Full actual artifacts preserve native token and production word boundaries for detailed investigation.

## Lifecycle

Every report has1 raw recognizer created/released,99 raw streams created/released,3 production ensures,102 recognition calls,3 closes, close/reload equality true. Production native allocation counts are explicitly unobservable.

All4 cancellation probes skipped pre-dispatch work and suppressed publication when caller cancellation took effect. cancel/join was2248.56–2332.98ms. Native interruption remains unproven, and these results do not cover seek/player lifecycle or bounded/immediate cancellation.

## Scope and provenance

Parser validated actual runtime/build labels, frozen manifest, identical float input, window hashes and ordering,198 rows/report, same-version APK identity, raw/production text equality, lifecycle counters and cancellation fields. It did not execute inference or alter the tested app. Full initial-pass text is preserved in ACTUAL-RAW-TEXT.md; all per-pass metrics and source hashes in ACTUAL-SUMMARY.json. Script summarize_actual.py derives these files from the four original reports plus analyze.py output. No large raw report is required to read this summary.

Fixtures remain publicly licensed adaptations: Emoji by Simpleshow Japan, CC BY-SA4.0, https://commons.wikimedia.org/wiki/File:Emoji.webm ; derived Emoji text shared under https://creativecommons.org/licenses/by-sa/4.0/ . Kishida by Cabinet Public Affairs Office / Prime Minister's Office of Japan, CC BY4.0, https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm ; https://creativecommons.org/licenses/by/4.0/ . ASR text may contain errors and is not an official transcript or endorsement. Edited reference: https://commons.wikimedia.org/wiki/TimedText:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm.ja.srt . No new MT, phone, end-to-end video, or general release acceptance is asserted.
