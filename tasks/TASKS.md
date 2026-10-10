# Agent 任务卡

每段都可以直接作为独立 Agent 的任务说明。Agent 还必须遵守仓库根目录
`AGENTS.md`。任务未满足依赖时，不要提前开始正式实现。

## FN-00：Android 工程骨架

**依赖**：无  
**拥有路径**：根构建文件、`app/` 基础代码、版本目录、测试和 CI 基础配置  
**目标**：创建最小可编译 Android 项目，不实现产品功能。

工作内容：

- Kotlin、Compose、Material 3，应用 id 使用 `com.framenest`。
- 只建一个 `app` module；选择合理的 minSdk，target/compile SDK 使用本机稳定可用版本。
- 建立主题、`Application`、`MainActivity` 和空首页。
- 添加最小单元测试和 Compose smoke test；记录 JDK/SDK/Gradle 要求。
- 只加入骨架实际需要的依赖，不提前加入 VLC、SMB、Room。

验收：`assembleDebug`、单元测试和 lint 可运行；debug APK 可在模拟器启动；README
补充精确构建命令。

## FN-01：播放内核技术验证

**依赖**：FN-00  
**拥有路径**：`player/**`、`feature/player/**` 的 spike 页面、对应测试、播放决策记录  
**目标**：验证 libVLC 在目标设备上的本地播放、暂停首帧、seek、轨道和生命周期。

工作内容：

- 用一个本地视频建立最小播放页，验证硬解、prepare 后暂停首帧、seek 和 release。
- 验证内嵌音轨/字幕轨枚举；记录 Compose Surface 集成方式。
- 与 FN-02 用同一 NAS 样本验证 libVLC 直接 SMB 播放，不在 URL 中嵌入密码。
- 只抽取 UI 当前需要的最小播放器边界和状态。
- 写 `docs/decisions/0001-player-engine.md`，包含测量数据和已知限制。

验收：连续进入/退出播放页 20 次无崩溃；seek 可用；暂停状态能显示解码帧；错误
不会泄露凭证。

## FN-02：SMB 与数据路径技术验证

**依赖**：FN-00  
**拥有路径**：`smb/**`、测试工具页、对应测试、SMB 决策记录  
**目标**：选定 SMB2/3 客户端和播放器/取帧可复用的数据路径。

工作内容：

- 以 SMBJ 为首选，验证认证、列 share、列目录、metadata、随机读取和断线行为；若
  失败，再对比 jcifs-codelibs。
- 与 FN-01 测试同一 NAS 和样本，比较直接 `smb://`、seekable 数据源；只有两者
  不可接受时才做最小 localhost Range proxy。
- 测量首次读取、顺序吞吐、随机 seek 和错误恢复；所有日志脱敏。
- 写 `docs/decisions/0002-smb-data-path.md`。

验收：可连接 SMB2/3、列目录、读取大文件任意区间；错误区分认证/网络/不存在；
决策记录能直接指导 FN-04、FN-05、FN-07。

## FN-03：手机/平板自适应 App 外壳

**依赖**：FN-00  
**拥有路径**：`app/navigation/**`、`app/ui/**`、主题及 UI 测试  
**目标**：建立不依赖真实数据的导航和响应式页面框架。

工作内容：

- 手机使用底部导航；较大窗口使用 NavigationRail 和 list-detail 占位布局。
- 入口：服务器、最近播放、设置；建立服务器→浏览→详情/播放的导航占位。
- 正确处理系统返回、旋转和窗口尺寸变化。
- 使用假数据，不提前实现 SMB 或播放器逻辑。

验收：手机 portrait 与平板 landscape 截图/测试通过；旋转不丢失当前导航目标；
无固定设备宽度判断。

## FN-04：服务器管理与 SMB 浏览

**依赖**：FN-02、FN-03  
**拥有路径**：`data/server/**`、`feature/servers/**`、`feature/browser/**`、相关测试  
**目标**：让用户安全保存服务器并浏览 share、目录和视频文件。

工作内容：

- 添加/测试/编辑/删除服务器；端口默认 445。
- Room 保存非敏感字段和凭证别名；密码使用加密存储。
- 浏览 share/目录，支持返回、刷新、名称排序、加载/空/错误状态。
- 只识别 MVP 视频和字幕扩展名；500 条目录不阻塞主线程。

验收：重启后服务器仍存在且无明文密码；认证失败可修改重试；浏览 500 条目录 UI
保持响应；单元测试覆盖排序和错误映射。

## FN-05：SMB 播放纵切面与历史

**依赖**：FN-01、FN-02、FN-03；使用 FN-04 定义的文件模型  
**拥有路径**：`player/**` 正式实现、`feature/player/**`、`data/history/**`、相关测试  
**目标**：从远程文件点击到可播放、可 seek、可恢复进度。

工作内容：

- 按 0001/0002 决策接通 SMB 视频，不自行引入另一条数据路径。
- 提供 loading、ready、playing、paused、ended、error 和 retry。
- 周期性且在退出时保存进度；接近片尾时标记完成。
- 处理生命周期、旋转、音频焦点和后台行为的最小正确实现。

验收：1080p 样本首次播放、seek、退出续播通过；断网显示可重试错误；20 次进出无
资源泄漏或崩溃；历史规则有单元测试。

## FN-06：字幕

**依赖**：FN-04、FN-05  
**拥有路径**：`feature/subtitle/**`、必要的播放器字幕扩展、相关测试  
**目标**：实现内嵌字幕选择和当前 SMB 目录外挂字幕支持。

工作内容：

- 枚举并切换内嵌字幕轨。
- 扫描当前目录并按视频 basename 匹配 SRT/ASS/SSA/VTT。
- 自动优先用户语言与 `.ass`/`.ssa`/`.srt`，同时允许手动选择。
- 支持字幕延迟和字号；UTF-8 优先，非 UTF-8 失败时提供明确提示或受控回退。

验收：UTF-8 SRT 自动加载；ASS 和内嵌轨可手动切换；匹配排序有表驱动测试；字幕
加载失败不影响视频播放。

## FN-07：列表缩略图与播放前首帧

**依赖**：FN-02、FN-04、FN-05  
**拥有路径**：`data/thumbnail/**`、浏览列表缩略图 UI、播放器首帧状态、相关测试  
**目标**：分别完成静态列表封面和真实播放器首帧预渲染。

工作内容：

- 按 0002 决策复用远程数据路径，生成任务默认单并发。
- 缓存 key 包含 server/share/path/size/modifiedTime；磁盘缓存可清理。
- 首选 10 秒，短视频取约 20%；失败/黑帧再尝试有限候选点并记录退避。
- 列表只显示缓存位图；播放页 prepare 后暂停并对 UI 暴露 first-frame-ready。

验收：首次浏览渐进出图且滚动不卡顿；再次浏览命中缓存；失败项不无限重试；播放
页的“可播放”状态以真实首帧回调为准。

## FN-08：自适应体验与无障碍打磨

**依赖**：FN-04、FN-05  
**拥有路径**：各 feature 的响应式 UI 与 UI 测试；不改底层数据实现  
**目标**：将真实服务器、浏览和播放能力接入手机与平板布局。

工作内容：

- 手机保持清晰单列；平板使用服务器/列表/详情的 list-detail 体验。
- 播放页横屏、沉浸模式、返回行为、触控目标、TalkBack 描述和键盘基础操作。
- 空状态、长文件名、加载和错误在两类窗口均正确显示。

验收：发布验收流程可在手机和平板不迷路完成；旋转不丢当前目录或选中项；关键
控件具有语义描述和足够触控尺寸。

## FN-09：稳定性、性能与测试 APK

**依赖**：FN-04 至 FN-08  
**拥有路径**：跨功能集成、测试、发布配置和文档  
**目标**：关闭集成问题并产出可分发的内部测试 APK。

工作内容：

- 完成 `docs/PRODUCT.md` 全部发布验收场景并记录设备/NAS/样本。
- 检查崩溃、ANR、内存、播放器释放、大目录和缩略图缓存上限。
- 提供清理缓存、默认字幕语言和脱敏日志导出。
- 生成版本号、变更说明、已知问题和 debug/internal APK。

验收：所有自动检查通过；发布验收记录完整；APK 可安装升级；无明文凭证、密钥或
真实 NAS 地址进入产物和仓库。

