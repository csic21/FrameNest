# FrameNest

FrameNest（栖影）是面向 Android 手机和平板的 NAS/SMB 视频播放器。

当前仓库已完成 **Wave 1（FN-00～FN-03）集成**：单 module Android 项目，含
自适应导航外壳、libVLC 播放 spike、SMBJ 数据路径 spike 与两份决策记录。
产品级 SMB 浏览/续播仍待 Wave 2（FN-04 / FN-05）。

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
| Android SDK | Platform **36**（`compileSdk` / `targetSdk`）、Build-Tools 36.x |
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

**尚未引入** Room 等持久化（FN-04+）。决策记录：

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

# 一键：assemble + 单测 + lint
./gradlew assembleDebug testDebugUnitTest lintDebug
```

产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.framenest/.MainActivity
```

## 从这里开始

1. 阅读 [产品范围](docs/PRODUCT.md)。
2. 阅读 [架构边界](docs/ARCHITECTURE.md)。
3. 按 [实施计划](docs/PLAN.md) 的波次分配 Agent。
4. 将 [任务卡](tasks/TASKS.md) 中对应的 `FN-XX` 段落直接交给 Agent。
5. 要求每个 Agent 按 [交接模板](tasks/HANDOFF.md) 回报。

Wave 1 已集成。下一波可并行：`FN-04`（服务器与浏览）、`FN-05`（SMB 播放与历史）。
