# Changelog

## 0.6.2-internal (2026-10-09)

- 听译先显示并缓存识别原文，译文就绪后补齐；仅译文模式仍按用户选择显示。
- 成功听译窗口不再额外固定等待，预取预算包含缓存提交并跟随播放倍速调整。
- 切到后台暂停听译与未完成的模型准备，中断阻塞的辅助 SMB 读取；回前台复用已加载模型并重新连接。
- 拖动、音轨/语言切换和退出时拒绝过期听译结果；旧音轨的失败退避不再阻塞新音轨，空译文不会反复忙重试或误记为完成。
- 补充阶段显示、取消、倍速与重连回归；保留现有本机识别/翻译、播放器默认行为和同签名更新。实际字幕延迟、发热与真实 NAS 体验仍需真机验证。

## 0.6.1-internal (2026-10-09)

- 连续失败重试保留实际续播位置，旧目录扫描不再覆盖重试后的字幕和上下集列表。
- 离开播放页时主动中止目录、字幕与听译的辅助 SMB 请求，避免等待失联连接超时才释放资源。
- 缩略图内存访问与磁盘读写/清理分离，减少滚动时等待后台磁盘锁的机会。
- 新增重试、过期结果、传输取消和缓存并发回归测试；实际 NAS、音视频和设备体验仍需真机验证。

## 0.6.0-internal (2026-10-08)

- 增加应用内检查更新、版本说明、下载进度、取消/重试和系统安装确认。更新包先校验来源、大小、SHA-256、包名、版本与安装签名；播放期间不打断观看。
- 播放会话、前后台恢复和退出清理更明确，避免过期回调影响下一部视频；保留手动暂停意图和续播进度。
- SMB 浏览取消和清理不在主线程等待失联 NAS，减少刷新、返回目录时的卡顿。
- 恢复 Actions 的 Android SDK 安装与构建检查；新增显式发布请求、固定签名与源码、三个 ABI 产物、更新清单和摘要验证。
- 发布签名由维护者配置并固定为旧版相同的证书；签名验证通过后可覆盖安装，后续同签名新版本可以在应用内更新。

## 0.5.2-internal (2026-10-02)

- 桌面图标改为午夜蓝底上的两层圆角画框，中间一个暖白播放三角。
- 从文件夹点开的片子，拖动第一张预览直接用列表里已有的文件大小和修改时间。
- 封面和拖动预览的软件解码少缓冲一些。正在播放的片子仍用原来的缓冲和硬件解码。

## 0.5.1-internal (2026-10-02)

- 文件夹里的视频封面默认三路同时取出。竖屏画面按片子比例放进格子，不再被拉满。
- 拖动进度条时，手指按住就能看到对应时间的预览图。松手后才跳转，播放中的画面不跟着远程定位。
- 播放时切到别的应用再回来，可以直接继续播放。

## 0.5.0-internal (2026-10-01)

- 拖动进度条或左右滑动时，先显示目标时刻的预览图；松手后才跳转，播放中的画面不再跟着远程定位。
- 文件夹列表里的视频封面改为看得出画面的 16:9，并标出文件大小和时长。有自带封面时先用它；否则较长的片子避开片头，纯色帧会换成后面的画面。

## 0.4.2-internal

Main playback path fixes, no behavior change on the happy path:

### Fixes

- **Replay from Ended** keeps an in-Ended seek: reopen from zero, then apply
  the target on the fresh input instead of dropping it
- **Play during Preparing** latches the intent (symmetric with pause), so the
  first frame continues into Playing without a second tap
- **Native hardening**: all `MediaPlayer` getters/calls guarded — a teardown
  race can no longer throw onto the main thread; a failed native `play()`
  surfaces a retryable error instead of crashing
- **Seek preview**: `isBuffering` is owned by Buffering events again (preview
  completion no longer hides real stalls); a preview that cannot be muted is
  cancelled instead of leaking audio
- **Audio track switch**: listen-translate cache is re-keyed only after the
  native switch succeeds
