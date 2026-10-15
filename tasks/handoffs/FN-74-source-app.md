## FN-74 交接：source-ASR 独立 app profile

- 状态：首跑 assemble/881 JVM tests/AndroidTest 编译/lint 通过；已修复静态审计的单一生成 R 类漏项，新增许可入口/固定 harness 待第二次普通 CI。设备/发布门槛未完成。
- 分支与提交：`experiment/FN-74-asr-source-app`；从 source recipe `52f6d40` 创建。首个实现 `0676439`，不修改 stock PR #6 或稳定分支。
- 改动路径：`app/build.gradle.kts`、`AsrModelSupport.kt` 与对应单测、固定 artifact 导入脚本/锁、独立普通工作流/APK 审计、说明/决策文档。
- 验收结果：本地完整核验并导入原 artifact ZIP 与 AAR；113 项 Python 回归通过，含真实 AAR 与不可安装的 synthetic APK 容器；许可 reader 10 项 JVM 回归、30 份真实许可全文读取/校验/无损分页通过。固定 AAR SHA `6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`。首跑四项 Android 检查通过，7 项新增 Compose 测试与 source harness 仍待最终 CI 编译及后续设备执行。
- 执行过的命令及结果：`python3 scripts/prepare_source_asr.py --archive ../fn74-aar-strict-run1/artifact.zip --report /tmp/fn74-source-app-import.json` 成功；`python3 -m unittest discover -s scripts/tests -p 'test_prepare_source_asr.py' -v` 6 项通过；`python3 -m py_compile scripts/prepare_source_asr.py` 和 `git diff --check` 通过。
- 决策记录：`docs/decisions/0035-experimental-source-asr-profile.md`。仅 `-PsherpaAsrSource=true` 使用此 AAR，默认依赖/缓存身份不变。upstream runtime version 与含完整 SHA 的实验身份分开。source profile 拒绝 release。
- 未解决问题：包含最终许可 UI/补充文本/harness 的 APK 编译/静态审计待 CI；实际 JNI load/session/PCM/release、真实 4KB/16KB、性能/准确率/时间戳/取消、完整许可与重现性均未通过此任务。ORT arm64 RELRO 保留 warning，不等于实测失败。Actions input artifact 2026-10-29 到期后禁止自动替换输入。
- 集成 Agent 需要做的共享文件改动：无；所有共享文件修改仅独立实验分支，不可直接移植为默认运行库。
- 后续任务可依赖的接口/行为：`SHERPA_RUNTIME_VERSION=1.13.8` 用于 actual VersionInfo 核验；`SHERPA_RUNTIME_IDENTITY` 和 `SHERPA_AAR_SHA256` 标识具体 no-TTS 源构建。CI 保留 exact app APK 与 compiled test APK；后续运行测试应记录并使用对应 APK SHA，而不是沿用 stock ABBA 结论。

- 最终产物契约：一个 APK artifact 含 4 APK + source-app-pair.json + 4 份证据 JSON；manifest 明确上游版本 expected=1.13.8 / observed=null、source runtime identity/full AAR SHA、来源 run/head 与每个 APK 的字节数/SHA，模型/PCM 包成员在实际 test APK 中重新核验。普通 CI 不运行设备。
