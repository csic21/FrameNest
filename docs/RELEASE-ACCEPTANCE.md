# 发布验收记录 — FrameNest 0.3.0-internal

- **版本**：`0.3.0-internal`（versionCode 3）
- **构建日期**：2026-07-14
- **分支**：`agent/FN-09-release` → 待并入 `main`
- **自动检查**：`./gradlew assembleDebug testDebugUnitTest lintDebug`（见 handoff）

## 设备 / 环境

| 项 | 值 |
|---|---|
| 手机模拟器 | `Medium_Phone_API_36.1`（API 36） |
| 平板模拟器 | `Pixel_Tablet`（可选补测） |
| JDK | 17 |
| 本机 SDK | compileSdk/targetSdk 36 |
| 真实 NAS | **本集成环境未连接** — 场景 1–7 中依赖 NAS 的项标记为「待用户侧验证」 |

## PRODUCT 发布验收场景

| # | 场景 | 自动化 / 本机 | 状态 |
|---|---|---|---|
| 1 | 添加 SMB2/3，浏览大目录 | UI 与单元/结构就绪；500 条 IO 线程 | **待 NAS** |
| 2 | 1080p H.264 / HEVC 播放与 seek | 本地 raw H.264 样本 + 播放器路径就绪 | **部分**（本地样本通过；NAS 1080p 待 NAS） |
| 3 | 断网错误可重试 | `SmbError` / `PlayerErrorMapper` 映射 | **逻辑通过**；实网待 NAS |
| 4 | UTF-8 SRT 自动 + 内嵌轨 | 匹配排序单测；播放器字幕 API | **逻辑通过**；实机字幕待 NAS |
| 5 | 退出续播 | 历史规则单测 + Room | **逻辑通过** |
| 6 | 缩略图首次生成 / 缓存命中 | Thumbnail 单测 + 磁盘缓存 | **逻辑通过**；实目录待 NAS |
| 7 | 播放页先首帧再点播 | `firstFrameReady` / `canPlay` | **本地样本通过** |

## 稳定性抽查（无 NAS）

| 检查 | 结果 |
|---|---|
| 冷启动 MainActivity | 通过（模拟器安装） |
| 设置：清理缓存 / 语言 / 导出诊断 | 已实现 |
| 连续进出播放（spike 仪器测试路径） | 既有 `ProductPlayerEnterExitTest` / `PlayerSpikeEnterExitTest` |
| 日志无明文密码 | CredentialRedactor + 导出脱敏；单测覆盖 |

## APK

| 变体 | 路径 | 备注 |
|---|---|---|
| debug universal | `app/build/outputs/apk/debug/app-universal-debug.apk` | 内部测试；含 arm64-v8a / x86_64 与 debug 符号 |
| debug arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 真机测试用较小分包 |
| internal 命名 | versionName `0.3.0-internal` | 未单独 productFlavor；以 versionName 区分 |

## 安全审查（仓库 / 产物）

- [x] 无密钥文件提交
- [x] NAS 凭证只在 App 运行时输入，不由本地属性注入 BuildConfig / APK
- [x] 诊断导出脱敏
- [x] Spike Activity `exported=false`
- [x] Room 无密码列

## 签字

| 角色 | 结果 |
|---|---|
| 工程 Agent | 自动检查 + 文档完成；NAS 场景需用户设备确认 |
