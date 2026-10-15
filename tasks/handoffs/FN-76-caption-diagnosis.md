## FN-76 交接

- 状态：诊断测试实现完成；真实 Android 编译、三列模型执行和语义审查尚未完成。
- 分支与基线：`agent/FN-76-caption-diagnosis`，基于 main `d8cef219`。
- 改动路径：新增 CaptionDiagnosis instrumentation/parser tests、固定公开 fixture manifest/reference/profile、prepare/schema 脚本与 12 项 Python 测试、诊断文档、原20窗 host probe 证据。没有修改 main 生产代码、模型、窗口策略、workflow 或 TASKS。
- 验收结果：12 项 Python 测试通过；固定模型、原片、PCM、参考文件校验及生成资产打包通过；从完整原片再次执行 FFmpeg 得到完全相同的 1,527,040 字节 PCM。生成模型/媒体不提交。
- 执行过的命令及结果：`python3 -m unittest discover -s scripts/tests -p 'test_caption_diagnosis.py' -v` 通过；`python3 scripts/prepare-caption-diagnosis.py --input-dir ../framenest-fn76-mcdc --model-dir ../reazon-ab/models/sensevoice` 通过；真实 FFmpeg download-mode reproduction 路径通过（复用已核完整原片，没有再次联网）；`git diff --cached --check` 通过。
- Kotlin 检查：实际 1.13.8 source AAR classes.jar、实际 RealEngine/Sherpa 源码和纯逻辑 helper，加 Android/MLKit/JSON 小型 stub 的语法/API 编译通过；未 opt-in 时在 JNI/asset/model/network 前跳过；JUnit 方法返回 JVM void。该检查不是 Android 构建或模型执行，未用 stub 生成任何识别/译文。
- 决策记录：只新增测试，未改变已接受生产决策。MCDC 参考是有人工修改记录的公开字幕，四个语义组由助手在看本轮 ASR 之前固定；不是独立听审逐字 gold，semantic acceptance 始终 null。
- 未解决问题：实际 Android compile/lint/instrumentation 及4条 reference、4条 raw span、16条 production 窗真实MLKit输出待集成运行；无新 KVM 操作、手机/平板/NAS/渲染或听审验收。
- 集成 Agent 需要做的共享文件改动：TASKS/FN76 项目登记、source=true/Hy=false 普通 CI 和经授权后的保留 APK 实际运行由 owner 负责。不得把普通 debug 的1.12.32当本轮 source 构建。
- 后续任务可依赖的接口/行为：`CaptionDiagnosisTest` 需 `captionDiagnosis=true`、`captionDiagnosisNetwork=true`、`expectedAppApkSha256`、`expectedTestApkSha256`；产物 `files/caption-diagnosis.json`。prepare 支持显式 `--download`，只下载固定原WebM并复现精确PCM，reference.json随测试提交，模型目录仅需已有两文件。小型 `CaptionDiagnosisFixtureTest` 可默认执行，无模型/网络。
