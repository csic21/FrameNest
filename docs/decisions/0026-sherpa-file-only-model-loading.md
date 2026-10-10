# 0026：sherpa-onnx 只能从文件加载 SenseVoice 模型

- 状态：accepted
- 日期：2026-10-10
- 任务：FN-64（修复 0.6.3-internal「一开启听译就闪退」）

## 问题

0.6.3-internal 在真机（OnePlus PME110 / Android 17，已装 SenseVoice 模型）上
开启听译必闪退。进程不是 Java 崩溃：`ApplicationExitInfo` 记录
`reason=1 (EXIT_SELF) status=255`，崩溃缓冲区无 FATAL/tombstone。

必须决定 `SherpaAsrEngine` 如何构造 `OfflineRecognizer`，避免 sherpa-onnx
原生库对绝对路径 + AssetManager 组合的进程级退出。

## 证据

真机 logcat（19:55:33，启用听译、SMB 已认证、模型目录已存在）：

```
W sherpa-onnx: You are using an absolute path
  '/data/user/0/com.framenest/files/listen_models/sherpa/sensevoice-2024-07-17-int8/tokens.txt',
  but assetManager is NOT set to null.
F sherpa-onnx: Read binary file: Load '.../tokens.txt' failed
D RefBase: #01 exit+40
D RefBase: #05 ...Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromAsset+804
I Zygote : Process <pid> exited cleanly (255)
```

即 `OfflineRecognizer(assetManager, config)` 在两个模型路径都是绝对路径时进入
[json 问题 #2562](https://github.com/k2-fsa/sherpa-onnx/issues/2562) 所述分支，
原生 `exit(255)` 直接结束进程，Kotlin 层无法捕获。

javap 核对 1.12.32 AAR：构造函数为 `(AssetManager?, OfflineRecognizerConfig)`，
`assetManager == null` 时调用原生 `newFromFile`，非 null 时调用 `newFromAsset`。

首次实现（a16b238）本就用单参 `OfflineRecognizer(config)`；`0e76bca` 为对齐
Kotlin API 时误加了进程 AssetManager，注释写成“原生层必须有”，与原生行为相反。

## 决定

- `SherpaAsrEngine` 不再接收 `AssetManager`，构造识别器时显式使用默认的
  `assetManager = null`（`newFromFile`）。模型永远来自私有目录绝对路径。
- 增加反射回归测试：`SherpaAsrEngine` 的任何构造函数都不得出现
  `AssetManager` 参数，防止再次引入进程级退出组合。
- 不改窗口、缓存身份、模型安装、Vosk/ML Kit 路径与播放行为。

## 未选择的方案

- 保留参数并在调用处传 `null`：签名仍允许再次传错，回归风险不变。
- 把模型复制进 assets：239MB 权重不应进 APK，且与安装/校验/清理流程冲突。
- 用异常包装原生失败：`exit()` 不会抛错，Java 层无从捕获。

## 后果

- SenseVoice 模型必须始终以可校验文件存在于私有存储；`SherpaModelInstaller`
  的 `.ready`/摘要校验是唯一就绪来源。
- sherpa-onnx 升级时须重新核对 `newFromFile` 路径与默认参数语义。
