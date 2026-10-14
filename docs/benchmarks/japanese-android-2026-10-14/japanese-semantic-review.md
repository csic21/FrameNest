# Japanese → Chinese fixture semantic review

**Release decision: FAIL.** The collected bilingual output is not ready for a usable-subtitles release. Functional integration passing does not override the meaning errors below.

## Review scope and limitations

- Reviewed `japanese-fixture-review.json` and `japanese-benchmark.json` on 2026-10-14.
- This is a transcript-to-translation and adjacent-window text review only. I did not listen to the audio, compare an independent golden transcript, inspect physical rendering, or measure real-device performance. No listening acceptance, ASR accuracy score, or full-video acceptance is asserted.
- Fixture identity recorded in the supplied artifact: Simpleshow Japan, [Emoji.webm](https://commons.wikimedia.org/wiki/File:Emoji.webm), SHA-256 `ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956`.
- The supplied text covers 0–24 seconds and ends during a sentence. An unfinished final collected sentence alone is not proof of a production omission.

## Findings

| Span | Evidence | Severity and effect |
| --- | --- | --- |
| 0–6 s | Japanese `こ` / `のイラストの意味わかりますか`; the first fragment has an empty Chinese target and a 180 ms cue. | Medium. `この` is split across cues, leaving neither cue independently well formed. The second Chinese cue conveys the basic question, with the demonstrative omitted. The text establishes an awkward boundary; actual flicker was not observed. |
| 6–12 s | Japanese `今世界中で使われているのは何語で` / `ょうか英語、スペイン語、中`; Chinese `现在它是全世界的使用` / `英语，西班牙语，`. | High, release-blocking. The Chinese is ungrammatical and loses the central “what language?” question. Joining the Japanese fragments yields `何語でょうか`, which is malformed; adjacent-window text does not recover the missing grammatical material. Audio is needed to attribute that defect to recognition or boundary filtering. |
| 9–18 s | Japanese `中` / `国語、それ は ビジネス 文章 で は` / `使われない言葉です。正解は`; Chinese `英语，西班牙语，` / `术语，它是商业句子` / `这是一个未使用的词。 答案是`. | High, release-blocking. Adjacent Japanese identifies `中国語` and a business-writing limitation on “not used.” Chinese changes the language-list item into `术语` (“terminology”), introduces a statement about commercial sentences, and separates the negation from its business-writing scope. Reading the Chinese cues together does not restore the intended meaning. |
| 18–21 s | Japanese `絵文字`; Chinese `形符`. | High, release-blocking. The video's central answer is rendered with an unnatural, nonstandard term for emoji instead of a recognizable equivalent such as `表情符号`. This is a terminology/translation problem in addition to boundary problems. |
| 21–24 s | Japanese `絵文字 は インターネット 上 の コミ`; Chinese `表情符号在互联网上`. | Medium boundary/context concern. Japanese ends inside a word. Chinese is a plausible partial clause and correctly renders `絵文字` here, making the preceding answer inconsistent. The collection ends here, so continuation loss cannot be established without subsequent windows. |

The recognizable source intent across 9–18 seconds includes the language name “Chinese” and “not used in business writing.” These observations rely on the supplied Japanese text and its adjacent context, not an independently verified audio transcription.

## Minimal engineering recommendation

1. Keep transport/decode windows separate from semantic units. Accumulate stable Japanese tokens across adjacent windows and assemble a complete clause before committing its Chinese translation; preserve token timestamps when assigning output cues. Avoid committing orphan prefixes/suffixes as final Japanese cues.
2. Provide preceding and following clause context to translation. Reconcile overlap without dropping or duplicating tokens. ASR decode overlap is already recorded in the fixture; its presence alone does not make fragmented translation input coherent.
3. Re-test the complete reconstructed clauses, including the short answer `絵文字`. If the translator still produces `形符` on a complete input, fix model/terminology handling before release; boundary changes alone do not demonstrate translation quality.
4. Add regressions for `この`, `何語でしょうか`, `中国語`, scoped negation spanning `ビジネス文章では` / `使われない`, and consistent emoji terminology. Treat the expected grammatical forms as test examples until audio review verifies the actual wording.

## Acceptance needed after the fix

- Listen to the pinned fixture and review complete utterances, adjacent-window joins, omissions, duplication, and Japanese-to-Chinese meaning. Record the reviewer, scope, and outcome explicitly.
- Require all meaning-changing defects above to be resolved, with coherent Japanese and natural Chinese in both individual cue display and continuous reading. Review beyond 24 seconds to close the last collected fragment.
- Re-run latency and display checks because semantic buffering changes when a finalized bilingual cue becomes available. Do not silently revise old final captions without evaluating readability.

## Metrics interpretation

The artifact records `functional_acceptance_passed: true`, `semantic_acceptance_passed: null`, `accuracy_scored: false`, and `hardware_performance_accepted: false`. Those claims are appropriately separate. First-install bilingual publication is recorded at 66,115 ms and the warm-download-cache run at 4,396 ms, both for a paused playhead in an Android emulator. These are functional integration observations, not proof of flowing real-time captions or physical-device acceptance. Keep the release gate closed.

## Attribution

Quoted machine-derived excerpts are from Simpleshow Japan, *Emoji*, provided under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The fixture identifies the excerpts as machine-generated Japanese transcription and Chinese translation that may contain errors. This review adds analysis and groups the supplied excerpts by adjacent context. Derived excerpts remain under CC BY-SA 4.0.
