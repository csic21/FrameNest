# Changelog

## 0.6.11-internal (2026-10-16)

- 译文尚未就绪时保留可读原文；双语模式延迟显示轻量提示，避免快速翻译反复闪烁。仅译文模式不再无说明地空白，展开快照明确标注打开时的译文状态。
- 听译开关附近直接显示准备、处理和错误反馈；运行文案使用“处理中”，未识别提示不再错误指定某个识别引擎。
- 手机和平板尺寸的 Android UI 回归均通过；公开日语样片完成真实参考→翻译、字幕跨度识别→翻译、当前3秒生产路径→翻译对照。发现识别、切词与翻译模型各自的误差，不能将本次显示改进当作准确率提升。
- 固定3秒/6秒、两段公开视频共81窗对照发现6秒仍有切词、重复与译文信息损失；本版不采用该候选。继续保留现有3秒窗、SenseVoice/Vosk、ML Kit、签名和数据。Hy-MT仍关闭，不新增模型下载。
- AI字幕仍可能误识或误译；尚未解决快速且精确的日语双语字幕目标，未测OPPO/NAS端到端速度。

## 0.6.10-internal (2026-10-15)

- 改用固定 SHA-256 的 sherpa-onnx 1.13.8 源码构建 ASR-facing/no-TTS AAR；SenseVoice 模型、Vosk 与 ML Kit 翻译链路不变，Hy-MT 保持关闭和隐藏。
- 识别缓存带独立的源码产物身份，避免复用旧运行库结果。升级有机会改善某些识别结果，但不保证准确率提升；目前没有这份源码构建的同条件实测提速结论。
- 设置中可离线读取完整开源许可、源码链接及 Android simple-sentencepiece 变更说明；发布附件同时提供原始 AAR、完整 NOTICE 和来源记录。
- 固定公开语料上，源码运行库两次 Android 会话的 396 行文本与时间戳与 stock 1.13.8 一致（耗时不作等价比较）；114 秒 Kishida 标注片段的 Android 参考结果由旧版的 CER 10.78% 降至 38/436（约 8.72%），这两次源码会话重现 stock 1.13.8 的结果。仍会将「広島サミット」识别为「広島さと」、「握り拳」识别为「握りこ」；不把有限语料结果当作普遍准确率保证。
- 原生库 PT_LOAD 与 APK ZIP 的 16KB 静态对齐检查通过。arm64 16KB 真机尚未验收；ORT 未取整 RELRO 末端仍保留静态警告，未发现已知 LOAD 保护重叠或已证实的运行失败。
- 保留原安装标识、签名、模型及用户数据；AI 识别与翻译仍可能出错，不承诺逐字同传或未经实测的加速倍数。

## 0.6.9-internal (2026-10-15)

- 应用显示名称改为「映舟」，保留原安装标识、签名和数据兼容性。
- 保留 SenseVoice/Vosk 与 ML Kit 的本机听译方案。翻译准备可与有效语音识别重叠；阶段计时覆盖模型准备、音频读取、识别、翻译和字幕可用时点。
- 修复字幕任务切换、拖动、退出与迟到结果隔离；双语各自保留显示空间，长字幕可展开完整文本。
- 本版不开放新的 Hy-MT2 日语整段模式，不打包其原生引擎，也不会下载约 1.13 GB 模型。该开发方案的实际视频仍有指代和句间关系误译，不能把功能测试通过当作语义验收成功。
- 云端 Android 模拟器的既有 ML Kit 路径：首次安装模型后首条双语可用约 51.1 秒，模型已下载的新会话约 3.8 秒。这不是 OPPO、真实 NAS 或屏幕实际绘制的延迟保证。手机速度、内存与发热尚未实测；快速、可靠的日语双语字幕目标仍未完成。
- 启动、默认听译链路、两种宽度的双语展开与重置测试已通过；最终发布构建另行校验签名和全部 APK。AI 识别与翻译可能有错，不保证逐字同传。

## 0.6.8-internal (2026-10-12)

- 听译读取 SMB 视频的音频时改用会话级 1 MiB 块缓存（最多 16 块，LRU）。之前每个 3 秒窗口都会让解码器对远程文件发出大量小读取并重复解析容器索引，同一窗口可能产生数百次网络往返，字幕因此明显落后播放。
- 听译诊断面板的“音频读取/解码”新增本窗口的 SMB 读取次数和字节数，便于判断后续瓶颈；不包含路径、内容或凭证。
- 播放、缩略图、SMB 客户端与字幕路径不变；保留本机模型、听译缓存、用户数据和同签名覆盖升级。
- 未在真实 NAS 上测量往返与端到端首句延迟，请以安装后的听译诊断数值为准。

## 0.6.7-internal (2026-10-11)

- 统一日志与错误提示的凭证脱敏，覆盖带空格、转义引号、换行和异常 URL 的场景；诊断缓冲增加单条长度上限。
- 拒绝含数据的模型 ZIP 目录条目，避免绕过解压资源限制。
- 将翻译/SMB 间接依赖中的 OkHttp、Okio、Bouncy Castle 更新至已修复已知漏洞的版本，保留严格产物摘要验证。
- 保留 0.6.6 的听译优化、用户数据和同签名覆盖升级。

## 0.6.6-internal (2026-10-11)

- 听译首次开启和远距离拖动后优先解码当前所需音频，连续处理时恢复预读。
- 仅原文模式跳过翻译模型准备与翻译；翻译失败时复用最近窗口的原文，减少重复识别。
- 修复 SenseVoice 英文子词被拆开、中文字符多余空格与极短字幕一闪而过；更新文本缓存身份，旧结果按需重新生成。
- 新增“暂停并准备后续字幕”，前台准备约 12 秒连续字幕并显示真实就绪量，完成后保持暂停，由用户按播放继续。
- 保留本机模型、原 NAS 数据路径与同签名覆盖升级；不宣称未经真机测量的加速倍数。

## 0.6.5-internal (2026-10-10)

- 听译字幕改为贴合视频画面底部（与 CC 字幕同一区域），不再固定在屏幕底部。
- 开启 CC 时听译字幕显示在 CC 正上方分层并存，互不遮挡；关闭听译后 CC 行为不变。

## 0.6.4-internal (2026-10-10)

- 修复已下载离线识别模型后开启听译立即闪退：识别模型始终从本机私有文件加载，不再误走应用安装包资源的原生路径。
- 听译模型下载、缓存、播放与 SMB 行为不变。

## 0.6.3-internal (2026-10-10)

- 修复 SenseVoice 首次安装校验循环，补齐 tokens 摘要，下载写入、续传和解压增加明确上限。
- 修复空白覆盖记录遮挡同起点短句；默认仅跳过数字零，弱短语音保留完整 PCM 和媒体时间轴。
- 听译缓存接入容量、保留期限及活动租约，明确清除后拒绝迟到回写。
- SMBJ 默认签名及 SMB3 加密，旧 NAS 仅能通过每服务器明确选择签名兼容模式；独立 libVLC 传输不由此保证加密。
- 增加本机窗口诊断、默认关闭的保守静音实验和安全/并发回归；固定同签名更新链保持不变。无真机性能、热功耗或 NAS 兼容性实测。

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