- Removed the unused `ResumeSeekGate.shouldFire` natural-fire path

### Known limitations

- Same as 0.4.1-internal; real-NAS E2E still depends on user environment

## 0.4.1-internal

Decode-path performance, no behavior change:

### Performance

- **Thumbnails**: try embedded cover art first (zero video decode); on API 27+
  decode directly at 320px via `getScaledFrameAtTime` instead of full-res
  decode + `createScaledBitmap`
- **Listen-translate PCM**: cache `MediaCodecList.findDecoderForFormat` result
  per MIME (was once per 3s window); resample 44.1/48 kHz → 16 kHz with a
  single incremental accumulator instead of per-sample multiply+divide

### Known limitations

- Same as 0.4.0-internal; real-NAS E2E still depends on user environment

## 0.4.0-internal (2026-08-24)

Internal test build after listen-translate, player product controls, brand, and
performance work (FN-10 … FN-49).

### Features

- **Listen-translate (Beta)**: on-device PCM → Vosk small ASR → ML Kit MT;
  Settings download / resume / verify; adaptive prefetch while playing
- **Player controls**: audio tracks, speed, lock, orientation lock, prev/next
  and autoplay, buffering, scrub preview, swipe seek, landscape fullscreen,
  brightness / volume gestures
- **LAN discovery**: user-initiated mDNS (`_smb._tcp`); optional deep scan
  probes subnet TCP 445; fills host only (no credentials)
- **Browse layout**: list / multi-column grid toggle (phone ≈2 cols, tablet
  ≈3–4); preference persisted
- **Brand**: confirmed logo and indigo / violet theme; full-bleed adaptive
  launcher icon

### Fixes

- Release R8 keep rules so SMBJ, Vosk/JNA, and ML Kit survive minify
- First-frame hold, rotation surface, non-blocking player exit
- Browse listing hang after playback; visible-first thumbnail work
- Listen empty-result recovery and SMB audio reconnect after lock screen
- Critical interaction / accessibility polish on phone and tablet

### Performance

- SMB session reuse, Room write reduction, PCM allocation cuts, thumbnail
  disk accounting, bounded LAN scan, cache maintenance off the main thread

### Known limitations

- Listen-translate is Beta (Vosk small + ML Kit); not claimed as general
  accurate captions
- Real NAS end-to-end still depends on the user environment
- Release APK is debug-signed for overlay install on the existing test device

## 0.3.1-internal (2026-07-14)

Post-MVP batch: size, share probe, settings, debug isolation.

### Improvements

- **APK size**: ABI splits (`arm64-v8a`, `x86_64` + universal); release minify + shrinkResources
- **Share list**: probe common home-NAS share names when MS-SRVS unavailable
- **Thumbnails**: configurable concurrency 1–2 in Settings (default 1)
- **Spikes**: Player/SMB spike activities moved to **debug** source set only
- **Security**: `allowBackup=false`
- **Docs**: `docs/NAS-ACCEPTANCE-CHECKLIST.md` for real-NAS sign-off

## 0.3.0-internal (2026-07-14)

Internal test build after Waves 0–3 (FN-00 … FN-09).

### Features

- Add / edit / delete / test SMB servers; credentials encrypted (Keystore-backed)
- Browse shares and directories; video/subtitle filtering; list thumbnails (single-concurrency)
- Product player: first-frame ready before play, seek, progress history / resume
- Embedded + sidecar subtitles (SRT/ASS/SSA/VTT), delay & font size
- Adaptive phone/tablet shell; settings for cache clear, subtitle language, diagnostic export

### Known limitations

- Real NAS E2E depends on user environment (see `docs/RELEASE-ACCEPTANCE.md`)
- Share enumeration is candidate/default-share based (no full MS-SRVS)
- Thumbnail extract uses MediaMetadataRetriever (not all codecs/containers)
- Spike activities exist for debug but are not exported

### Security notes

- Passwords never stored in Room or URLs
- Diagnostic export is redacted via `CredentialRedactor`
- No real NAS host/password should appear in git or APK assets
