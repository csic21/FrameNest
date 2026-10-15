# Full VAD → SenseVoice → Hy translation comparison

2026-10-15. **Semantic acceptance FAILED; useful local improvements do not justify replacing the released path.** This is an independent evidence-only experiment; stable0.6.9/main/PR4 were not modified.

## Complete input and real inference

All13 Emoji segments and all12 full-length Kishida segments were translated exactly as returned by official SileroVAD+SenseVoice. No source correction, punctuation repair, joining, previous-sentence prompt, dropped fragments or model search. Even the punctuation-only “？” and fragment “この？” were kept. All25 calls exited0 with EOG; this is execution success, not semantic success.

| Video | Full audio | VAD selected | VAD excluded | Real MT time |
|---|---:|---:|---:|---:|
| Emoji |113.960s|95.200s /83.54%|18.760s|33.375s|
| Kishida |175.7866875s|168.932s /96.10%|6.8546875s|35.265s|

Every excluded interval and its measured RMS is retained separately. They are VAD decisions, not human-verified silence, and never count as successful transcription or translation coverage. A model producing text over a selected interval does not establish that all its words were recognized. No app completed coverage or persistent cache was added.

Source evidence: branch experiment/FN-70-reazon-ab commit8dc2130000cff5fd588db326f34022992ca706b2, docs/benchmarks/reazon-ab-2026-10-15/results/sensevoice-official-vad-deduplicated.json. Input SHA256 is recorded in evidence-results.json. Original token/PCM evidence remains at that immutable source; this report's results retain exact text, segment times, prompts, commands, full output/stderr and every exclusion gap.

**Important distinction:** CER10.09% belongs to a separately truncated114s Kishida ASR run. The complete175.787s run has different windows and still outputs “容疑者が態度をされ” and “民主義の根幹をなく”. The114s score is not silently transferred to full-video output. No new CER or golden transcript was invented here.

## Comparison with run24 / whole-source units

Improvements:
- Emoji Hollywood clause now belongs with popularity: “尤其受到年轻一代的喜爱——甚至还有…电影在好莱坞制作”. It no longer invents the old Hollywood-film→Japanese-origin/expression-style causal chain.
- The separate style sentence becomes “将图像像文字一样使用的新型表达方式”, fixing the earlier image-to-text conversion interpretation. The correct-answer sentence also translates cleanly.
- Kishida's reporter question now retains 朝日新聞 and 広島サミット and asks whether security was sufficient. The summit/dignitary continuation now says people gather, rather than are rescued.

Blocking failures:
- Emoji language list still incorrectly claims English/Spanish/Chinese are not used in business documents. Preserving the question and list did not fix the pronoun's referent.
- Separate “絵文字は日本発祥” becomes “涂鸦是日本起源”. “意味深なピザやナスも” produces mixed-language “意味深なる披萨和茄子也”. Raw short chunks are not automatically good MT units.
- The long-text/emotion/history segment still adds “因此” and an unsupported causal connection to ancient use. Dog prohibition becomes keeping-dogs prohibition; floppy-disk icon becomes an icon on a floppy disk. The corrupted final emoji name remains Japanese noise.
- Kishida's damaged arrest source becomes “嫌疑人态度可疑，目前正在检察机关接受调查”, introducing a suspicious-attitude assertion and losing arrest. “民主義の根幹をなく” still yields “民主制度的根本被摧毁了”. These are consequential factual errors, not merely awkward wording.
- Several Kishida boundaries split commitments and predicates. The diplomatic-security continuation is turned into a request to citizens, while “力を尽くしていきたい” is isolated and adds “正在努力”. The initial event topic and punctuation-only units are not complete statements.

SENTENCE-REVIEW.md reviews all25 pairs, including favorable and unfavorable cases. This is Japanese/Chinese text review with existing source/reference context, not an independent human listening audit of all audio. No confidence label can excuse the factual additions above.

## Timing and earliest useful text

CPU AMD EPYC9V74, affinity0–1,2 inference threads, noGPU. Each segment uses a fresh CLI process; model/file caches were not cleared. These are cloud computations on already captured ASR source, not a paced end-to-end Android/NAS test or OPPO performance claim. MT process high-water RSS reported by the harness is1957360KiB (~1911.48MiB); no phone memory acceptance.

- Emoji first complete question spans2.982–5.030s; VAD's recorded input-ready point is5.280s. MT first stdout1.594s and completion1.909s after launching that segment. Source ASR decode in the first captured pass was0.060s. These independent timings are not a directly measured live first-caption time; actual live capture must wait for the VAD endpoint.
- Kishida first segment ready at5.088s produces only “关于昨天的事件，首先…”, not a complete useful first sentence. The following “？” is not progress in semantic understanding. The first broadly faithful substantial complete unit in this review is segment6, input-ready90.400s, MT3.668s, but this does not mean the preceding partial content was absent or all earlier sentences were recoverable.
- Reused sourceASR work ranges: Emoji2.509–3.252s plus VAD0.468–0.571s; Kishida4.154–5.740s plus VAD0.783–0.849s. Do not present reused work as newly measured end-to-end latency. No model download/audio extraction/CLI compilation in MT totals.

## Runtime, prompt and reproduction

Existing Hy-MT2-1.8B-Q4_K_M.gguf1133080448bytes was rehashed as dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699. Official llama.cpp source d81235049384534c167caea52b85a694f6103d14 was rebuilt locally as Release CPU because the old CLI executable had disappeared. Binary hash and exact args are in results. Runtime identity is pinned; binary differs from the previously downloaded release, so timing is not a controlled cross-binary speed comparison.

Prompt unchanged: 将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释： followed by newline and the exact source. CPU2, context1024/output256, temperature0.7/top-p0.6/top-k20/repeat1.05/seed42, default min-p0.05, Jinja user-turn formatting. No prompt retries. run.py and summarize.py preserve all data and review. Use parent ASR files and existing model paths documented in run.py; build command is in build-manifest.json.

Attribution/licensing: Emoji by Simpleshow Japan, CC BY-SA4.0, https://commons.wikimedia.org/wiki/File:Emoji.webm ; experimental machine transcript/translation adaptations retain CC BY-SA4.0. Kishida video Cabinet Public Affairs Office / 首相官邸ホームページ, Commons-declared GJSTU2.0/CC BY4.0, https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm . Preserve source attribution; no endorsement. Hy weights Apache2, llama.cpp MIT; SenseVoice weights custom FunASR model license, not the sherpa runtime's Apache license. All weight/media bytes remain outside the repository.

## Decision

VAD improves some sentence associations and preserves useful answer/movie/source-style units. It also produces fragments and does not correct ASR or MT factual errors. Do not call this a usable complete bilingual-caption replacement or a release acceptance. No stable code, production prompt or release was changed.