---

## FN-10：听译 — 本机 PCM 取流 spike

**依赖**：FN-01、FN-05（播放路径）  
**拥有路径**：`docs/decisions/0005-listen-translate.md`、debug
`feature/listen_translate/spike/**`、可选 `player/audio/**` 最小类型、相关测试  
**目标**：验证播放同时能在本机稳定拿到 PCM（供后续 ASR），不写 NAS、不落公共目录。

工作内容：

- 确认 libVLC Android 绑定是否提供音频 sample 回调；若无，采用 **MediaExtractor +
  MediaCodec 并行只解音轨**（决策 0005 路径 B）。
- Debug Activity：本地 `sample_h264`（或可选文件）VLC 播放 + PCM 统计（采样率、
  字节数、RMS、覆盖时间轴）。
- PCM 环形缓冲/临时文件仅 `cacheDir`；日志脱敏。
- 更新决策 0005 的 D1 证据表。

验收：连续播放样本期间 PCM 持续入账；播放不崩溃；`assembleDebug` + 相关单测通过；
交接说明 adb 启动命令。

## FN-11：听译 — Room 缓存与清理

**依赖**：FN-10（音频路径已定）、FN-04/FN-05（identity / AppDatabase）  
**拥有路径**：`data/listen_translate/**`、相关单测；**共享** `AppDatabase` /
`AppContainer` / 设置清理 在交接中描述，不抢写除非任集成 owner  

**目标**：听译结果只存本机 SQLite；片源没了或用户清理可删；卸载后无残留（私有存储）。

工作内容：

- 表：`listen_translate_job`、`listen_translate_cue`（含 `text_src` + `text_tgt`、
  `source_lang`/`target_lang`、`content_key`）。
- Repository：upsert cue、按 identity 查询、`covered_until_ms`、purge API。
- 清理触发：打开失败、删除服务器、设置「清除听译缓存」；可选 LRU。
- 禁止任何 SMB 写回；模型路径预留仅私有目录。

验收：单测覆盖 identity 隔离、content_key 失效丢弃、purge；无公共存储路径。

## FN-12：听译 — 管线与播放页 UI

**依赖**：FN-10、FN-11  
**拥有路径**：`feature/listen_translate/**`、播放页叠层/开关的最小接入（共享
导航/Player 接线写交接）、相关测试  

**目标**：用户打开听译、手选源/目标语言，边播边出字，结果进 Room；二次进入命中缓存。

工作内容：

- 窗口化 ASR → MT（模型细节可先 stub/接口，真模型可与 FN-13 并行接线）。
- 播放页：开关、源语言、目标语言、显示模式（原文/译文/双语）。
- Seek：已有 cue 直接显示；空洞处从当前位置补跑。
- 不改坏现有字幕轨/外挂字幕行为。

验收：无字幕样本可出字（或 stub 管线可演示时间轴 cue）；Room 有原文+译文；
断网（模型已在本地时）可用；手机布局可用。

## FN-13：听译 — 模型下载与设置

**依赖**：FN-12（或与 FN-12 约定接口后并行）  
**拥有路径**：模型存储与下载、`feature/settings` 听译相关项（共享设置页写交接）、
`CacheMaintenance` 扩展描述  

**目标**：ASR/MT 模型按需下载到 **仅** app 私有目录；设置可看占用并清除；卸载无残留。

工作内容：

- 下载状态机、校验、版本字段写入 job。
- 设置：听译缓存占用、清除听译、清除模型；文案说明全本机与卸载即删。
- 禁止默认写入 Download/MediaStore。

验收：卸装或清数据后模型与 DB 均不在；下载失败有可理解错误且无凭证泄露。

> **说明（纠偏）**：FN-12/13 任务卡允许 stub/占位包，**不等于**听译产品完成。
> 真实「听音出字」必须通过 **FN-14**。

## FN-14：听译 — 真本机 ASR/MT（产品可用）

**依赖**：FN-10（PCM 路径）、FN-11、FN-12、FN-13（store/UI 可复用）  
**拥有路径**：`feature/listen_translate/**` 引擎实现、`player/audio/**` 产品接线、
真模型包与下载、相关测试  

**目标**：**无字幕片也能听出可读译文**（短延迟字幕，非同传），全本机。

工作内容：

- 将 FN-10 MediaCodec PCM 接到**产品播放路径**（含 SMB 第二条只读或等价方案）。
- 接入可运行的本机 ASR（如 whisper.cpp / sherpa 量化小模型），按窗口出 `text_src`。
- 接入本机 MT（ML Kit on-device 或量化 NMT），出 `text_tgt`；写入既有 Room。
- 模型仍仅 `filesDir`；设置占用/清除沿用 FN-13。
- UI 文案区分「演示/未就绪」与「真模型已装」。

验收（产品）：

1. 无字幕样本（或关闭字幕轨）开启听译后，出现与对白相关的原文/译文（非纯时间戳模板）。  
2. 断网可用（模型已下载）。  
3. 不写 NAS；卸载无残留。  
4. 中端机连续播 ≥5 分钟不崩溃；失败有可理解提示。  

## FN-15：播放器拖拽与控制交互优化

**依赖**：FN-05、FN-08
**拥有路径**：`player/**`、`feature/player/**`、相关单测与交接记录
**目标**：让播放中的进度拖拽能及时预览目标画面，同时保持 SMB seek 稳定。

工作内容：

- 播放中拖动进度条时，对 libVLC 快速 seek 做节流；松手后执行一次精确 seek。
- 暂停态保持单次 seek + 帧预览，避免反复 play/pause。
- 避免播放进度更新导致视频 Surface 无效重绑定。
- 播放/暂停使用一个随状态切换的主按钮，不改变现有无障碍语义。
- 不在没有真机数据时调整 SMB 缓存或引入新的缓存层。

验收：快速拖动不会为每个 pointer event 发 seek；播放中能逐步预览目标画面；松手落点
精确；暂停态 seek、首帧、旋转和播放状态机单测/构建不回归；手机和平板布局可编译。

## FN-16：播放器控制补齐（音轨 / 倍速 / 双击快进退）

**依赖**：FN-05、FN-08、FN-15  
**拥有路径**：`player/**`、`feature/player/**`、相关单测与交接记录  
**目标**：补上常见播放控制缺口，不引入新协议或缓存层。

工作内容：

- 播放页音轨选择 UI（复用已有 `audioTracks` / `selectAudioTrack`）。
- 倍速：0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0，点击循环；`setRate` 跨 prepare 保持。
- 播放中双击画面左/右半区 ±10s；单击仍切换控制条（带短延迟防误触）。
- 不改 SMB 数据路径、不引入新依赖。

验收：音轨可切换；倍速状态可见且生效；双击快进退夹紧到片长；相关纯逻辑单测 +
`:app:compileDebugKotlin` 通过。

## FN-17：播放器锁屏与方向锁

**依赖**：FN-08、FN-16  
**拥有路径**：`feature/player/**`、相关单测与交接记录  
**目标**：躺着观看时减少误触，并可选冻结当前屏幕方向。

工作内容：

- 控制锁：隐藏控制条；屏蔽亮度/音量/双击 seek；点按仅显示解锁入口；返回键先解锁。
- 方向锁：冻结当前旋转（含反向横屏）；离开播放页恢复系统旋转。
- 错误态自动解锁，确保重试可达。
- 不改播放内核 / SMB 路径。

验收：锁定后无法点到 seek/播放；可解锁；方向锁开关可编译且有纯逻辑单测；离开播放页恢复方向。

## FN-18：同目录上一集 / 下一集与连播

**依赖**：FN-05、FN-17  
**拥有路径**：`feature/player/**`、`navigation/FrameNestApp.kt`（仅 sibling 导航）、相关单测与交接  
**目标**：同一目录内按文件名顺序切换视频，播完可自动进下一集。

工作内容：

- 首帧后列出同目录视频文件（与浏览一致的扩展名与大小写不敏感排序）。
- 控制条上一集 / 下一集；显示 `n / total`。
- 自然 Ended 后约 1.5s 自动打开下一集（用户重播则取消）。
- 切换时 replace 当前 player 路由，返回不经过中间集。
- 不改 SMB 协议层。

验收：多视频目录可 prev/next；单文件按钮禁用；纯逻辑单测 + 编译通过。

