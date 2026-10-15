# Failure-layer diagnosis — all25 segments

Zero-based segment IDs; same unchanged outputs as the full report. **This is a manual text-level review, not a statistically validated error taxonomy or proof of a unique root cause.** Multiple layers can fail on one segment; do not sum overlapping flags. No new inference was run.

## Outcomes

-8/25 substantially faithful in meaning: Emoji0,2,3,5,10; Kishida6,7,8.
-1/25 minor scope warning: Emoji9, 絵 (pictures) becomes 艺术 (art).
-16/25 fail or remain incomplete/unusable as independently reliable caption units. Includes punctuation-only/source fragments, not just wrong translations.

## Overlapping failure signals

1. **SourceASR damage:9/25** — Emoji6,11,12; Kishida2,3,4,9,10,11. Examples include list itemパート, corrupted emoji name手しなき, arrest態度をされ, and根幹をなく. For Emoji, absent independent gold means some are text/cross-capture error signals rather than human-adjudicated errors. Do not ascribe all resulting wrong meaning solely to MT.
2. **Clear MT error on sufficiently understandable source material:3/25** — Emoji4,7,11. Of these, only Emoji4 is a wholly clean complete standalone source sentence; Emoji7 is a fragment, and Emoji11 contains otherASR damage while its dog/icon clauses remain understandable. Thus the requested strict “complete correct source→wrong MT” count is **1**, with **2 additional local MT failures** rather than3 clean-source controls.
3. **Boundary / cross-unit discourse failures:11/25** — Emoji1,7,8; Kishida0,1,3,4,5,9,10,11. This includes both VAD cutting dependent phrases and VAD grouping different propositions without adequate discourse cues. It does not assert that acoustic segmentation alone is the sole cause. The most important examples are the language-list referent, added emotion→ancient-history causality, and splitting the security pledge from “力を尽くしていきたい”.

The union of these3 flags is16 segments. Nine+three+eleven must not be presented as23 distinct failures. Exact flags and all25 original source/translation pairs are in failure-classification.json.

## Four representative error types, exact observed strings

### A. SourceASR corruption becomes a factual assertion (Kishida2)

Source: 容疑者が態度をされ、そして検察において捜査中です。また私の方からえのご心配をおかけし、そしてご迷惑をおかけした地元の皆さんをや。

Translation: 嫌疑人态度可疑，目前正在检察机关接受调查。同时，我也给当地民众带来了困扰和麻烦。

The independent reference says the suspect was arrested and police were investigating. This source is already damaged; the MT then adds a suspicious-attitude assertion. More sentence context alone cannot guarantee factual repair.

### B. Complete clean source, wrong MT object (Emoji4)

Source: 絵文字は日本発祥。

Translation: 涂鸦是日本起源。

This is an unambiguous complete sentence about emoji. Calling the object graffiti is a translation error; noASR/VAD ambiguity is needed to explain this counterexample.

### C. Question/list referent fails inside a long VAD unit (Emoji1)

Source: 今、世界 中 で 使 われ て いる の は 何語 でしょ うか 英語、スペイン 語、中国語、それ は ビジネス 文章 で は 使 わ れ ない 言葉 です。

Translation: 现在，世界上使用的语言究竟是什么？是英语、西班牙语还是汉语呢？这些语言在商务文书中并不被使用。

The question is preserved, but the last clause wrongly attaches to the proposed languages. The next separate unit gives the emoji answer. This is a discourse/reference problem rather than simply a missing 中国語 character.

### D. Cut pledge changes speaker/action (Kishida10→11)

Source10: こうした日程日本いては、日本全体として最大限警備安全に努めていかなければならないと思っています、ぜひ国民の皆さんと共にこうした外交日程においても安全を確保できるよう。

Translation10: 我认为，面对这样的日程安排，全日本必须尽最大努力确保安全与秩序。请与各位民众共同努力，确保这些外交活动也能安全进行。

Source11: 力を尽くしていきたいと思って、います、い。

Translation11: 我想全力以赴，正在努力。

The dependent safety clause is separated from the speaker's promise to make efforts. MT turns one fragment into an instruction and the other into current action. Uncertainty labels would not repair the subject/mood change.

## Which layer to address next

The highest-frequency observed barrier is **source-unit/discourse quality**, not lack of another model candidate: raw acoustic VAD is not a semantic sentence segmenter. Hollywood association improving here is concrete evidence that context grouping matters, but the first question/list and long-text/history examples show grouping is not yet reliable. A next bounded engineering investigation should inspect unit boundaries/ownership and recognition reliability using these existing captured inputs, not silently glue every pause or trust punctuation at a decoding edge.

That alone cannot satisfy acceptance: sourceASR factual damage and the clean-sentence emoji→graffiti counterexample remain independent blockers. Any eventual path must be judged on all three layers and excluded-input accounting. This diagnosis does not authorize an additional model/prompt experiment, nor claim a boundary fix will solve all failures.
