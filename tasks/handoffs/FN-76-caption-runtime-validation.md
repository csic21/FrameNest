## FN-76 交接

- 状态：准备完成，设备执行与安全批准仍阻塞；workflow保持禁用。
- 分支与提交：`agent/FN-76-caption-runtime-validation`；提交见本文件所在commit。
- 改动路径：新增`.github/workflows/caption-diagnosis-runtime.yml`、
  `scripts/prepare_caption_runtime_pair.py`、`scripts/run_caption_runtime_sessions.sh`、
  `scripts/check_caption_runtime_results.py`、`scripts/tests/test_caption_runtime.py`、
  `docs/benchmarks/caption-diagnosis-2026-10-15/runtime-input-lock.json`及运行说明。
- 验收结果：15项离线合成archive/log测试通过，覆盖未固定身份禁止下载、APK与profile匹配、
  非预期skip/漏跑/重复诊断失败、4/4/16行完整性、语义验收保持null、门控和清理静态约束。
  Bash语法及YAML解析通过。没有执行Android、SDK安装、KVM/ACL命令或真实模型。
- 执行过的命令及结果：
  - `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p 'test_caption_runtime*.py' -v`：15/15通过。
  - `bash -n scripts/run_caption_runtime_sessions.sh`：通过。
  - PyYAML `safe_load`与job gate断言：通过，job仍为`${{ false }}`。
  - `git diff --check`：通过。
  - Gradle未运行：仅新增主机侧Python/Bash/workflow，运行APK仅消费现有普通CI保留产物。
- 决策记录：没有生产决策变更；沿用已发布0.6.10精确source AAR与现有ML Kit，Hy-MT关闭。
- 未解决问题：最终普通CI artifact ID/ZIP长度及四个hash待负责人固定；本次新KVM权限尚未批准。
  实机/模拟器UI与三路模型执行均未完成，不能声称字幕语义、准确率或性能通过。
- 集成Agent需要做的共享文件改动：无；只需挑选本提交，在成功CI后固定输入锁。
  取得本次精确安全授权前不得启用两个门控或dispatch。没有push。
- 后续任务可依赖的接口/行为：说明见`runtime-validation.md`。一个保留APK pair只安装一次，
  同一AVD手机/平板尺寸UI后执行一次4参考/4raw/16生产诊断；所有失败、UI与日志保留。
  `validation-summary.json`区分结构完整、UI结果与未进行的语义验收。

### 定点review修复验收

- reference/raw 的`returned`与`empty_target`必须携带与状态一致的真实`target_text`；
  `failed`必须有非空`mt_failure_class`；`skipped_empty_source`必须原文空白且译文为空字符串。
- 每个UI session的controls-360/840 PNG均必需：检查PNG结构、CRC、受限解压像素行和完整结束，
  尺寸匹配保留的display-0真实尺寸/rotation，并与配置视口交叉验证；缺失或不符失败关闭。
- 33项caption Python测试通过（其中21项runtime），`git diff --check`通过；仍未执行Android。
- `display.txt`在测试Activity启动前记录。两项Compose测试使用`createComposeRule`且无方向切换；
  对此次保留APK的manifest字符串池只读检查：app含`androidx.activity.ComponentActivity`，
  app/test均无`screenOrientation`属性，没有测试Activity锁横屏的声明。
- 本地旧run24/fn74证据没有找到完整`dumpsys display`；解析格式已对照AOSP
  `android-15.0.0_r1`的`LogicalDisplay.dumpLocked`及`DisplayInfo.toString`。
  首次授权运行仍须确认实际dump格式；无法识别时失败关闭，不猜测旋转或放行截图。