## FN-19：播放缓冲提示

**依赖**：FN-05  
**拥有路径**：`player/**`、`feature/player/**`、相关单测与交接  
**目标**：弱网 / SMB seek 后给出可理解的缓冲进度，不改状态机主路径。

工作内容：

- 消费 libVLC `Buffering` 事件（0–100%）；写入 `PlayerState.isBuffering` / `bufferPercent`。
- 不改 phase：播放中再缓冲仍保持 Playing。
- 首帧前加载文案可带百分比；播放中显示中心缓冲指示与控制条状态行。
- 纯逻辑单测 + 编译。

验收：缓冲事件更新状态；100% 清除 isBuffering；Error 不盖缓冲层。

## FN-20：播放器产品体验与无障碍收口

**依赖**：FN-16..FN-19  
**拥有路径**：`feature/player/**`、播放页相关文案、相关单测与交接记录  
**目标**：让控制层在手机窄屏、大字号和沉浸播放下都可达、不挡住视频。

工作内容：

- Playing 状态下控制层延时自动收起；锁定、非 Playing 和打开设置面板时不误收起。
- 窄屏或大字号时将主操作与倍速/画面比例分行，保持触控面积。
- 听译、字幕、音轨改为统一底部面板，遵守导航栏安全区，不压缩视频表面。
- 清理播放页中面向读屏的内部 test tag，换成可理解的本地化操作文案。

验收：自动收起与布局决策有纯逻辑单测；手机/平板配置可编译；
unit test、lint、debug/release assemble 通过。

## FN-21：听译模型交付可靠性

**依赖**：FN-14、FN-20  
**拥有路径**：`feature/listen_translate/asr/**`、`feature/listen_translate/mt/**`、
`feature/listen_translate/ListenTranslateControls.kt`、`data/settings/UserPreferences.kt`、
`ui/screens/SettingsScreen.kt`听译模型区、相关文案/测试/交接  
**目标**：大模型下载可恢复、可校验，默认不意外消耗移动流量，并如实告知当前听译质量边界。

工作内容：

- Vosk ZIP 使用 `.part` 断点续传，安装前核对长度、ZIP CRC、目录结构与固定 SHA-256。
- 模型下载默认仅非计费网络；设置可显式允许移动网络，Vosk/ML Kit 共用同一策略。
- 下载失败保留可恢复的部分文件，清理模型时一并删除；日志不包含 URL 参数或凭证。
- 将听译状态本地化，文案明确 Vosk small + ML Kit 是 Beta，不宣称通用精准字幕。

验收：续传决策、SHA-256 校验、网络策略有单测；所有自动检查通过；
不做真机移动网络切换与真模型下载验收。

## FN-22：SMB 列表性能与发布包体收口

**依赖**：FN-07、FN-09、FN-21  
**拥有路径**：`data/thumbnail/**`、`data/server/BrowseRepository.kt`、
`app/build.gradle.kts`、`AndroidManifest.xml`、备份规则、包体/性能文档、相关测试与交接  
**目标**：减少浏览和缩略图队列的重复 SMB 认证/连接，并清理发布包中不必要的本地符号。

工作内容：

- 每个缩略图 worker 复用自己的 SMB 会话；服务器切换、配置变更、断线或 worker 取消时关闭。
- 连续目录浏览复用同一服务器会话；连接类错误后废弃，下次刷新重连。
- 密码仅在新建连接时短暂取出并立即清零，不进入日志或会话 key。
- 发布包不再对所有 `.so` 全局保留 debug symbols；重新记录各 ABI 实际产物大小。
- 显式禁止云备份与设备迁移导出应用数据，保持 SMB 凭证/模型/历史的本机边界。

验收：会话复用/切换决策有单测；unit test、lint、debug/release assemble 通过；
记录包体对比；不做真 NAS 延迟和吞吐实测。

## FN-23：播放优先的自适应预翻译缓存

**依赖**：FN-14、FN-21、FN-22  
**拥有路径**：`feature/listen_translate/**`、`feature/player/PlayerViewModel.kt`、
`data/listen_translate/**`的最小调用、相关文案/测试/交接  
**目标**：利用文件可随机读特性，在不抢播放缓冲的前提下预先生成未来字幕并持久化。

工作内容：

- 当前位置缺口永远最高优先级；命中后向前扫描第一个未缓存窗口。
- 根据 ASR+MT 实测处理时间的平滑实时因子，自动选择 30s / 12s / 0s 领先范围。
- 播放缓冲时立即取消预取；远距离 seek 取消旧窗口并转向新位置。
- 预翻译文字继续写 Room，PCM 不落盘；再次进入、回拖或跳转可直接命中。
- 将选中音轨 ordinal 加入 `contentKey`，音轨变更时不复用错误字幕。

验收：预取范围、窗口选择、实时因子、seek/缓冲取消和音轨 key 有纯逻辑测试；
全量自动检查通过；真机发热、实时因子与 NAS 带宽影响留待发布验收。

## FN-24：应用图标焕新

**依赖**：FN-03  
**拥有路径**：`app/src/main/res/drawable/ic_launcher_*`、
`app/src/main/res/mipmap-*/ic_launcher*`、`app/src/main/res/mipmap-anydpi-v26/ic_launcher*`、
图标源稿与交接记录  
**目标**：建立更年轻、易识别且符合 Android 自适应图标规范的 FrameNest 品牌图标。

工作内容：

- 使用原创的「播放 + 流动环带/画框」符号，传达视频播放与 FrameNest 品牌含义。
- 采用高饱和渐变与轻盈气泡感；仅借鉴现代音乐应用的宽泛视觉语言，不复刻现有商标。
- 同步更新 Android 自适应前景/背景，以及各密度的旧版方形和圆形图标。
- 保证主体位于安全区，小尺寸下轮廓仍清晰，无文字和细碎装饰。

验收：资源检查、单元测试、lint 与 debug assemble 通过；生成手机/平板尺寸的资源，
真机启动器观感留待发布验收。

## FN-25：Release 启动崩溃修复

**依赖**：FN-14、FN-21、FN-24  
**拥有路径**：`feature/listen_translate/mt/MlKitTranslationModelCleaner.kt`、
`app/proguard-rules.pro`、相关测试与交接记录  
**目标**：修复 R8 压缩后的 Release 在 Application 初始化 ML Kit 时立即崩溃的问题。

工作内容：

- 将 `RemoteModelManager` 改为首次执行模型清理时才初始化，不阻塞或破坏应用启动。
- 保留 Firebase Encoder 的运行时实现，避免 R8 优化破坏 ML Kit 内部编码器。
- 添加最小单元测试，确保构造模型清理器不会立即触发 ML Kit 初始化。
- 构建、签名并覆盖安装 arm64 Release，在无线连接的真机上验证首页可启动。

验收：单元测试、lint、Release 构建通过；Release 真机启动后进程保持存活且 crash buffer
不新增 `com.framenest` 启动异常；不卸载、不清除现有应用数据。

## FN-26：Release 反射与 JNI 兼容修复

**依赖**：FN-22、FN-25  
**拥有路径**：`app/proguard-rules.pro`、`feature/player/PlayerViewModel.kt`、
相关测试、验证与交接记录  
**目标**：修复 R8 裁剪第三方运行时入口后，Release 无法建立 SMBJ 会话、加载 Vosk
或初始化 ML Kit 翻译器的问题。

工作内容：

- 保留 SMBJ 事件总线 mbassador 的运行时反射入口。
- 保留 Vosk 与 JNA 的 JNI/反射入口；底层类缺失时不向用户直接展示技术类名。
- 保留 ML Kit 翻译的组件注册与内部工厂；初始化异常时不向用户展示混淆后类名。
- 继续保持应用代码与其他依赖的 Release 压缩，不关闭全局混淆或资源收缩。
- 构建、签名并覆盖安装 arm64 Release，保留服务器、凭证与历史数据。
- 在当前真机/NAS 配置上重试浏览根目录，确认不再出现缺少 `SubscriptionContext` 构造函数。

验收：单元测试、lint、Release 构建通过；真机覆盖安装后 SMB 根目录可返回，Vosk
不再报 `org.vosk.LibVosk`，ML Kit 可进入本机翻译模型准备阶段，且无新的
`com.framenest` crash；不记录或导出 NAS 地址、用户名、密码。

