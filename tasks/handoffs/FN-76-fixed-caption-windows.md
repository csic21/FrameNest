## FN-76 交接：固定 3 秒 / 6 秒全片对照

- 状态：实现与本地定向验证完成；真实 Android 编译/运行及语义审查待集成 owner。
- 分支与提交：`agent/fn76-fixed-caption-windows`，基于 `99d90f6`；本交接同提交。
- 改动路径：新增 FixedCaptionWindowComparisonTest / Fixture / FixtureTest；caption-diagnosis 的固定比较 manifest；新增 Python schema / 准备器 / 定向测试；诊断文档；原 source-build workflow 只增加受控 Emoji prepare 和相关路径触发。
- 验收结果：MCDC 47.720 秒和完整 Emoji 113.960 秒各跑 3000/6000 ms 两个条件（16/8 与 38/19，共 81 行）；同一模型和 ML Kit 实例提前准备并共享，新 RealEngine 隔离每个条件的 retry cache。默认产品、750 ms context、token 选取、模型、运行库、MT 均未改。
- 执行过的命令及结果：
  - `python3 -m unittest discover -s scripts/tests -p 'test*caption*.py' -v`：21 项通过。
  - 原 `prepare-caption-diagnosis.py` + 新 `prepare-fixed-caption-window-comparison.py`：完整本地输入校验/打包通过；新增只有 emoji.s16le 和 comparison-prepared.json，没有另一份模型或旧 FN-74 corpus。
  - 从完整 Emoji WebM 执行无 `-ss/-t` 的 FFmpeg decode，与旧 WAV 直接读取样本均得到既有 s16le SHA-256 `e3e4e1de9395948bc3e03c0f88a93cb55bbbb0768280344bd7f4bd3f164700a6`。
  - `/tmp/fn76-fixed-syntax/compile.sh`：真实 production engine / Sherpa / helper 与精确 source AAR classes.jar 的 Kotlin 编译通过；Android/JSON/ML Kit 仅 API stubs。disabled-opt-in smoke 在 JNI/assets/network 前跳过，JUnit 方法为 JVM void。不是 Android 或语义运行。
  - `git diff --check`：通过。
- 决策记录：无生产决策或默认变更；本次严格固定 3/6 两条件，真实结果前不推荐切换默认。
- 未解决问题：没有 Android SDK/真实设备验收，未运行 Gradle/模型/MT。完整三路旧诊断不用重跑来代替这次固定比较；没有 NAS/codec/player/rendering/冷启动/速度结论。Kotlin 小 parser tests 已编译，需 owner Android 执行。
- 集成 Agent 需要做的共享文件改动：已有 runtime-validation worker 负责测试 APK 身份/结果 checker 和显式 gate workflow；参数 `fixedCaptionWindowComparison=true`、`captionDiagnosisNetwork=true`，安装 APK SHA 参数不变。需独立外部 20 分钟 instrumentation watchdog；协程 timeout 不能中断卡住的 JNI。报告原子写入并保留进行中/已完成证据，早期验证先替换旧成功标记。
- 后续任务可依赖的接口/行为：`FixedCaptionWindowComparisonTest#compareThreeAndSixSecondWindows`；结果 `files/fixed-caption-window-comparison.json`；schema 1，`fixtures[].conditions[].rows`、`coverage`、`final_eof`。静态比较 manifest SHA-256 `69270ac4a4b1979ee398887853e4e09af4efe0337905632ec47cb65c5702d9be`。完整字段与复现见 `docs/benchmarks/caption-diagnosis-2026-10-15/FIXED-WINDOW-COMPARISON.md`。
