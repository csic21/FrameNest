# FN-57 播放状态验收矩阵

本轮重点是会话边界与恢复。离开视频清理的是当前会话、临时界面状态、回调和资源；历史进度、服务器配置和用户设置需要保留。

| 场景 | 必须保持的行为 | 自动化 / 设备验证 |
|---|---|---|
| 历史已有进度，打开后未播放直接返回 | 不用首帧附近的零秒覆盖历史 | ResumeSeekGateTest、PlaybackProgressPersistenceTest 单测通过；PlayerSessionBoundaryTest 待设备运行 |
| 用户明确定位到零秒后退出 | 能保存用户主动选择的零秒，不用“只增不减”掩盖错误 | PlaybackProgressPersistenceTest 单测通过；显式零点真实 VM 测试待设备运行 |
| 正在播放时进入后台再回来 | 同一视频和进度；表面可用且焦点允许后恢复原播放意图 | PlaybackLifecycleTest 单测通过；ProductPlayerLifecycleTest 和实际画面待设备运行 |
| 手动暂停后进入后台再回来 | 保持暂停，画面和操作仍可用 | PlaybackLifecycleTest 单测通过；产品页设备验证待执行 |
| 等待音频焦点时进入后台，随后获得焦点 | 不在后台意外启动；过期请求不能影响新会话 | PlaybackLifecycleTest 单测通过；PlayerAudioFocusGenerationTest 待设备运行 |
| 退出 A 后立即打开 B | A 的首帧、seek、字幕、听译和预览回调不能更新 B | PlaybackLifecycleTest、SubtitleSessionCacheTest、ScrubPreviewLifecycleTest、MlKitMtEngineOwnershipTest 单测通过；快速换源设备验证待执行 |
| 快速返回、重复退出、再次进入同一视频 | 清理幂等；旧会话不能删除新字幕缓存或覆盖更新的历史 | PlaybackProgressPersistenceTest、SubtitleSessionCacheTest 单测通过；重复进出设备验证待执行 |
| 旋转、窗口变化、锁屏再解锁 | 不把表面重建误当作导航退出；保留正确播放意图并重新挂接画面 | 生命周期策略单测通过；ProductPlayerRecreationTest 和锁屏设备验证待执行 |
| 加载失败或断网后退出、再进与重试 | 不残留旧错误、旧加载状态或后台工作 | 生命周期错误/结束不自启单测通过；真实网络失败恢复未测 |
| 拖动超过 36 个预览位置再返回被淘汰位置 | 能重新加载正确预览；旧帧完成不能写回已退出会话 | ScrubPreviewLifecycleTest 单测通过；实际连续拖动设备验证未测 |
| NAS 无响应时刷新/返回、随后发起新请求 | 主线程及时返回；旧连接清理不影响新请求 | BrowseRepositoryHangTest、BrowseViewModelCleanupTest、SmbjClientAbortTest 通过；真实 NAS 未测 |
| 播放页退出后的系统状态 | 释放临时全屏、屏幕常亮、音频焦点与会话资源，保留用户持久设置 | 生命周期关闭策略单测通过；全屏/常亮系统状态设备验证待执行 |

每项记录实际执行的测试与结果。单元测试、AndroidTest 编译、模拟器运行与真实 NAS/设备实测是不同覆盖层，不能互相代替。

## 当前证据（2026-10-08）

- FN-54 + FN-55 的代码组合：464 项 JVM 测试、lintDebug、assembleDebug、assembleDebugAndroidTest 已通过。独立复查发现的目录扫描结果抑制和 pre-open 音量假设问题均已修正，三项目录回归及一项音轨/时钟 Android 回归已编译；不声称实际声音验证通过。
- FN-55：48 项定向测试通过，包括 15 项新增取消/清理测试和真实 SMBJ 无响应 loopback TCP 测试；这不是实际 NAS 验收。
- FN-58：17 项 JVM 测试、lintDebug、Debug APK 与 AndroidTest APK 编译通过。
- FN-59：35 项发布脚本测试通过；尚未使用 Actions Secrets 生成真实签名产物。
- GitHub CI 37807300329 attempt 2 已通过，但只覆盖初始构建修复提交 5678395，不能作为最终集成版通过的证据。
- 手机/平板仪器测试、实际画面恢复、系统安装器和真实 NAS 均待验证。AndroidTest 编译不等于运行。

## 分支功能覆盖

远端原有 FN-51、FN-52、FN-53 均为基线 main 0649456 的祖先或同一提交，已包含拖动预览、目录封面及快速封面功能。FN-54 到 FN-59 的新增提交汇入本集成分支后仍需最终构建和远端 CI；不重复合并旧分支，不删除分支。

## 最终整合本地检查

生产/测试源码本地提交 `e961b71512caa8a2a7703982cfa5f548fc71872b` 与远端
`bc31ba0282fd38788f350e6117a79988702a5629` 的完整 Git tree 相同：
`15dbb173359ae8b3bfc64ce94a2653c6de5a5f08`。

- `testDebugUnitTest`：481 项通过，0 失败、0 错误、0 跳过。
- `lintDebug`：通过，无错误；47 项 warning、4 项 hint，未做无关大规模清理。
- `assembleDebug`、`assembleDebugAndroidTest`：通过。
- `python3 -m unittest discover -s scripts/tests -q`：35 项通过（模拟发布，不代表实际签名发布）。
- 构建使用单 worker、JDK17、SDK36，8 分 22 秒完成。全程没有并发运行模拟器。
- 对应 GitHub CI：[37815995136](https://github.com/csic21/FrameNest/actions/runs/37815995136)，已通过全部构建、单测、Lint 和产物上传；仪器测试只编译，没有在 GitHub CI 中运行。

## 设备验收限制

本轮确实尝试了官方 API36 x86_64 模拟器。云端没有 KVM/VMX/SVM，只能软件模拟。
首次启动未到可用状态时执行会话丢失：最后轮询在 2026-10-08 17:23:13 UTC，
内核日志停止在约 133 秒的 Android 服务启动阶段。恢复时同一 AVD 的正常重复实例
保护阻断启动；未删除锁、未绕过保护，也未启动第二个实例。

因此本轮 **没有运行任何仪器测试、截图/实际帧验证或系统安装器验证**。
这属于测试环境阻塞，不是测试通过，也不能判定为应用测试失败。真实 NAS、锁屏、
硬件音频输出、同签名覆盖安装及历史/凭证保留仍需实际设备确认。准备阶段静音不是
本轮保证。用户已获知此限制；继续使用通过的本地/远端构建、481 项 JVM 测试和独立
源码复查证据进行已授权的合并与发布，真实签名和公开产物另由发布流水线验证。