## FN-27：听译空结果诊断与恢复

**依赖**：FN-23、FN-26
**拥有路径**：`feature/listen_translate/**`、`feature/player/PlayerViewModel.kt`、
`player/audio/PcmWindowDecoder.kt`、
相关决策/文案、测试与交接记录
**目标**：避免无音频或 ASR 空结果被误报为“已生成字幕”，并让当前播放窗口
能从旧空白缓存中恢复。

工作内容：

- 区分 PCM 为空、近静音与有声但 Vosk 未识别三种空结果。
- PCM 为空时显示可操作错误且不写入覆盖；静音可作为已扫描窗口缓存。
- 远程容器无法解码时区分“未发现音轨”与“音轨未输出 PCM”，为回退路径提供证据。
- SMB 听译改用 `MediaDataSource.readAt()` 直接对接已有随机读，避开部分机型
  `MediaExtractor` 无法从代理 FD 探测音轨的问题。
- 当前播放窗口命中历史空白覆盖时允许有界重试，不对预取静音无限重复识别。
- 状态面板分开“已扫描至”和“已生成 N 条”，并提示最近空结果原因。

验收：空结果分类、当前窗口恢复、预取边界和状态文案有自动测试；
unit test、lint、debug/release assemble 通过；不导出 PCM 或 NAS 信息。

## FN-28：播放器旋转续播与画布恢复

**依赖**：FN-05、FN-15、FN-20  
**拥有路径**：`feature/player/PlayerScreen.kt`、`feature/player/VlcVideoSurface.kt`、
`player/PlayerController.kt`、`player/VlcPlayerController.kt`、Manifest 的旋转接线、
相关测试与交接记录  
**目标**：横竖屏切换时保持原播放状态，并确保 VLC 视频画布绑定当前窗口，避免继续播放黑屏。

工作内容：

- 配置变化不按退到后台处理，旋转前正在播放则旋转后继续播放。
- 横竖布局共用同一个 Compose/AndroidView Surface 宿主，不在布局分支切换时 detach/attach。
- Activity 确实重建时，不复用属于旧 Activity 的 `VLCVideoLayout`。
- Surface 尺寸稳定后只刷新一次视频输出，避免方向与尺寸变化重复刷新。

验收：生命周期与宿主匹配策略有自动测试；unit test、lint、debug/release assemble 通过；
真机竖屏→横屏→竖屏播放状态和画面正常，不清除现有应用数据。

## FN-29：锁屏后听译 SMB 音频自动恢复

**依赖**：FN-27、FN-28
**拥有路径**：`feature/listen_translate/audio/**`、相关听译重试测试与交接记录
**目标**：设备锁屏导致听译专用 SMB 会话失效后，解锁或下次打开听译可继续获取音频，
不影响播放器主连接和已有字幕缓存。

工作内容：

- 将结构化 `SmbError.Disconnected` / `SmbError.Network` 识别为可重连音频错误。
- 兼容 SMBJ / 系统包装后的 `Not connected`、`Transport endpoint is not connected`
  等断连文本，不把认证、文件不存在或解码器错误误判为可重试。
- 当前 PCM 窗口断连时关闭旧随机读句柄，重建听译专用 SMB 会话并立即重试一次；
  仍失败时沿用会话级指数退避，之后继续补当前窗口。

验收：断连分类与非重试错误边界有自动测试；相关 unit test、lint、debug/release assemble
通过；可用真机完成锁屏→解锁→当前窗口继续生成字幕，不清数据、不记录 NAS 信息。

## FN-30：播放首帧与退出生命周期修复

**依赖**：FN-05、FN-20、FN-28
**拥有路径**：`player/**`、`feature/player/**`、`navigation/FrameNestApp.kt`
的播放器退出转场、相关测试与交接记录
**目标**：保证进入播放页后真实暂停在首帧，返回时立即停止画面并及时释放解码资源。

工作内容：

- 用播放意图约束 libVLC 的异步 Playing/Paused 事件，防止暂停状态下视频继续运行。
- 显式退出时先保存进度快照，立即停止并拆除 VLC Surface，耗时的本地清理保持在后台。
- 播放页回退不使用默认淡出，避免已退出的 SurfaceView 残留在浏览页上。
- 不改 SMB 数据路径、不引入新依赖。

验收：首帧暂停与迟到事件约束有纯逻辑测试；返回时无播放页残影；连续进出
20 次不累积活动播放器；unit test、lint、debug/release assemble 通过。

## FN-31：大目录浏览内存与无障碍优化

**依赖**：FN-07、FN-08、FN-22  
**拥有路径**：`data/thumbnail/**`、`feature/browser/**`、浏览页相关文案、
相关测试与交接记录  
**目标**：降低高分辨率视频封面检测的瞬时内存，并让目录条目向读屏用户提供真实、
本地化的内容，而不是内部自动化标识。

工作内容：

- 黑帧检测只读取固定数量的网格像素，不为整张 1080p/4K 帧创建 ARGB 副本。
- 保持现有黑帧阈值、候选时间点、缩略图尺寸、缓存键与单/双 worker 行为不变。
- 列表和网格条目的 TalkBack 描述使用“名称 + 共享/文件夹/视频/字幕”本地化文案；
  `testTag` 仍只用于自动化测试。
- 缩略图作为父条目的装饰内容，不重复朗读“视频”。

验收：固定网格采样的数量、坐标和黑帧判断有纯逻辑单测；手机与平板 Compose 测试
验证列表/网格读屏文案不包含内部 tag；unit test、lint、debug/release assemble 通过。

## FN-32：可见区域优先的缩略图调度

**依赖**：FN-22、FN-31

**拥有路径**：`data/thumbnail/**`、相关单测、性能文档与交接记录

**目标**：快速滚动大目录时取消已离屏的排队任务，让当前可见视频封面优先生成。

工作内容：

- 用可取消的键控待处理队列替换无界 FIFO 工作队列；同一缓存键只允许一份排队或在途工作。
- 条目最后一个 UI interest 释放时移除尚未开始的任务；在途提取保持现有安全发布和缓存行为。
- 保持单/双 worker、SMB 会话复用、失败退避、缓存 generation 与缩略图数据路径不变。
- 用纯逻辑单测覆盖离屏取消、重新进入、去重、在途状态和清空行为。

验收：快速滚过一批条目并全部释放后，新可见任务可直接成为下一个待处理项；
unit test、lint、debug/release assemble 通过；无真机时不虚报滚动帧率或 NAS 出图毫秒。

## FN-33：品牌 Logo 与应用主题统一

**依赖**：FN-24  
**拥有路径**：`design/app-icon/**`、`docs/brand/**`、Android launcher 资源、
`ui/theme/**`、品牌决策记录、相关测试与交接记录  
**目标**：将用户确认的蓝紫旋转影框 Logo 正式接入项目，并让手机/平板界面使用一致、
可访问的深靛蓝与蓝紫品牌体系。

工作内容：

- 将确认稿保存为品牌母版并建立确定性生成流程，生成自适应前景/背景、旧版方形/圆形、
  Android 13 单色主题、Play Store 与品牌源稿。
- 浅色/深色 Material 3 色彩角色与 Logo 保持亲缘，显式覆盖常用 container、surface、
  outline 角色；浅色按钮使用满足白字对比度的加深主色。
- 默认关闭系统动态取色，避免 Android 12+ 壁纸色完全覆盖品牌；不改播放器叠层、错误色
  与诊断页等功能性黑白/红色。
- 更新已接受的品牌决策，记录 Logo、主题角色与可访问性边界。

验收：Logo 在 48 px、圆形遮罩和单色主题下可辨识；主题关键前景/背景组合满足对比度；
手机/平板相关 Compose 测试、unit test、lint、debug/release assemble 通过。

## FN-34：播放器返回不卡主线程

**依赖**：FN-30

**拥有路径**：`player/PlayerController.kt`、`player/VlcPlayerController.kt`、
播放器释放调度与相关单测、性能文档与交接记录

**目标**：从播放器返回时立即显示上一页，不让 libVLC 原生停止或 SMB 输入关闭阻塞主线程。

工作内容：

