# FrameNest

映舟（FrameNest）是面向 Android 手机和平板的 NAS/SMB 视频播放器。

当前仓库已完成 **MVP + 听译 Beta + 播放器产品控制**（FN-00 … FN-49）。
版本见 `CHANGELOG.md`；验收记录见 `docs/RELEASE-ACCEPTANCE.md`。

## MVP 一句话

打开 App → 添加 NAS → 浏览 SMB 目录 → 看到视频封面 → 载入字幕 → 流畅播放 →
下次从上次位置继续。

## 建议技术路线

- Kotlin + Jetpack Compose + Material 3 Adaptive
- libVLC 作为首选播放内核，须由 `FN-01` 验证
- SMBJ 作为首选 SMB2/3 客户端，须由 `FN-02` 验证
- Room 保存服务器元数据、播放历史和缩略图索引
- Android Keystore 保护凭证；不在 Room 中保存明文密码
- 列表缩略图与播放页首帧预渲染是两个独立能力

## 构建要求

| 工具 | 要求 |
|---|---|
| JDK | **17**（`JAVA_HOME` 指向 JDK 17） |
| Android SDK | Platform **36**（`compileSdk` / `targetSdk`）、Build-Tools **36.0.0** |
| Gradle | Wrapper 自带 **9.6.1**（AGP 9.2.1 最低要求 9.4.1） |
| minSdk | 26 |

在仓库根目录创建 `local.properties`（已在 `.gitignore` 中）：

```properties
sdk.dir=/path/to/Android/sdk
```

macOS 常见路径：`sdk.dir=/Users/<you>/Library/Android/sdk`。

### 版本目录（Wave 1）

见 `gradle/libs.versions.toml`：

- Android Gradle Plugin **9.2.1**（内置 Kotlin，无需 `kotlin-android` 插件）
- Compose Compiler 插件 Kotlin **2.4.0**
- Compose BOM **2026.06.01**；Navigation Compose **2.9.8**
- `core-ktx` 1.18.0 / Lifecycle 2.10.0（与 compileSdk 36 对齐；更高 AndroidX 需 SDK 37）
- **libVLC** 3.6.5（FN-01）；**SMBJ** 0.14.0 + Coroutines 1.10.2（FN-02）

Room、SMB 播放、缩略图、字幕、听译与设置持久化均已接入。关键决策记录：

- [0001 播放内核](docs/decisions/0001-player-engine.md)
- [0002 SMB 数据路径](docs/decisions/0002-smb-data-path.md)

Spike 入口（可选，adb）：

```bash
adb shell am start -n com.framenest/.feature.player.PlayerSpikeActivity
adb shell am start -n com.framenest/.smb.spike.SmbSpikeActivity
```

## 常用命令

```bash
# Debug APK
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# 仪器测试（需模拟器/设备；Compose smoke）
./gradlew connectedDebugAndroidTest

# 只编译仪器测试 APK（不代表已在设备上通过）
./gradlew assembleDebugAndroidTest

# 一键：assemble + 单测 + lint
./gradlew assembleDebug testDebugUnitTest lintDebug
```

CI 显式安装 `platform-tools`、`platforms;android-36` 和 `build-tools;36.0.0`，
避免 `setup-android@v3` 默认请求已下线的 `tools` 包。复用 hosted runner
已有的稳定版 SDK 许可，不批量接受无关许可；缺少目标 SDK 文件时直接失败。
CI 包含仪器测试 APK 编译；手机和平板的实际验证仍需执行 `connectedDebugAndroidTest`。

产物路径：

```text
app/build/outputs/apk/debug/app-universal-debug.apk
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell am start -n com.framenest/.MainActivity
```

## 从这里开始

1. 阅读 [产品范围](docs/PRODUCT.md)。
2. 阅读 [架构边界](docs/ARCHITECTURE.md)。
3. 按 [实施计划](docs/PLAN.md) 的波次分配 Agent。
4. 将 [任务卡](tasks/TASKS.md) 中对应的 `FN-XX` 段落直接交给 Agent。
5. 要求每个 Agent 按 [交接模板](tasks/HANDOFF.md) 回报。

已发布内部测试版：`0.6.9-internal`；`0.6.10-internal` 是已完成源码运行验收、待最终签名构建的发布候选。安装：

```bash
./gradlew assembleDebug
# 通用包（含 arm64 + x86_64）
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
# 或真机 arm64 分包更小：
# adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```


## SMB 安全策略（0.6.3）

SMBJ 浏览、目录/字幕及听译辅助读取默认要求签名和 SMB3 加密。不支持时会停止并提示；
如确需旧 NAS 兼容，可以在该服务器的编辑页主动选择“仅要求签名、允许未加密”模式。
此选项按服务器保存，没有自动降级。独立 libVLC 视频播放和 VLC 抓帧另行协商，不能把
SMBJ 策略视为其加密保证。升级保留服务器与凭证引用，已有服务器默认进入加密模式。

本机听译诊断不上传音频/字幕。ML Kit 自身的 SDK 统计遵循 Google 的
[数据披露](https://developers.google.com/ml-kit/android-data-disclosure)；不能宣称零遥测。


## 0.6.10 源码 ASR 发布候选

候选发布使用已验证的 sherpa-onnx 1.13.8 ASR-facing/no-TTS AAR（SHA-256
`6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503`），
模型与 ML Kit 翻译不变，Hy-MT 仍关闭和隐藏。升级有机会改善某些识别结果，
不保证准确率提高，也没有这份源码构建的同条件实测提速结论。

发布构建从既有、锁定的同仓库 Actions artifact 取 AAR，不依赖尚未发布的 URL。
公开发布后可显式执行 `python3 scripts/prepare_source_asr_from_release.py`，
从固定版本地址读取同一 AAR 并再次核验长度和哈希；不存在或不匹配即失败，无自动替换。
然后用 `./gradlew -PsherpaAsrSource=true -PhyMtNative=false assembleDebug` 重现源码运行库集成。
开发默认仍是旧版 Maven 依赖，也可明确指定 `-PsherpaAsrSource=false`；旧版 profile 禁止发布。
两种运行库使用不同缓存身份，下载的 SenseVoice 模型不变。

设置中的“开源许可与源码（ASR 实验）”可完整离线阅读 29 份原始许可和补充说明，
包括 Eigen 源码链接及 Android simple-sentencepiece 变更说明。计划发布附件包含精确 AAR、
完整 NOTICE 和来源记录。它们没有单独下载模型或上传语音。

PT_LOAD/ZIP 的 16KB 静态对齐通过，arm64 16KB 真机仍未验收。ORT 未取整 RELRO
末端警告尚存，但未发现已知 LOAD 保护重叠或已证实的运行失败。测试范围、候选审批和
同签名发布流程见 [发布说明](docs/RELEASING.md)。

源码运行库已在固定公开语料上完成两次 Android 会话：396 行文本/时间戳与 stock 1.13.8
逐行一致（不比较跨环境耗时），Kishida114 的 CER 为 38/436（约 8.72%）。仍有
「広島サミット→広島さと」「握り拳→握りこ」等误识别；没有普遍准确率或提速保证。
手机宽度模拟器上的 7 项许可界面测试通过，不能替代平板或 arm64 真机验收。
