# 决策：本机听译字幕（无字幕听音出字）

- 状态：accepted（产品与存储边界）；音频取流路径见下方 **D1**（FN-10 spike）
- 日期：2026-07-14
- 任务：FN-10～FN-13（post-MVP）

## 问题

用户希望在 **无字幕** 时仍能看到翻译字幕：本机 ASR + 本机机器翻译，结果可缓存；
**不写回 NAS**；卸载应用后手机上 **不留** 听译数据与模型。

## 产品约定（已拍板）

| 项 | 决定 |
|---|---|
| 能力 | 听译：音轨 → 本机 ASR → 本机 MT → 叠层；非「同声传译」，目标为短延迟字幕 |
| 源语言 / 目标语言 | **均由用户手动选择**（不做静默 auto 作为唯一路径） |
| 持久化文本 | **原文（ASR）+ 译文均写入本地 SQLite（Room）** |
| NAS | **禁止** 写回外挂字幕或任何听译产物 |
| 卸载 | **必须清空**：仅使用应用私有存储；`allowBackup=false` 已满足备份侧 |
| 片源消失 | 打开失败 / 删除服务器 / 设置清理 → 删除对应 Room 行；可选浏览对账与 LRU |

## 证据

### 存储与卸载

- Android 卸载删除应用私有目录（`filesDir` / `cacheDir` / 应用 DB）。
- 仓库已设 `android:allowBackup="false"`（0.3.1），避免备份通道残留。
- 现有缩略图/外挂字幕缓存均在 `cacheDir` 私有路径，模式一致。

### D1：播放中如何取 PCM（FN-10）

**libVLC Android 3.6.5 Java API**（`org.videolan.libvlc.MediaPlayer`）**不暴露**
`setAudioCallbacks` / PCM 拉流接口（与 LibVLCSharp / C API 不同）。已用
`javap` 核对公开方法：仅有 `setAudioOutput`、音轨切换、音量、delay 等，无 sample 回调。

| 方案 | 结论 |
|---|---|
| A. libVLC Java 音频回调 | **不可用**（绑定层无 API） |
| B. 并行 MediaExtractor + MediaCodec 只解音轨 | **首选**：与 VLC 画面并行；本地 raw/文件已在 spike 验证；SMB 可用第二条只读随机读 |
| C. AudioPlaybackCapture 录系统/本应用混音 | 备选；延迟与格式控制差于 B；权限/机型差异大 |
| D. JNI 接 libvlc C `libvlc_audio_set_callbacks` | 维护成本高，暂不做 |
| E. 写公共 Download / 导出 SRT 默认落盘 | **禁止**（违反卸载清零与 NAS 边界） |

#### Spike 测量（FN-10）

| 项 | 值 |
|---|---|
| 入口 | debug：`PcmCaptureSpikeActivity`（`exported=true` 以便 API 34+ adb 启动） |
| 样本 | `res/raw/sample_h264.mp4`（H.264 + AAC，短样本） |
| 取流 | `MediaCodecPcmTap`：Extractor → 音频轨 → MediaCodec PCM → 16 kHz mono |
| 播放 | 同页 `VlcPlayerController` 播画面（验证并行不崩溃） |
| 设备 | Emulator `emulator-5554`（API 36，arm64） |
| logcat | `FrameNestPcmTap: pcm_tap done label=raw:… bytes=354304 mono16k=64183 peakRms=0.097` |
| 并行 | `FrameNestPlayer: first frame ready (paused)` 与 AAC MediaCodec 解码同时存在 |

结论：**路径 B 可用**；短样本约 4s 内完整出 PCM（≈64k mono-16k 样本）。CI 以
`assembleDebug` + `PcmAudioMathTest` 为主。

## 决定

1. **做听译**，作为 post-MVP；全本机，无云端默认路径。
2. **身份键** 与历史一致：`(server_id, share, path)`；另存 `content_key`
   （size + modifiedTime 摘要）防同路径换片；语言对：
   `(source_lang, target_lang)`。
3. **Room 两表**（名称可微调，语义固定）：
   - `listen_translate_job`：会话状态、覆盖进度、模型版本、语言对
   - `listen_translate_cue`：`start_ms`/`end_ms`/`text_src`/`text_tgt`/`rev`
4. **音频取流默认 B**（并行解码音轨）；产品管线不得依赖未暴露的 VLC Java PCM 回调。
5. **模型与缓存** 仅 `context.filesDir` / `cacheDir`（或应用私有外部 `getExternalFilesDir`，卸载仍删）。
   **禁止** MediaStore、公共 Download、SAF 共享目录作为默认落点。
6. **不写 NAS**；设置提供「清除听译缓存 / 清除模型」。

## 未选择的方案

- **仅字幕轨翻译（路径 A）**：不满足「无字幕也能听译」主诉求；可作后续增强。
- **云 ASR/MT**：违背全本机与隐私定位。
- **默认导出到 NAS 或公共目录**：违背卸载清零与 NAS 边界。
- **源语言静默 auto 唯一**：质量不稳；改为用户手选。

## 后果

- 任务拆分：`FN-10` PCM spike → `FN-11` Room/清理 → `FN-12` 管线+UI → `FN-13` 模型下载。
- 共享文件（`AppDatabase`、`AppContainer`、设置页）归集成 owner；功能 Agent 在交接中描述所需改动。
- 播放页叠层建议 App 自绘（原/译/双语），不强制改 libVLC 内嵌字幕渲染。
- 机型门槛与发热需在 FN-12/13 实测后写入设置文案；不承诺毫秒级同传。

## 后续可修订

- 若未来 libVLC 4.x Android 绑定暴露音频回调，可再评估与路径 B 的取舍（新决策或修订本文件）。
- 浏览目录对账清理、LRU 上限数值在 FN-11 实现时落默认值并单测。