- 保留主线程上的视频画布 detach/remove，避免已退出画面残留在浏览页。
- 将 `MediaPlayer.stop/release`、代理 FD 关闭与 `LibVLC.release` 放到命名后台线程。
- 保持退出进度快照、幂等释放、事件抑制和 ViewModel 后台清理行为不变。
- 用纯 JVM 测试约束原生释放执行器运行在独立 daemon 线程。

验收：返回调用链不在主线程执行 `MediaPlayer.stop()`；unit test、lint、debug/release
assemble 通过；无真机时明确保留真实 SMB 返回帧时间验收，不用模拟值代替。

## FN-35：Room 数据迁移与听译写入减负

**依赖**：FN-11、FN-23

**拥有路径**：`data/server/AppDatabase.kt`、Room schema 输出配置与迁移测试、
`data/listen_translate/**` 的最小写入优化、性能文档与交接记录

**目标**：应用升级时保留服务器、播放历史和听译缓存，并减少连续听译窗口写入中的
重复 Room 查询。

工作内容：

- 为现有 v1→v2 schema 提供显式迁移，移除升级时清空全部表的 destructive fallback。
- 开启并提交 Room schema 导出，为迁移验证和后续版本演进保留基线。
- 添加迁移测试，验证 v1 服务器/播放历史数据保留且 v2 听译表可写。
- `ensureJob` 复用已读取的 job，避免未失效时再次执行同一主键查询；保持 content/model
  失效语义不变。

验收：迁移验证通过；旧服务器与播放历史不丢失；听译 repository 回归测试约束普通
`ensureJob` 只查询一次；unit test、lint、debug/release assemble 通过。

## FN-36：听译 cue 缓存热路径优化

**依赖**：FN-23、FN-35

**拥有路径**：`feature/listen_translate/ListenTranslateSession.kt`、
`ListenTranslateWindows.kt`、最小测试 fake/相关单测、性能文档与交接记录

**目标**：连续听译和播放进度更新不重复加载整份 cue 列表，也不为每次当前字幕查找创建
临时集合。

工作内容：

- 使用 `upsertCue` 返回的持久化 cue 原地更新会话的有序内存缓存；Room Flow 继续负责
  最终一致性，不在每次窗口完成后再次 `listCues`。
- 同范围 cue 按 repository 替换语义更新，保留重叠的语音片段与整窗覆盖记录。
- `cueAt` 改为单次无中间集合扫描，保持“更晚 start、更高 rev 优先”和端点兼容行为。
- 纯逻辑/会话测试覆盖排序、替换、重叠和连续 30 秒预取只做一次初始全量查询。

验收：普通窗口完成后无新增全量 `listCues`；快速预取 10 个窗口仍只执行初始查询；
字幕选择语义测试不回归；unit test、lint、debug/release assemble 通过。

## FN-37：听译 PCM 解码内存优化

**依赖**：FN-14、FN-36

**拥有路径**：`player/audio/PcmAudioMath.kt`、`player/audio/PcmWindowDecoder.kt`、
相关纯逻辑测试、性能文档与交接记录

**目标**：减少连续听译解码窗口中每个 MediaCodec 输出块的字节数组和 PCM 数组复制，
降低 GC 压力与瞬时峰值内存，不改变采样、裁剪或音轨选择语义。

工作内容：

- 从 MediaCodec `ByteBuffer` 直接下混 PCM16/float，不再为每个输出块创建完整 `ByteArray`。
- 完整覆盖窗口不复制裁剪数组；输入已为 16 kHz 时复用原 PCM 数组。
- 使用预估容量的连续累加缓冲替代长期保留所有分块，常规 3–9 秒窗口避免末尾再次拼接。
- 纯逻辑测试覆盖 ByteBuffer 下混、输入 position 保持、无操作路径复用和累加扩容。

验收：常规 PCM16/16 kHz 块只创建下混结果且完整窗口不再二次复制；输出样本语义测试
不回归；unit test、lint、debug/release assemble 通过。

## FN-38：设置页缓存维护非阻塞化

**依赖**：FN-09、FN-35

**拥有路径**：`feature/settings/CacheMaintenance.kt`、`ui/screens/SettingsScreen.kt`、
相关纯逻辑测试、性能文档与交接记录

**目标**：设置页首次组合不执行目录遍历，缓存统计和清理不使用 `runBlocking` 占住线程，
并避免为了计算未展示的总量反复遍历大型 Vosk 模型目录。

工作内容：

- 缓存/模型/Room 统计与清理改为挂起 API，并在维护层统一切到 I/O dispatcher。
- 设置页缓存和模型状态先使用轻量初值，再异步加载一致的使用量快照。
- “全部缓存”“听译缓存”“听译模型”分别只统计自身清理范围，不扫描无关目录。
- 目录递归计数提取为纯逻辑函数并覆盖嵌套文件、目录项和不存在路径测试。

验收：Settings 首次 composition 不调用目录大小扫描；缓存维护代码无 `runBlocking`；
相关 unit test、lint、debug/release assemble 通过。

## FN-39：LAN 端口探测有界并发与取消稳定性

**依赖**：FN-17

**拥有路径**：`data/discovery/SmbPortProber.kt`、`data/discovery/LocalIpv4.kt`、
相关纯逻辑测试、性能文档与交接记录

**目标**：主动扫描 /24 网段时只创建配置数量的 worker，不为每个地址创建一个等待协程；
网络切换导致单个接口读取失败时跳过该接口，并保持取消传播。

工作内容：

- 用原子索引的固定 worker 池替换 `hostAddresses.map { async }.awaitAll()` 与信号量。
- Socket 探测前后检查取消，取消不被通用异常处理吞掉，也不在取消后发布发现结果。
- 网卡的 `isUp`、loopback 与地址快照按接口隔离异常，Wi-Fi/VPN 切换不终止整个枚举。
- 纯逻辑测试覆盖最大并发、每个地址恰好一次、发现结果和取消传播。

验收：253 个 /24 地址默认只创建 32 个探测 worker；最大在途探测不超过配置值；
相关 unit test、lint、debug/release assemble 通过。

## FN-40：听译窗口 Room 快速写入

**依赖**：FN-35、FN-36

**拥有路径**：`data/listen_translate/**`、`feature/listen_translate/ListenTranslateSession.kt`、
相关 DAO fake/JVM/Room 测试、性能文档与交接记录

**目标**：会话已建立听译 job 后，每个 3 秒窗口不再重复 SELECT 并整行 upsert 父 job；
保留 ASR 原文先落盘、取消后不丢已完成文本和父行意外缺失时自动重建的语义。

工作内容：

- 进度更新改为单条定向 `UPDATE`，不再读取父行再执行完整 `@Upsert`。
- 新增“已有 job cue 写入”事务，仅更新时间戳并同范围替换 cue；父行缺失时回退 ensure。
- 空白/覆盖 cue 与窗口进度在同一事务完成；语音原文仍先独立提交，再写覆盖。
- DAO 计数和会话测试约束连续预取的父表查询/upsert 只发生在初始 activation。

验收：10 个连续空白窗口总计仅初始 1 次 job SELECT + 1 次 job upsert；窗口内使用定向
UPDATE；失败原文持久化和父行重建测试通过；unit test、lint、debug/release assemble 通过。

## FN-41：缩略图磁盘缓存 O(1) 容量计量

**依赖**：FN-07、FN-32

**拥有路径**：`data/thumbnail/ThumbnailDiskCache.kt`、`data/thumbnail/ThumbnailKey.kt`、
相关纯逻辑测试、性能文档与交接记录

**目标**：批量生成封面时不为每次 `put` 重新枚举并统计整个缩略图目录，避免大目录下
近似 O(N²) 的文件系统调用；应用启动时不创建缓存目录。

工作内容：

- 首次按需扫描后缓存 JPEG 总字节数，写入覆盖和删除使用文件长度增量维护。
- 只有容量真正超限时才枚举并按 mtime 排序淘汰；清理或写入失败时重置/校准计量。
- `put/get/remove` 每次只计算一次 key digest，损坏 JPEG 解码失败时删除并允许重新生成。
- 同一 `ThumbnailKey` 缓存 SHA-256 摘要，并用字符表编码十六进制，避免重复摘要与 Formatter。
- 缓存目录延迟到后台写入/统计时创建，不在 `AppContainer` 构造期间 `mkdirs`。

验收：连续未超限写入只发生 1 次初始容量扫描；覆盖/删除/失效后字节数正确；
unit test、lint、debug/release assemble 通过。

