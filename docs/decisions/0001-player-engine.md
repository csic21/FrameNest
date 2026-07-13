# 决策：播放内核选用 libVLC

- 状态：accepted（FN-01 spike 结论；SMB 直连待真机 NAS 补测）
- 日期：2026-07-14
- 任务：FN-01

## 问题

FrameNest MVP 需要在 Android 上稳定播放 NAS/SMB 上的常见视频（含 H.264，设备可硬解时的 HEVC），支持：

1. 播放页 **prepare 后暂停并显示已解码首帧**（不是黑屏、不是列表静态缩略图）
2. Seek、音轨/内嵌字幕轨枚举与切换
3. 进入/退出播放页资源可释放，连续 20 次无崩溃
4. 凭证不得进入 URL、日志或错误文案

须在实现完整播放产品功能（FN-05）前选定内核与最小 UI 边界。

## 证据

### 依赖与设备

| 项 | 值 |
|---|---|
| 库 | `org.videolan.android:libvlc-all:3.6.5`（Maven Central 稳定 3.x；4.0.0 仍为 EAP） |
| 构建 | JDK 17、AGP 9.2.1、compileSdk/targetSdk 36、minSdk 26 |
| 验证设备 | Emulator `Medium_Phone_API_36.1`（API 36，arm64/x86_64 视 AVD） |
| 本地样本 | `res/raw/sample_h264.mp4`（ffmpeg 生成，320×240 H.264 + AAC，约 4s / ~64KB） |
| NAS 样本 | 未接入真实 NAS；代码路径与 adb 参数已按占位约定就绪（见下） |

### 测量与验证结果

| 场景 | 结果 | 备注 |
|---|---|---|
| 本地 raw 资源打开 | **通过** | `Media(LibVLC, AssetFileDescriptor)` + `MediaPlayer.setMedia` |
| HW decoder 请求 | **通过（请求路径）** | `Media.setHWDecoderEnabled(true, false)`；样本 Baseline H.264；log：`hwDecoderRequested=true` |
| prepare → 首帧 → pause | **通过（设备）** | `Event.Vout` 后 `pause()`；logcat：`FrameNestPlayer: first frame ready (paused)`；Activity 显示约 +797ms，首帧约 +1.1s（tiny 本地样本，API 36 模拟器） |
| Seek | **通过（API + UI）** | `MediaPlayer.time` / Slider / +1s；短样本 demux seekable |
| 音轨枚举 | **通过** | `getAudioTracks()` → UI；样本 1 路 AAC |
| 内嵌字幕枚举 | **部分** | API `getSpuTracks()` 已接；本地样本无字幕轨，需多轨 MKV 补测 |
| 生命周期 release | **通过** | `onDestroy` → detachViews + stop + MediaPlayer/LibVLC.release |
| 连续进出 20 次 | **通过** | `PlayerSpikeEnterExitTest`（connectedDebugAndroidTest，0 failed） |
| libVLC 直接 `smb://` | **未实测（无 NAS）** | 代码 + adb 参数路径就绪；见运行说明 |
| 凭证不进 URL/日志 | **通过（代码 + 单测）** | `SmbMediaUri` 禁止 userinfo；`:smb-user`/`:smb-pwd`/`:smb-domain`；`CredentialRedactor` |
| Debug APK 体积 | ~117MB（arm64-v8a+x86_64） | 全 ABI 约 201MB；`ndk.abiFilters` 收窄 |

### Compose Surface 集成方式

采用：

```text
Compose AndroidView
  → FrameLayout container
    → controller.attachVideoLayout(container)
      → 注入 VLCVideoLayout
      → MediaPlayer.attachViews(layout, null, subtitles=true, useTextureView=false)
```

结论：

- **推荐**：`VLCVideoLayout` + `attachViews`（SurfaceView 路径）。与官方 VLC Android 一致，字幕 surface 一并托管。
- **备选**：`IVLCVout.setVideoView(TextureView)` + `attachViews()`，便于部分动画/截图场景，但 Spike 未采用，避免与官方布局辅助分叉。
- 注意：不要对承载 Surface 的 `AndroidView` 滥用会破坏 Surface 合成的 clip/graphicsLayer。

