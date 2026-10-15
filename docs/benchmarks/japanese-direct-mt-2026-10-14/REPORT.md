# 日语→中文直译模型受控实测（2026-10-14 UTC）

## 结论

**Hy-MT2-1.8B Q4_K_M 是可继续研究的更强 MT 候选，但当前截断 ASR 到字幕链路仍 FAIL，不得据此发布。**
- 六条人工完整语言控制：官方默认 prompt 核心语义 6/6 保留，MLKit 既有独立文字评审为 2/6。只说明这组定向诊断的改进，不是通用翻译准确率。
- 四条真实 15 秒 ASR 文本：至少三条仍出现无依据补写或关系连接；其余一条恢复了 MLKit 漏掉的电影／好莱坞信息，但没有音频或连续字幕边界验收。
- 按要求仅做一次通用“片段不补全”prompt 对照，六条完整控制未见核心语义退化；三个问题块仍 FAIL。没有继续 prompt 搜索，没有 fixture 专用词典，没有第二模型。
- CPU2、真实 affinity 0–1：驻留模型的默认 prompt 完整控制请求 0.899–2.112 秒；强制片段 prompt 的 CLI 引擎推理 0.969–3.299 秒，另有每次模型冷进程载入。CLI peak RSS 最高 1,960,004 KiB（约 1.87 GiB）。这是云 AMD EPYC CPU 结果，不是 OPPO、Android 或模拟器性能。

## 输入与范围

输入严格使用 `../japanese-run11-diagnostic/japanese-fixture-review.json` 中六个 controlled_text_diagnostic.textSrc，再使用四个 whole_block_diagnostic.textSrc；没有清洗空格、改错、合并或补齐源文。
输入 JSON SHA256：`b8471f58249a366ca91bfa8f407f3f5498101d401d22b3146213231b4ce9cb9c`。
人工控制是作者编写的语言探针，不是视频原文、独立音频 gold 或 ASR 准确率标签。四个真实块含首尾残片、空格及疑似识别错误，不能称为四个完整自然句。本次没有听审、播放器渲染验收或生产集成。

## 版本、许可和可信来源