## FN-42：听译初始化失败清理非阻塞化

**依赖**：FN-14、FN-34、FN-37

**拥有路径**：`feature/player/PlayerViewModel.kt`、听译初始化清理辅助文件、
相关纯逻辑测试、性能文档与交接记录

**目标**：听译模型或 SMB 初始化失败/取消时，不在 ViewModel 主线程同步等待音频源、
Vosk 与翻译器关闭；取消状态下仍保证所有已创建资源得到 best-effort 清理。

工作内容：

- 失败和取消路径统一切到不可取消的 I/O context 关闭待接管资源。
- 单个资源关闭异常不阻止后续资源清理，完成后清空 pending 引用。
- JVM 测试覆盖调用方取消、专用清理线程和首个 close 抛错后继续关闭。

验收：失败/取消清理不在主线程执行；取消不会跳过清理；单个 close 异常被隔离；
unit test、lint、debug/release assemble 通过。

## FN-43：媒体路径重复工作优化

**依赖**：FN-18、FN-32、FN-41

**拥有路径**：`feature/player/PlayerViewModel.kt`、`feature/subtitle/SidecarSubtitleScanner.kt`、
`data/thumbnail/**`、`feature/browser/BrowseScreen.kt`、相关单测、性能文档与交接记录

**目标**：减少播放首帧和大目录缩略图链路中的重复 SMB、线程与 Compose 状态工作，
不改变播放、字幕选择或缓存失效语义。

工作内容：

- 播放首帧只列举一次父目录，同时派生外挂字幕候选和同目录播放列表。
- 每个缩略图 worker 复用一个代理 FD I/O 线程，worker 结束时统一安全关闭。
- 缩略图观察返回稳定的 `StateFlow`，Compose 行在 entry 不变时不重复创建观察对象。
- 添加最小测试约束共享目录快照的字幕匹配结果，并记录可测量的性能变化。

验收：SMB 播放首帧的父目录枚举由两次降为一次；连续缩略图提取不再为每张图片新建
代理 FD 线程；相关 unit test、lint、debug/release assemble 通过。

## FN-44：播放器无卡顿退出

**依赖**：FN-30、FN-34、FN-43

**拥有路径**：`navigation/FrameNestApp.kt`、`feature/player/PlayerScreen.kt`、
`feature/player/PlayerViewModel.kt`、`player/VlcPlayerController.kt`、
`player/PlayerReleaseExecutor.kt`、相关单测、性能文档与交接记录

**目标**：所有页面和顶层 Tab 立即切换；播放中返回浏览/最近页时，同时保证视频 Surface、
libVLC、代理 FD、SMB 读取和播放进度最终正确结束。

工作内容：

- 在全局 NavHost 关闭进入、退出、返回进入和返回退出转场，不保留页面级动画例外。
- 显式退出先快照进度，不在导航前同步调用 libVLC `pause` 或 `detachViews`。
- 主线程只隐藏并移除视频容器；后台停止 native 播放后回主线程 detach Surface，最后继续
  后台 release libVLC 与代理 FD。
- 用阶段顺序测试约束 stop → main detach → release，保留幂等和超时兜底。
- 在 PME110 上安装 release，验证播放返回、进程存活、音频停止和最终资源释放日志。

验收：页面和 Tab 切换没有默认淡入淡出，切换后旧目标不继续参与组合；主线程返回路径
不调用 native pause/stop/release 或活跃 vout detach；进度保存和资源释放测试通过；
unit test、lint、debug/release assemble 通过，并记录真实设备结果。

## FN-45：启动器图标满幅适配

**依赖**：FN-33

**拥有路径**：`design/app-icon/**`、Android launcher 资源、品牌决策记录、相关测试与交接记录

**目标**：消除厂商启动器上蓝紫 Logo 四周可见的深色边缘，让品牌图形铺满系统 adaptive
icon 遮罩，同时保留不同圆角/圆形遮罩的兼容性。

工作内容：

- 以 Android adaptive icon 的 108dp 图层与约 72dp 可见遮罩为基准，扩大前景图形，不在
  位图中预裁厂商圆角。
- 保留深靛蓝背景用于图形负空间，但不再让 66dp 安全区成为可见的外围深色边框。
- 由现有确定性脚本重建所有密度的 adaptive、legacy、round、monochrome 与品牌资源。
- 添加生成结果检查，约束 adaptive 前景占比与背景不透明性；在 PME110 桌面安装 release
  并以修复前后截图验收。

验收：PME110 桌面上图形明显铺满遮罩且无外围黑边；核心播放键与旋转影框不被系统遮罩
误裁；生成测试、unit test、lint、debug/release assemble 通过。

## FN-46：播放器画布位置稳定性

**依赖**：FN-15、FN-20、FN-28

**拥有路径**：`feature/player/**`、相关 UI 测试与交接记录

**目标**：拖动进度条或切换控制层显隐时保持视频画布位置与缩放稳定。

工作内容：

- 竖屏和横屏统一使用固定全窗口视频 viewport，顶栏与底部控制条仅作为叠层。
- 进度条拖拽、seek / buffering 状态文案和控制层显隐不得改变 VLC Surface bounds。
- 保持现有单次 seek、自动收起、锁屏、系统栏安全区和旋转 Surface 复用行为。
- 添加 UI 回归测试，约束不同高度控制层显隐前后视频 viewport bounds 不变。

验收：拖拽期间与松手 seek 时视频位置不跳；单击画面切换顶栏/控制条时视频位置不跳；
相关 unit test、UI 测试编译、lint、debug/release assemble 通过。

## FN-47：播放后目录浏览卡住

**依赖**：FN-22、FN-44  
**拥有路径**：`smb/SmbjClient.kt`、`data/server/BrowseRepository.kt`、
`feature/browser/BrowseViewModel.kt`、`feature/player/PlayerViewModel.kt` 的浏览会话
释放接线、相关单测与交接记录  
**目标**：连续播放几个视频后，返回或进入目录不再无限转圈。

工作内容：

- `SmbjClient` 不再在实例锁内执行 connect/list I/O，使 `close()` 能打断卡住的列目录。
- 浏览会话超时后关闭传输并换新会话重试一次；取消或进入播放时立即释放浏览连接。
- 刷新在加载中仍可点击，以便用户中断卡住的列出。
- 纯逻辑测试覆盖超时重试、release 解锁，以及断线/网络才重试的边界。

验收：卡住的 `listDirectory` 会在超时或 `releaseSession` 后结束，后续 load 可完成；
unit test、lint、debug/release assemble 通过；无真 NAS 时不虚报真机恢复耗时。

## FN-48：拖动预览、全屏横屏与手势快进

**依赖**：FN-15、FN-16、FN-17、FN-46
**拥有路径**：`feature/player/**`、`player` seek 预览接线、相关单测与交接记录
**目标**：拖动进度条时即时看到目标画面，一键横屏全屏，并支持左右滑动快进/后退。

工作内容：

- 拖动中对 preview seek 做时间/位移节流，松手提交落点；暂停态复用现有单会话预览。
- 拖动期间不盖住视频的“正在定位”层。
- 全屏按钮强制横屏，退出回到竖屏；离开播放页恢复系统旋转。
- 水平滑动按距离映射进度；垂直滑动仍控制亮度/音量；不改 SMB 数据路径。

验收：节流、落点夹紧、滑动方向和全屏方向请求有纯逻辑单测；unit test、lint、debug assemble 通过。

## FN-49：关键操作逻辑与无障碍收口

**依赖**：FN-31、FN-47、FN-48  
**拥有路径**：`navigation/**`、`feature/browser/**`、`feature/player/**` 的交互层、
`feature/servers/**`、`feature/settings/**`、`ui/screens/SettingsScreen.kt`、
`feature/subtitle/SubtitleControls.kt`、`feature/listen_translate/ListenTranslateControls.kt`、
`res/values/strings.xml`、主题尺寸、相关测试/交接记录  
**目标**：统一手机和平板上的关键操作语义，消除会误进页面、误清数据、卡住手势或让
无障碍用户失去控制入口的冲突。

工作内容：

- 服务器空状态只保留一组明确的“扫描 / 手动添加”入口；平板空列表不再重复显示两个
  相互竞争的空状态，列表项读屏文案不暴露内部 id。
