## FN-75 反馈可见性回归交接

- 状态：定点修复已完成；本地 Kotlin 语法和 diff 检查通过。新包 Android 编译与手机/平板执行待集成负责人完成。
- 分支与基线：`agent/FN-75-feedback-visibility`，独立 worktree，基于 `99d90f6ab935aa025bcece6ba7d32858da777c84`。该基线与 PR 8 的 `54849318ccaaf8788f6cd3cd25d683407f3fec60` tree 相同。
- 改动路径：`ListenTranslateControls.kt`、`ListenTranslateDiagnosticsControlsTest.kt`、决策 0037、本交接。
- 原始证据：API 35 run `38837762864` 的 phone/tablet 完整 instrumentation 日志各 39 项：33 通过、4 个 Hy-native 跳过、2 个失败。失败均为反馈用例的 360dp/840dp 参数实例，停在第一个 `listen_translate_enable` 显示断言；每台的 20 个 overlay 实例均通过。准备态截图不是安装态失败现场，不能证明失败时的屏幕几何。
- 证据校验：phone `instrumentation.txt` SHA-256 为 `744a32dbe5aaf94a837a32eafe3cc4c5d60a5a31efcfb7a5e3776cc7d38314a7`；tablet 为 `04ca2cb46c3339b72bbeacb7bd0853b62bb694f55dd104a628aa709dfbe384cd`。
- 诊断：真实可操作节点是带 `toggleable` 的 Row，Compose 合并其子语义；旧 tag 位于装饰性 child Switch，不会传播到父节点。默认 merged-tree 查询没有匹配节点也会使 `assertIsDisplayed` 返回 false。代码和 AndroidX 原始实现支持选择器原因；原始日志不能单独证明滚动或裁剪缺陷。
- 最小修复：将既有 enable tag 放在 Row，为实际 Switch 图形另设 indicator tag。UI 几何、启停逻辑与滚动行为均不变。
- 加强回归：在原 260dp 高、360dp/840dp 请求宽度中保留安装/运行/失败/关闭反馈可见检查；新增 Row 存在与 on/off、enabled/disabled 语义断言，unmerged indicator 可见断言，以及每次状态变化后滚动位置必须为 0 的断言。几何顺序仍使用实际 Switch 图形 bounds。没有添加滚动、等待重试或跳过。
- 已执行检查：
  - Kotlin PSI 解析两份改动 `.kt` 文件：通过；仅语法，不代表 Android/Compose 类型编译。
  - `git diff --check`：通过。
  - `GRADLE_USER_HOME=/tmp/fn75-feedback-visibility/gradle-home ./gradlew -PhyMtNative=false testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon`：Gradle 9.6.1 wrapper 下载阶段失败，`java.net.SocketException: Network is unreachable`，项目尚未配置。没有取得新的完整单测、lint、Android 编译或设备执行结果。
- 决策记录：`docs/decisions/0037-caption-translation-feedback.md` 新增 API 35 回归说明。只修正测试节点标识，不改变已接受的产品布局。
- 未解决问题：需要新包重新执行 phone/tablet 的参数化反馈用例和现有 overlay/controls 回归，不能把旧包通过项当作新包验收。3 秒/6 秒固定窗对照由集成负责人协调，本文未改变产品窗口默认值。
- 集成 Agent 需要做的共享文件改动：无；可直接 cherry-pick 本提交。不涉及 KVM、系统权限、远端写入、发布文件或模型配置。
