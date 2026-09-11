# 决策：听译 ASR 换为 sherpa-onnx + SenseVoice（Vosk 保留为 fallback）

- 状态：accepted（待真机实测闭合精度/实时/发热三项）
- 日期：2026-09-12
- 任务：FN-51（post-MVP 听译增强；用户指令驱动，无 TASKS.md 独立卡）

## 问题

FN-14 落地的 Vosk small（`small-cn-0.22`，2022 年 Kaldi TDNN）是听译质量天花板
最低的一环：无标点输出、影视对白（BGM/多人/口音/快语速）精度弱。2024–26 年
中文端侧 ASR 换了一代（FunASR Paraformer → SenseVoice → Fun-ASR-Nano），需要在
不破坏现有 PCM/窗口/Room/MT 管线的前提下换引擎。

## 证据

### 候选对照（约束：中端机 CPU 实时、全离线、私有目录、按需下载、中英+粤/日/韩）

| 方案 | 下载包体 | 中文精度 | 标点 | 中端机实时 | 结论 |
|---|---|---|---|---|---|
| Vosk small（现状） | 44MB/语种 | 基线（弱） | 无 | ✅ | 保留为 fallback（fr/de/es 仍需它） |
| sherpa-onnx + streaming zipformer small 中英 | ~50MB | > Vosk | 无 | ✅ | 备选（包体敏感时） |
| **sherpa-onnx + SenseVoice-Small int8（选中）** | **239MB 一包五语言** | 强（官方中/粤优于 Whisper） | **自带 ITN** | ✅（A76 单线程 RTF≈0.1，RK3588 实测） | **默认引擎** |
| sherpa-onnx 2-pass | 两包之和 | 最高 | 看二遍 | ✅ | 后续增强，不在本次做 |
| whisper.cpp tiny/base | 42/78MB | 中文弱 | 有 | 真流式难做 | 拒绝 |
| Fun-ASR-Nano / Qwen3-ASR-0.6B | 800MB+ | SOTA | 有 | ❌ 中端 CPU 跑不到实时 | 拒绝（等 NPU/旗舰路线） |
| ML Kit GenAI Speech Recognition | 系统托管 | Basic≈旧系统识别；Advanced 仅 Pixel 10/11 | — | ✅ | Alpha、无 SLA、需 GMS；只观察 |

### 钉死的集成事实（均已核对源码/官方文档）

- AAR：`com.github.k2-fsa:sherpa-onnx:1.12.32`（JitPack，AAR 44.4MB，含全 ABI `.so`；
  本仓 abiFilters 只留 arm64-v8a/x86_64，包体增量约 +11MB/分包）。
- Java API（master 已核对签名）：`OfflineRecognizer(OfflineRecognizerConfig)` →
  `createStream()` → `acceptWaveform(float[], 16000)` → `decode()` →
  `getResult()` → `getText/getTokens/getTimestamps`（逐 token 起始秒，中文到字）。
- 模型（`csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`，
  commit `2365baea` 不可变引用）：`model.int8.onnx` 239,233,841 bytes +
  SHA-256 `c71f0ce0…51a2cd51`；`tokens.txt` 315,894 bytes。选用 2024-07-17 而非
  2025-09-09，因为后者是粤语微调版且**不支持标点**，而字幕可读性优先。
- 缓存隔离：`listen_translate_job.asr_model` 已存引擎标签；新标签
  `sherpa-sensevoice-2024-07-17-int8` 与任何 Vosk 标签不同，旧 cue 自动失效。

## 决定

1. 新增 `AsrEngine` 接缝（`recognize → AsrRecognition(text + timed words)`），
   `VoskAsrEngine` 原样适配（行为、文案零变化），`RealListenTranslateEngine`
   只依赖接缝。
2. 新增 `SherpaAsrEngine`（sherpa-onnx `OfflineRecognizer` + SenseVoice int8，
   ITN 开启，greedy，2 线程，语言 hint 由用户源语言映射，未知回 `auto`）
   与 `SherpaModelInstaller`（双文件、断点续传、SHA-256、仅非计费网络、
   `.ready` 标记；与 `VoskModelInstaller` 同级复用下载原语）。
3. 用户偏好 `AsrEngineChoice`（`UserPreferences`，默认 **SHERPA**，Vosk 可切回）；
   引擎选择只影响本次会话的 installer/engine/support 三件套。
4. 关闭听译开关时立即释放 native recognizer（SenseVoice 常驻约 240MB，不能
   等到退播放页；顺带修复 Vosk 路径同位置的持有）。
5. “清除听译模型”一并删除 sherpa 目录（`CacheMaintenance` + `AppContainer`
   接线）；卸载清零不受影响（仍全在私有目录）。
6. Vosk 路径、ML Kit MT、Room schema、字幕/缩略图/播放行为一律不动。

## 未选择的方案

见上表。另：不做 streaming zipformer 首遍（2-pass 留后续）；不碰 `SettingsScreen`
（共享文件，引擎切换 UI 与 SenseVoice 文案/署名行在交接中描述，由集成 owner 接）。

## 后果

- 包体：AAR 按 ABI 拆分后 release arm64 预计 +11MB（72.0 → ~83MB），以实测为准，
  记入包体账本。
- R8：`com.k2fsa.sherpa.onnx.**` 全 keep（JNI 按名反射；FN-25/26 教训）。
- License：sherpa-onnx 为 Apache-2.0；**SenseVoiceSmall 权重为 FunASR Model
  License**（官方澄清可商用、需署名），设置页须加署名行（交接中描述）。
- 真机闭合项（本环境无设备，未测）：同一批 NAS 对白 AB（CER/实时率/发热）、
  239MB Wi-Fi 下载、Release 下 `UnsatisfiedLinkError` 不出现、中端机连续 5 分钟。
- 若 JitPack 拉取不稳定，改 vendor AAR（固定 1.12.32 文件）。

## 已知限制

- fr/de/es 源语言在默认引擎下无 ASR（切 Vosk 才有；待设置页引擎开关落地）。
- SenseVoice 输出为整窗文本 + token 时间戳；跨窗长句仍按现有窗口切分语义。