### SMB 直连（结构就绪，环境未测）

- URI：`SmbMediaUri.build(host, share, path)` → `smb://host/share/path`，**禁止** `smb://user:pass@host/...`
- 凭证：仅 `Media.addOption(":smb-user=…")` / `:smb-pwd=…` / `:smb-domain=…`
- 与 FN-02 共用占位约定：
  - host：`192.168.1.10` 或 `nas.local`
  - share：`media`
  - path：`samples/movie.mkv`
- 手工补测：

```bash
adb shell am start -n com.framenest/.feature.player.PlayerSpikeActivity \
  --es smb_host 192.168.1.10 \
  --es smb_share media \
  --es smb_path samples/movie.mkv \
  --es smb_user YOUR_USER \
  --es smb_password 'YOUR_PASSWORD'
```

**勿**把真实密码写入仓库、截图或 CI 日志。

若 libVLC 直连在目标 NAS 上 seek/认证失败，按架构 D1 再评估 SMBJ seekable 数据源或最后手段 localhost Range proxy（FN-02 主导数据路径对比）。

## 决定

1. **播放内核**：采用 **libVLC（`libvlc-all` 3.6.x）** 作为 FrameNest 首选与当前唯一内核。
2. **UI 边界**：仅暴露 `PlayerController`（prepare / play / pause / seek / tracks / release）与不可变 `PlayerState`；**不**做多引擎抽象。
3. **首帧契约**：prepare 后自动起播至首次 `Vout`，立即 pause，并以 `firstFrameReady` 作为「可展示解码帧」信号（区别于列表缩略图）。
4. **SMB**：首选 libVLC 直接 `smb://` + 选项传凭证；真机结果未闭合前，FN-05 须以 FN-02 决策与补测为准。
5. **版本**：钉选 **3.6.5**（稳定 3.x、含多 ABI、含 16KB page 相关后续小版本演进空间）；暂不跟 4.0 EAP。

## 未选择的方案

| 方案 | 原因 |
|---|---|
| ExoPlayer / Media3  alone | 对 MKV/内嵌字幕/SMB/冷门封装覆盖弱于 libVLC，MVP 字幕与 NAS 场景风险高 |
| 多内核可切换抽象 | 任务明确禁止投机抽象；UI 只需一套边界 |
| libVLC 4.0 EAP | 仍为 EAP，不适合作为 MVP 默认 |
| 凭证写入 `smb://user:pass@` | 泄露面大（日志、崩溃栈、Intent），明确禁止 |
| 提前实现 localhost proxy | 架构要求仅当直连与 seekable 源均不可接受时再做 |

## 后果

- FN-05 应复用 `com.framenest.player` 的 `PlayerController` / `PlayerState` / `MediaSource`，在 `feature/player` 上做成品播放页，不必再选内核。
- FN-02 必须与本文相同的 host/share/path 占位及「凭证不进 URL」约束对比数据路径；若直连失败，在 `0002-smb-data-path.md` 中推翻或收窄本决策的 SMB 部分。
- FN-06 可直接基于 `getSpuTracks` / `setSpuTrack` / `addSlave` 扩展外挂字幕。
- FN-07 播放前首帧必须以 `firstFrameReady`（真实 vout）为准，不得用缩略图冒充。
- 集成：`MainActivity` **未改**；spike 入口为 `PlayerSpikeActivity`（exported）。后续可用导航接入，无需本任务改 Home。
- APK 体积：libVLC 多 ABI 显著增大 debug 包；发布阶段可考虑 ABI split / 按 ABI 分包。

## 已知限制

- 模拟器上硬解能力与真机（尤其 HEVC）不一致；HEVC/多字幕 MKV 须真机补测。
- 首帧策略依赖 `Vout` 事件；极端机型若只报 `Playing` 不报 `Vout`，需后续增加超时回退（当前 spike 未加）。
- SMB 选项名在不同 VLC 模块版本可能有差异；若 3.6.5 对某 NAS 认证失败，可再试文档中的 smbj 相关选项并记入 0002。