- 模型：[Tencent 官方 Hy-MT2-1.8B-GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/tree/a0c709d9fac510f2c807aa3af52872340dc37a4a)，Apache-2.0；revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`。
- 文件 `Hy-MT2-1.8B-Q4_K_M.gguf`：1,133,080,448 B，SHA256 `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`。先复核官方 API LFS manifest，再下载并验证完全相同的 hash。
- Runtime：[ggml-org/llama.cpp v0.6.0 官方稳定发布](https://github.com/ggml-org/llama.cpp/releases/tag/v0.6.0) 的 nightly-tag.txt 明确指向 [b11429](https://github.com/ggml-org/llama.cpp/releases/tag/b11429)；其二进制报告 `0.6.0-dev (build 11429, commit d81235049)`，实际 commit `d81235049384534c167caea52b85a694f6103d14`。稳定发布元数据的 target commit 是 `8345f333951c661d166b00e6f9362e553768f292`，不与所指二进制 commit 混为一谈。
- 官方 Ubuntu x64 archive 17,693,462 B，SHA256 `f6d25dde8f51133143d1453da4fd5f73b145127177612a283bf7995957af3392`；校验通过。llama.cpp MIT；完整许可证保存在 sources。
- GGUF 实际架构 hunyuan-dense，129 F32、33 Q6_K、192 Q4_K tensors；没有 STQ 类型。未用 462 MB STQ、未合并分支、第三方 fork、在线 LLM、API keys 或 NAS 文件。

## 机器、参数与计时口径

- 当前云实例：AMD EPYC 9V74，9 visible vCPU；开始时可用 RAM 约 8.6 GiB、磁盘可用 29 GiB。`lscpu` 因虚拟 /sys 不完整报错，CPU 信息以 /proc/cpuinfo 和 affinity 实测为准。
- 主测推理 threads=2、batch threads=2；用 os.sched_setaffinity 将子进程限定 CPU 0–1。/proc 实测 Cpus_allowed_list=0-1，无权限升级或安全设置更改。进程还有 I/O／库工作线程，不能将总 OS threads=14 误称为 14 个推理 CPU。
- CPU-only `-ngl 0`；context=1024，max generated tokens=256，seed=42；temperature=.7、top_p=.6、top_k=20、repeat_penalty=1.05。其余保留该官方 runtime 默认值，包括 min_p=.05、repeat_last_n=64；不是贪心采样。无 prompt 缓存、词典、few-shot 或系统提示。
- 使用 GGUF 内官方 Jinja user/assistant chat template；完整模板见 sources/gguf-metadata.json 与 results/hy_cpu2/server-props.json。
- 初始 localhost server 仅作为驻留模型实测：fresh process 到 health-ready 1.558 s，包含默认 warmup。每例 SSE 首次非空 content 和最后完成时点来自 monotonic wall clock；cached_tokens 全为0。
- server 启动时提示“no API key is set and CORS allows all origins”；看到 warning 后中断，未加 key、未改 CORS、未重启。已有9例保留为标明模式的历史测量，第4个 ASR 在进行中中断。后续改用无监听官方 llama-completion CLI，完成第4块并复测2控制。启动会话 exit130；后续 localhost:18791 检查 ECONNREFUSED。
- CLI 每例新进程，记录 process 总 wall、runtime reported load、prompt eval、generation eval、首个输出。不得将 CLI 含载入总时间与驻留 server 推理请求混比。所有载入前都读过模型 hash；没有清 OS 页缓存，因此是进程冷启动，不是存储冷启动。
- CLI first-content-from-process 含模型载入。另由日志 generate 标记到首次 stdout 的时差给出推理首输出近似，不声称是精确 runtime token API。/proc 采样保留，CLI os.wait4 的 maxRSS 为该独立子进程 Linux KiB。单次测量，没有热稳态统计或移动端外推。

## 两种固定 prompt

官方默认模板（source 原样替换）：

将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：
{source}

唯一通用片段对照：

将下面日语翻译成简体中文，只输出译文。输入可能是未完成的口语片段；只翻译已有内容，不推测或补写缺失词句，不添加解释；原文未完成时译文也保持未完成。
{source}

同一输入下对照 prompt 比默认多28个输入 tokens。没有任何词条、实体修补或逐例例外。

## 全量文本结果与逐条评审

### language_question

源文：今、世界中で使われているのは何語でしょうか。

MLKit：现在全球使用什么语言？

HY 默认：现在世界上使用的语言是什么语呢？

HY 片段对照：现在世界上使用的语言是什么语言？

评审：默认 pass_core_semantics；对照 pass_core_semantics。现在、全球、何种语言疑问均保留；默认“什么语”略生硬。

### language_entities

源文：英語、スペイン語、中国語の中で、あなたはどの言語を使いますか。

MLKit：你用英语，西班牙语，中国人使用哪种语言？

HY 默认：在英语、西班牙语和中文中，你使用哪种语言？

HY 片段对照：你使用哪种语言呢？英语、西班牙语还是中文？

评审：默认 pass_core_semantics；对照 pass_core_semantics。英语、西班牙语、中文三个语言实体及你选择使用哪种语言保留。

### negation_scope

源文：この表現は日常会話では使われますが、ビジネス文書では使われません。

MLKit：此表示在日常对话中使用，但不在商业文档中使用。

HY 默认：这种表达在日常对话中会使用，但在商务文书中则不会使用。

HY 片段对照：这种表达常用于日常对话中，但在商务文书中不会使用。

评审：默认 pass_core_semantics；对照 pass_core_semantics。日常会话肯定、商务文件否定及对比保留。guard“常用于”有轻微频率色彩，不构成关系倒置。

### emoji_answer

源文：正解は絵文字です。

MLKit：正确的答案是象形图。

HY 默认：正确答案是表情符号。

HY 片段对照：正确答案是表情符号。

评审：默认 pass_task_terminology；对照 pass_task_terminology。本任务的正确答案／表情符号概念保留。孤立絵文字有更宽泛词义，不外推通用词义结论。

### popularity_scope

源文：絵文字は若い人だけでなく幅広い年代の人に人気があり、世界中でメッセージのやり取りに使われています。

MLKit：表情符号不仅适用于年轻人，也很受欢迎，也很受欢迎，而且是各种年龄的人，并且用于在世界各地互动。

HY 默认：表情符号不仅受到年轻人喜爱，也受到各年龄层的人欢迎，被全球用于信息交流。

HY 片段对照：表情符号不仅受到年轻人的喜爱，也受到各年龄层的人欢迎，在全世界被用于信息交流。

评审：默认 pass_core_semantics；对照 pass_core_semantics。不仅年轻人、广泛年龄层的欢迎、全球信息交流均保留；没有把它改成人人使用。

### neutral_statement

源文：総理大臣は官邸で記者会見を開き、今日の会議の内容を説明しました。

MLKit：总理在公共居住地开设新闻发布会，并解释了今天会议的内容。

HY 默认：首相在官邸召开记者会，说明了今天的会议内容。

HY 片段对照：首相在官邸召开记者会，解释了今天的会议内容。

评审：默认 pass_core_semantics；对照 pass_core_semantics。首相、官邸、记者会、说明今天会议内容保留，没有新增人名或政策。

### asr_0_15000

源文：この イラスト の 意味 わかり ま すか 今、世界 中 で 使 われ て いる の は 何語 でしょ うか 英語、スペイン語、中国語、それ は ビジネス 文章 で は。

MLKit：此图的含义我想知道是否是全球使用的常用事物，英文，西班牙语，中文，这是一个商业句子。

HY 默认：你能理解这个插图的含义吗？现在全世界使用的是哪种语言呢？是英语、西班牙语还是中文？在商务文书中则常用英语。

HY 片段对照：您明白这幅插图的含义吗？现在全世界使用的语言是什么？是英语、西班牙语、中文，还是用于商务文件。

评审：默认 fail_unsupported_completion；对照 fail_relation_attachment。默认凭空补成商务文件常用英语。guard虽删除此补写，却将原本分开的商务残片接入“是英语、西班牙语、中文，还是用于商务文件”选择项，语义类别错接。

### asr_9000_24000

源文：うか 英語、スペイン語、中国語、それ はビジネス 文章では使われない言葉です 正解は絵文字 絵文字はインターネット上の。

MLKit：UMM英语，西班牙语，中文，它是一个不用于商业判决的词，正确答案是互联网上的表情符号象形图。

HY 默认：英语、西班牙语、汉语——这些在商务文本中都不会被使用。正确的表达方式就是使用表情符号，而表情符号则来自互联网。

HY 片段对照：英语、西班牙语、中文在商务文本中都不会使用这种词，正确答案是表情符号，表情符号是网络上的。

评审：默认 fail_unsupported_relation_and_completion；对照 fail_unsupported_relation。默认把模糊それ明确为三种语言均不用于商务文本，并新增表情符号来自互联网；将正确答案改为正确表达方式。guard删除互联网起源补写，但仍把英语、西语、中文作“不使用这种词”的参与者，不能据现有截断输入确认这种关系。

### asr_18000_33000

源文：絵文字 絵文字はインターネット上のコミュニケーションで使われ、特に若い世代に人気です 絵文字の映画がハリウッドで作られるほど。

MLKit：表情字符用于互联网上的通信，尤其是对于年轻世代。

HY 默认：表情符号在互联网交流中被广泛使用，尤其受到年轻人的喜爱——甚至还有以表情符号为题材的电影在好莱坞被制作出来。

HY 片段对照：表情符号在互联网交流中被广泛使用，尤其受到年轻一代的喜爱。甚至还有以表情符号为主题的电影在好莱坞制作。

评审：默认 core_facts_preserved_boundary_unresolved；对照 core_facts_preserved_boundary_unresolved。互联网交流、特别受年轻人欢迎、表情符号电影在好莱坞制作均保留，较MLKit明显改善。两种输出都补充“广泛”，且将末尾程度从句ほど自然化成独立完整陈述；核心内容可读不等于连续字幕或音频语义验收通过。

### asr_27000_42000

源文：第人気 絵文字の映画が ハリウッドで作られるほど、絵文字は 日本発祥 絵を文字 のよ うに使う 新しい表現スタイルです。

MLKit：更流行的象形图电影是在好莱坞制造的，象形图是使用日本的出生件作为字符的新表达式。

HY 默认：由于有如此受欢迎的表情符号电影在好莱坞被制作出来，表情符号其实是一种源自日本的、将图像当作文字来使用的新型表达方式。

HY 片段对照：如此受欢迎的表情符号电影在好莱坞被制作出来，说明表情符号是一种源自日本的、将图像作为文字使用的新型表达方式。

评审：默认 fail_invented_causal_link；对照 fail_invented_inferential_link。日本起源和图画作文字恢复，但默认“由于”把好莱坞电影与日本起源连成因果；guard“说明”改为推论，均不是源文给出的关系。第人気本身疑似ASR错误，不能凭此报告判定音频原文。

## 原始默认 prompt 驻留 server 时延（CPU2）

| case | 首 content s | 全译文 wall s | prompt tokens | output tokens |
|---|---:|---:|---:|---:|
| language_question | 0.649 | 1.035 | 34 | 9 |
| language_entities | 0.698 | 1.256 | 44 | 14 |
| negation_scope | 0.702 | 1.551 | 43 | 17 |
| emoji_answer | 0.640 | 0.899 | 27 | 6 |
| popularity_scope | 1.108 | 2.112 | 56 | 22 |
| neutral_statement | 0.790 | 1.358 | 46 | 14 |
| asr_0_15000 | 1.325 | 2.648 | 92 | 31 |
| asr_9000_24000 | 0.910 | 2.210 | 68 | 31 |
| asr_18000_33000 | 1.140 | 2.707 | 71 | 31 |

9例 finish_reason=stop，均没有256输出上限截断。第4块未完成，不拿残留输出充作结果。

## 默认 prompt 无监听 CLI 补测（CPU2）

| case | process total s | engine load s | prompt eval s | decode eval s | inference sum s | prompt/decode runs | maxRSS KiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| asr_27000_42000 | 4.165 | 1.064 | 1.184 | 1.502 | 2.686 | 72/29 | 1955132 |
| language_entities | 3.087 | 1.066 | 0.970 | 0.653 | 1.623 | 44/13 | 1952548 |
| emoji_answer | 2.206 | 0.959 | 0.599 | 0.248 | 0.847 | 27/5 | 1950596 |

## 唯一片段 prompt 无监听 CLI 对照（CPU2）

| case | process total s | engine load s | first content from generate-log s | prompt eval s | decode eval s | inference sum s | prompt/decode runs | maxRSS KiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| language_question | 2.920 | 1.029 | 1.118 | 1.117 | 0.372 | 1.489 | 62/7 | 1954420 |
| language_entities | 2.819 | 0.890 | 1.006 | 1.000 | 0.559 | 1.559 | 72/12 | 1955096 |
| negation_scope | 3.268 | 0.967 | 1.255 | 1.251 | 0.652 | 1.903 | 71/14 | 1954968 |
| emoji_answer | 2.192 | 0.872 | 0.780 | 0.778 | 0.191 | 0.969 | 55/5 | 1953440 |
| popularity_scope | 3.908 | 0.968 | 1.477 | 1.474 | 1.083 | 2.557 | 84/21 | 1956384 |
| neutral_statement | 3.458 | 1.056 | 1.348 | 1.346 | 0.656 | 2.002 | 74/13 | 1955384 |
| asr_0_15000 | 4.803 | 1.082 | 1.940 | 1.939 | 1.360 | 3.299 | 120/26 | 1960004 |
| asr_9000_24000 | 3.887 | 0.971 | 1.393 | 1.391 | 1.094 | 2.485 | 96/24 | 1957364 |
| asr_18000_33000 | 4.353 | 0.968 | 1.654 | 1.651 | 1.288 | 2.938 | 99/27 | 1957804 |
| asr_27000_42000 | 4.150 | 0.941 | 1.424 | 1.422 | 1.388 | 2.810 | 100/27 | 1958012 |

CLI decode runs 不是 server completion_tokens 的同一计数定义，不将两者直接相减比较。所有13次CLI exit0并见真实EOG标记，无输出上限截断。

## EOS 与执行警告

- 官方 GGUF eos_token_id=120020，对应 `<｜hy_place▁holder▁no▁2｜>`。官方 llama.cpp 在 configured EOS 未包含于 EOG set 时先加入该 token，再打印 `special_eos_id is not in special_eog_ids`。证据：[llama-vocab.cpp 2978–2982](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/src/llama-vocab.cpp#L2978-L2982)。没有修改权重、tokenizer或运行时以消除 warning。
- CLI 的 `[end of text]` 是在 llama_vocab_is_eog 判真时由官方程序打印的终止标记，不计入译文；raw stdout 完整保留。证据：[completion.cpp 927–931](https://github.com/ggml-org/llama.cpp/blob/d81235049384534c167caea52b85a694f6103d14/tools/completion/completion.cpp#L927-L931)。
- CLI 还提示 user-specified prompt will pre-start conversation；这正是模型官方给出的 user prompt + 单轮推理用法，不改成 system prompt。所有 warning 作为原始 stderr 留存。没有浏览器或证书安全警告被绕过。

## 复现与文件

1. 在同一目录保留原始 fixture JSON（路径如上），Python3 标准库足够。`bash prepare.sh` 从官方来源复核并准备固定 model/runtime，任何 hash 不符即停止。
2. 默认无监听补测：`python3 benchmark_hy_cli.py --threads 2`。若需重新测默认全部10例，传入下面相同 --cases 列表，但不要将新增测量覆盖本报告证据。
3. 一次片段对照：`python3 benchmark_hy_cli.py --threads 2 --prompt-mode fragment_guard --cases language_question language_entities negation_scope emoji_answer popularity_scope neutral_statement asr_0_15000 asr_9000_24000 asr_18000_33000 asr_27000_42000`。
4. `benchmark_hy.py` 保留初始 server 探针的可审计实现，**不要重新启动它**；后续复现只用无监听 CLI。CLI脚本也从它导入常量和只读工具函数，import不会启动server。
- `results/*/results.json`：真实逐例输出和计时；`*.events.json`、`*.stderr.log`、`*.stdout.txt`：原始证据；`*.process-samples.json`：资源采样。
- `results/hy_cpu2/termination-note.json`：中断及历史测量界限；原始未完成 manifest 未伪造最终状态。
- `semantic-review.json`：结构化逐例评审；本报告不修改原始结果。
- `sources/`：固定官方模型／runtime manifest、README、许可证、完整 chat template、GGUF tensor metadata、官方 warning/EOG 代码及版本输出。

## 最小工程决定

1. 保留 HY 作为可能替换 MLKit 的候选，不接入生产、不发布，也不宣称当前链路验收通过。
2. 下一步若继续，应独立解决 ASR 分句／语义单元和提交边界，再原样重测，而不是靠扩大固定秒数或继续给这10句调 prompt。
3. 模型包约1.13 GB、实测进程 RSS约1.87 GiB，且CPU2推理通常数秒，移动端资源／延迟可行性仍须另行实测；没有 OPPO或Android的结论。
4. clean MT、真实音频ASR、连续字幕语义与时序应分开过门禁。无需再盲目搜索第二模型来掩盖当前句界问题。

## 媒体及派生文本许可

真实四块 ASR／译文来自 Simpleshow Japan 的 [Emoji.webm](https://commons.wikimedia.org/wiki/File:Emoji.webm)，媒体 SHA256 `ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956`， [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)。派生转写／译文含机器错误，本报告新增了诊断比较，相关派生部分继续遵守 CC BY-SA 4.0。六个人工语言控制不属于音频转写，其中官邸句是虚构语言测试，不构成新闻事实。