- 从服务器进入浏览时保留“共享列表 → 默认共享”层级；字幕文件保持可识别但不可误开为
  视频，说明其应在播放页自动匹配或手动选择。
- 播放器拖动在 Release、Cancel 或组合退出时都结束 scrubbing 并恢复音量；视频画面提供
  TalkBack/键盘可达的播放与控制条入口。
- 全屏横屏只在显式进入时显示退出动作；退出横屏完成后释放临时方向请求，方向锁仍是
  唯一持久方向约束。
- “清理缓存”只清理页面所展示的一般磁盘缓存；听译结果和模型使用独立、带确认的操作，
  结果通过当前可见的 Snackbar/无障碍 live region 反馈。
- 删除服务器文案如实说明同步删除本机播放历史和听译结果；保存中不可通过外部点按造成
  “已取消”错觉。设置内容在平板限制可读宽度，推荐模型优先展示。

验收：关键导航/字幕动作、缓存清理域、手势取消、方向状态和语义点击有自动测试；手机
与平板布局截图通过；`testDebugUnitTest`、`lintDebug`、debug/release assemble 与
AndroidTest 编译通过。真 NAS seek 预览与物理旋转仍按发布验收单补测。

## FN-50：连续快进与片尾重播恢复

**依赖**：FN-44、FN-48、FN-49

**拥有路径**：`player/**`、`feature/player/**`、相关测试、播放决策与交接记录
**目标**：修复片尾重播无效，约束连续快进的在途请求，并让播放加载失败可恢复。

工作内容：

- 片尾重播重新打开原媒体，清除旧播放事件与进度，不停留在已结束的 native input。
- 快速拖动保留预览与最终落点，但只保留最新待处理目标，避免积压远程 seek。
- 播放重开不在主线程等待 native stop；加载超时进入可重试错误。
- 为重播、请求合并、过期事件和恢复补充最小回归测试；不切换 SMB 路径或解码器。

验收：本地样本在手机和平板配置上连续重播通过；快速 seek 最终目标不丢失；
unit test、lint、debug assemble 与 AndroidTest 编译通过；真实 NAS 未测项如实记录。

## FN-51：拖动预览图

**依赖**：FN-48、FN-50

**拥有路径**：`feature/player/**` 的预览图调度与 UI、预览磁盘缓存、设置页一般缓存中的对应清理、相关测试、决策与交接

**目标**：拖动进度时马上看到目标时刻的预览图，而不是等待远程播放器逐次定位。

工作内容：

- 手指按下期间不 seek、不静音；松手提交一次落点，取消不提交。
- 进度条上方（滑动时在画面中央）显示预览图和时间。图未就绪时卡片先出现。
- 单路 MediaMetadataRetriever，10 秒分桶；当前播放附近和少量均匀帧在后台预热，拖动目标插队。
- 缓存键含 size/modifiedTime；计入一般缓存清理。不记录凭证。

验收：插队、缓冲暂停预热、失败不重试和缓存键有自动测试；拖动时预览卡出现、松手后消失有 Compose 测试；
unit test、lint、debug/release assemble 与 AndroidTest 编译通过。真实 NAS 单帧耗时不虚报。

## FN-52：文件夹视频封面

**依赖**：FN-07、FN-32、FN-51

**拥有路径**：`data/thumbnail/**` 的候选时间与封面缓存文件名、`feature/browser/**` 的列表封面与行文案、相关测试、决策与交接

**目标**：进入文件夹时能从封面上看出视频内容，而不是只看到文件名和一个小图标。

工作内容：

- 列表中的视频使用 16:9 封面；副标题带文件大小，封面上标时长。
- 较长视频的第一帧取在片中前段，黑场和纯色帧改试后面的有限候选。
- 新封面使用独立缓存文件名，避免继续显示旧的片头帧。不增加播放器，不记录凭证。

验收：候选时间、纯色判断、时长缓存和封面尺寸有自动测试；手机与平板上的浏览界面测试通过；
unit test、lint、debug/release assemble 与 AndroidTest 编译通过。真实 NAS 单张耗时不虚报。

## FN-53：文件夹封面尽快出现

**依赖**：FN-52

**拥有路径**：缩略图取帧长度与候选回退、`ThumbnailDiskCache` 的旧封面读取、拖动预览的取帧长度、相关测试、决策与交接

**目标**：进入文件夹后能较快看到视频封面，而不是在多 GB 文件上一直转圈、最后仍是占位图标。

工作内容：

- 列表封面默认 3 路同时取。每个 worker 复用一个 libVLC，直接 SMB 取片头一帧，画面按片子比例写入内存，不挂窗口，也不先搬片头。
- 封面未到时格子里保持电影图标，画面到位后短淡入。
- 旧的封面文件可以先显示，新的 `c3` 封面生成后替换它。
- SMB 拖动预览在手指按住时，用播放页里的一个软件解码 libVLC 取对应时间的帧。松手才定位正在播放的片子。播放过程中不为这条预览预热。
- 播放页进到别的应用再回来时，重新挂上视频表面，使暂停后的播放可以继续。

验收：取样位置和旧文件不是命中有自动测试；装到当前手机后重新进入文件夹，可见行在几秒内出现封面，包含之前停在占位的 2160p。真实 NAS 单张耗时不虚报。

## FN-54：播放会话边界与前后台恢复

**依赖**：FN-49、FN-50、FN-53
**拥有路径**：`player/**`、`feature/player/**`、`feature/subtitle/ExternalSubtitleLoader.kt` 的会话缓存隔离、`feature/listen_translate/mt/MlKitMtEngine.kt` 的 Translator 取消/关闭资源所有权、相关单元/界面测试、对应决策与交接记录
**目标**：退出视频后释放当前会话和临时状态；回到前台恢复原有播放意图，手动暂停保持暂停，历史进度不被预渲染覆盖。

工作内容：
- 明确首次进入、实际播放、手动暂停、进入后台、前台恢复、退出与换源的状态边界。
- 退出清理播放器、预览、字幕/听译工作和音频焦点等会话资源；保留历史和用户设置。
- 过期准备、seek、音频焦点及 native 回调不能污染新视频或在后台启动播放。
- 修复仅打开后退出导致续播进度归零，以及预览内存淘汰后不能重载的问题。
- 覆盖连续进出、快速返回、换视频、后台/锁屏回来、手动暂停、旋转重建和失败重试。

验收：最小自动回归覆盖上述状态；单元测试、Lint、APK 构建和 AndroidTest 编译通过；可用时在手机和平板配置上验证。真实 NAS 与设备未测项明确说明。

## FN-55：SMB 浏览取消与非阻塞清理

**依赖**：FN-53
**拥有路径**：`smb/**`、`data/server/BrowseRepository.kt`、`feature/browser/BrowseViewModel.kt`、相关测试与交接记录
**目标**：刷新或离开目录时及时取消请求，不在主线程等待不响应的 NAS。

工作内容：
- 会话解绑和阻塞网络清理解耦；取消路径及时终止传输，不等待优雅关闭的网络响应。
- 确保旧请求的取消或关闭不会终止新请求的会话，不泄漏凭证或网络资源。
- 补充阻塞关闭、连续刷新、取消后新请求与重复清理测试。

验收：相关自动测试通过；退出/刷新调用不等待模拟的阻塞网络关闭；真实弱网耗时不虚报。

## FN-56：Android 构建检查恢复

**依赖**：无
**拥有路径**：`.github/workflows/ci.yml`、对应构建文档和交接记录；任务环境安装不进入仓库
**目标**：修复 CI 安装旧 tools 包失败，使构建、单测与 Lint 真正执行。

工作内容：
- 显式配置必要 SDK 包，避免已不存在的 tools 默认值；保留 JDK 17、SDK 36 和既有依赖版本。
- 检查许可步骤和 shell 退出码，避免管道状态误报。
- 在授权的云端环境准备构建工具，验证 APK、单测、Lint 与 AndroidTest 编译。

验收：工作流语法有效；实际构建结果分清通过、失败和未执行；不进行无关依赖升级。

## FN-57：播放器体验修复集成

**依赖**：FN-54、FN-55、FN-56
**拥有路径**：任务卡、集成测试矩阵、共享导航/装配文件的必要协调改动、集成交接记录
**目标**：集成本轮修复，在独立分支核对完整的进出视频与前后台体验。

