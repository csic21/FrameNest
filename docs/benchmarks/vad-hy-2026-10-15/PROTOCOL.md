# Bounded VAD→Hy translation experiment

2026-10-15. Independent from stable0.6.9/main/PR4. Pending results, no semantic success claimed.

Take all13 Emoji and12 full175.787s Kishida VAD rows from docs/benchmarks/reazon-ab-2026-10-15/results/sensevoice-official-vad-deduplicated.json at8dc2130000cff5fd588db326f34022992ca706b2. Preserve exact text, including fragments and punctuation-only rows. No correction, joining, previous-context wrapper or selection of passing examples. The separately clipped kishida114 run/CER10.09% is not the same source segmentation and is not substituted for the full-video rows.

Existing verified Hy-MT2-1.8B-Q4_K_M, SHA256dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699; official llama.cpp pinned d81235049384534c167caea52b85a694f6103d14 rebuilt locally Release CPU. Same official baseline Chinese translation prompt; CPU2, context1024/output256, temp.7/top-p.6/top-k20/repeat1.05/seed42/defaultmin-p.05. Fresh process per segment, cache not cleared; no claim of mobile speed. Record prompt, full output/stderr, MT firststdout/completion, original VAD timings, sourceASR timings and excluded gaps. Gaps never count as translated coverage; successful inference does not establish semantic correctness or source FINAL.

Review every source/translation pair, emphasizing question/list reference, Hollywood relationship, long-text causality, arrest and political statement errors. No new model, prompt search, production changes or UI acceptance. Existing source licensing/attribution from the parent Reazon evidence applies; Emoji derivatives CC BY-SA4.0, Kishida attributed Cabinet Public Affairs Office / 首相官邸ホームページ. Model/runtime licenses remain as pinned earlier benchmarks.
