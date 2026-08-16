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