验收：各任务拥有路径不冲突；整体验证并记录手机/平板和真实 NAS 的覆盖范围；推送独立分支供检查，合并与发布另按用户指示执行。

## FN-58：应用内检查更新与安全安装

**依赖**：FN-54、FN-56；发布元数据契约与 FN-59 协调
**拥有路径**：新的 `feature/update/**` 与对应单元/Android 测试、更新机制文档与交接记录。共享设置页、MainActivity、Manifest 和 FileProvider 接线由 FN-57 集成。
**目标**：像纯粹骑行一样方便更新，包含自动/手动检查、版本说明、下载进度和系统安装确认；不打断播放。

工作内容：
- 使用固定仓库发布的静态更新清单，避免依赖匿名 GitHub API 配额；与发布任务约定清单结构。
- 自动检查限频，播放时不弹出安装流程；设置页提供明确的手动入口。
- 下载支持进度、失败重试与取消；校验 HTTPS 来源、长度、摘要、包名、版本与现有安装签名。
- 仅用户操作后进入系统安装器；来源安装许可由用户确认，不静默安装，不触碰播放历史或 NAS 凭证。
- 覆盖版本比较、清单异常、跨仓库/不安全链接、坏摘要、错误包名/签名、重复点击、取消和前后台状态。

验收：自动测试与集成检查通过；安装兼容性和真实设备未测项明确说明；不增加不必要的后端服务或权限。

## FN-59：可复用签名发布与更新清单

**依赖**：FN-56、FN-58；仅在 FN-57 验证本轮修复后发布
**拥有路径**：新的发布工作流/脚本、发布请求与清单契约、`app/build.gradle.kts` 的版本和签名配置、CHANGELOG、发布文档与交接记录
**目标**：Actions 产出可下载且后续可覆盖安装的版本，并与应用内更新共用可信元数据。

工作内容：
- 先核对现有 APK 签名及构建配置，确保持续签名来源；不生成或传输未经确认的新私钥，不使用每次构建随机生成的调试密钥作为更新链。
- 校验固定发布源、版本、签名、APK 摘要和更新清单，再发布 APK 与 update.json。
- 使用明确的发布请求触发；普通代码推送不自动发布。标签和已有发布不强制覆盖。
- 与 FN-58 约定包名、版本号、ABI、大小、摘要和固定版本下载链接。

验收：Actions 构建和安全检查真实通过，公开产物与更新入口可访问；缺失签名材料时明确阻塞，不降级为不可持续的签名。

## FN-60：重试状态、辅助连接与封面缓存隔离

**依赖**：FN-54、FN-55、FN-57
**拥有路径**：`feature/player/**` 的重试/目录任务所有权、`feature/subtitle/ExternalSubtitleLoader.kt` 的可取消传输、`data/thumbnail/**` 的内存与磁盘锁隔离、必要的 `smb/**` 可测试取消接口、相关测试/决策/交接；共享版本和发布由集成负责人处理。
**目标**：连续重试不丢续播点，旧扫描不覆盖新结果；退出能中止辅助 SMB 传输；磁盘缓存操作不持有 UI 内存读取所需的锁。

验收：覆盖延迟清理期间重复重试、旧扫描晚到、手动字幕选择、取消连接/读取和新会话隔离、缓存清理与解码竞态；运行单测/Lint/APK/AndroidTest 编译，设备未测项明确说明。用户已授权本轮修复和后续发布，不访问真实 NAS 凭证。

## FN-61：听译首句与播放预算

**依赖**：FN-51、FN-54、FN-60
**拥有路径**：`feature/listen_translate/**`、`PlayerViewModel.kt` 的听译接线、`smb/SmbTransportOwner.kt` 的非阻塞暂停、相关测试/决策/交接。
**目标**：原文先显示、译文随后补齐；按完整窗口处理预算和播放倍速预取，后台暂停听译并阻止过期结果发布。

验收：保持 SenseVoice/ML Kit、本机缓存、原有播放默认行为和非阻塞退出；覆盖迟到结果、语言/音轨变更、前后台、倍速、翻译失败和取消。运行可用单测/构建，真机未测明确记录。不新增服务、权限、模型、缓存迁移或预缓冲 UI。

## FN-62：听译本机诊断与保守静音实验

**依赖**：FN-61
**拥有路径**：`feature/listen_translate/**`、播放器听译开关接线、对应字符串/测试/决策/交接。
**目标**：借鉴 Handy 的测量与门控思路，先建立可解释的本机测量，再验证保留原时间轴的静音门控；评估流式能力，不盲目更换模型。

验收：窗口内分阶段耗时、队列等待、窗口落后量、原文/译文就绪与媒体迟到分别显示，只有最近窗口内存快照，不上传音频/字幕/遥测。实验默认关闭、每次播放会话重置，仅确认近数字静音才跳过整个窗口；安静人声、极短发声、BGM/不确定输入全部放行，不拼接/裁剪 PCM，不改 PTS。明确这不是人声/音乐分类器或模型 VAD。保留 SenseVoice/Sherpa、Vosk、ML Kit 和既有 seek/倍速/后台/退出代次边界；切换策略后不得复用旧静音覆盖。评估真实流式 API、生命周期和模型许可，不新增权重、服务、权限或自动发布。单测/构建/Lint/AndroidTest 编译实际验证；设备速度、温度、功耗与手机/平板 UI 未测项明确说明。

## FN-63：审计修复与 0.6.3 内部版集成

**依赖**：FN-62
**拥有路径**：模型安装与下载、听译显示与缓存、SMB 策略及其服务器编辑接线、数据库迁移、对应测试、构建与发布文档。
**目标**：修复本轮审计确认的问题，保留原有播放时间轴/代次边界，验证后沿既有签名发布流程发布内部版。

验收：覆盖全新模型安装、损坏/超限/续传下载、实际 rev=1 空白覆盖平局、弱短语音默认行为、缓存容量与活动所有权、SMB 加密默认及显式仅签名兼容、保数据迁移。真实运行 Android Debug/Release 构建、单测、Lint、AndroidTest 编译；独立审查精确提交树；公开 APK 摘要、固定签名和 update.json 核验。真机/NAS/热功耗未测必须如实说明。不访问真实 NAS、修改凭证或删除真实用户数据。

## FN-64：0.6.3 听译闪退修复（sherpa 绝对路径 + AssetManager）

**依赖**：FN-63
**拥有路径**：`feature/listen_translate/asr/SherpaAsrEngine.kt`、听译接线的引擎构造、对应单元测试/决策/交接记录。
**目标**：修复已安装 SenseVoice 模型时开启听译即进程退出（`EXIT_SELF status=255`）的回归；模型只从私有存储文件加载，不改变窗口、缓存身份、模型安装和播放行为。

验收：开启听译不再触发 `newFromAsset` 进程退出；回归测试锁定构造函数不得再接受 `AssetManager`；真机 0.6.3-internal 覆盖安装验证听译可用；单测/构建通过，未测项如实记录。

- 真机根因证据：`F sherpa-onnx: Read binary file: Load '.../tokens.txt' failed`；
  native 栈 `OfflineRecognizer_newFromAsset` → `exit` → Zygote `exited cleanly (255)`；
  `ApplicationExitInfo reason=1 (EXIT_SELF) status=255`，无 Java FATAL。
- 修复：`OfflineRecognizer(config)` 走 `newFromFile`（`assetManager` 默认 null）。
- 不修改：3 秒窗口、静音策略、`|digital-zero-v2` 缓存身份、Vosk/ML Kit、下载与迁移。

## FN-65：听译字幕位置对齐 CC 与分层显示

**依赖**：FN-64
**拥有路径**：`player/PlayerState.kt` 与 `player/VlcPlayerController.kt` 的视频尺寸暴露、新的播放器字幕几何纯函数与测试、`feature/player/PlayerScreen.kt` 的听译叠加定位、对应决策/交接。
**目标**：听译字幕显示在 libVLC CC 相同的画面底部区域；CC 开启时两层并存（听译在 CC 正上方）不重叠；不改字幕轨状态与听译管线。

验收：纯几何函数单测覆盖各画面比例/缩放模式/横竖屏；真机截图对比 CC 与听译的相对位置和分层；关闭听译后 CC 行为不变。
