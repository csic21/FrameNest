# FN-74 Android ABBA read-only analysis

Prepared against actual `SenseVoiceRuntimeComparisonTest.kt` schema_version=1. No real Android artifacts have been analyzed yet. Synthetic tests create temporary in-memory/directory fixtures only; they are not measurement evidence.

Run after four reports arrive, in baseline/candidate/candidate/baseline chronological order:

    python analyze.py baseline-first.json candidate-first.json candidate-last.json baseline-last.json --manifest ../../framenest-sherpa-runtime-ab/app/src/androidTest/assets/runtime-comparison/manifest.json --reference ../../reazon-ab/fixtures/kishida-reference.srt --python-results ../results/sensevoice-legacy-runtime.json ../results/sensevoice.json --output actual-android-analysis.json

Run tests from any working directory:

    python -m unittest discover -s qwen-asr-ab/android-analysis -p 'test*.py' -v

The command example is relative to this directory. The manifest must be the exact frozen bytes from the tested APK. Validation refuses incomplete/failed reports, inconsistent native/runtime labels, changed manifest/PCM windows, missing/duplicate/reordered rows, invalid elapsed times/absolute timestamp offsets, inconsistent raw/production text equality, and unbalanced lifecycle counters. Full public fixture attribution is retained in the source manifest and parent PROTOCOL.md.

Review checklist:
- 13 Emoji +12 Kishida +8 Kishida114 =33 windows per pass, with7 repeated Kishida windows. They are not33 independent examples. No pooled CER or aggregate performance across these overlapping samples.
- CER only Kishida114, exact pinned436-character edited Commons reference, NFKC/lowercase/remove whitespace and Unicode P/S; parenthesized unspoken question removed. No filler/spelling tuning. Emoji and full Kishida have no complete gold score.
- Compare each backend with itself across runtimes, in both AB and BA directions; inspect baseline repeat and candidate repeat before attributing changes. Three passes within one process are not independent devices/trials.
- Raw executes before production. Model staging and verification warm storage; model creation is not disk-cold. Raw decode boundaries exclude stream release; production includes dispatch/conversion/release. Never interpret raw-v-production speed as runtime A/B.
- Report per-pass full-extent RTF and selected-speech RTF separately; gaps are excluded, never accepted coverage. First nonempty decode and source VAD-ready time are separate, not claimed live player first-caption latency.
- Compare exact text and timestamp/word-boundary structures. Token/onset mismatches, nonmonotonicity, invalid durations and out-of-window words are flagged for review, not silently fixed. Do not assume production word bounds equal raw token bounds.
- Python text correspondence is optional and preserves every differing sample/backend/pass/window. Do not assert Android equals Python from equal configuration. Android-versus-Python timing is not a comparable target benchmark.
- Raw stream and recognizer counts balance; production native allocations remain unobservable. Coroutine cancellation and reload success do not prove native interruption, bounded cancellation, seek behavior or player lifecycle.
- A passed instrumentation test establishes that the harness assertions passed, not acceptable subtitle accuracy or an OPPO performance result. Inspect source/model/APK provenance and the actual runner hardware separately.

For a fresh checkout, set FN74_MANIFEST to the absolute path of the frozen manifest from the tested FN-74 checkout and FN74_REFERENCE to the pinned SRT before running unit tests. The defaults above refer to the original experiment workspace layout. The analyzer itself always takes explicit paths. This evidence branch does not contain or modify the tested app.
