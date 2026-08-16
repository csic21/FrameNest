# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 已完成 | agent | → main | FN-00 | [FN-01](handoffs/FN-01.md) |
| FN-02 SMB 数据路径 spike | 已完成 | agent | → main | FN-00 | [FN-02](handoffs/FN-02.md) |
| FN-03 自适应 App 外壳 | 已完成 | agent | → main | FN-00 | [FN-03](handoffs/FN-03.md) |
| FN-04 服务器与 SMB 浏览 | 已完成 | agent | → main | FN-02, FN-03 | [FN-04](handoffs/FN-04.md) |
| FN-05 SMB 播放与历史 | 已完成 | agent | → main | FN-01..FN-04 | [FN-05](handoffs/FN-05.md) |
| FN-06 字幕 | 已完成 | agent | → main | FN-04, FN-05 | [FN-06](handoffs/FN-06.md) |
| FN-07 缩略图与首帧 | 已完成 | agent | → main | FN-02, FN-04, FN-05 | [FN-07](handoffs/FN-07.md) |
| FN-08 自适应体验打磨 | 已完成 | agent | → main | FN-04, FN-05 | [FN-08](handoffs/FN-08.md) |
| FN-09 稳定与发布 | 已完成 | agent | → main | FN-04..FN-08 | [FN-09](handoffs/FN-09.md) |
| FN-10 听译 PCM spike | 已完成（仅 spike） | agent | → main | FN-01, FN-05 | [FN-10](handoffs/FN-10.md) |
| FN-11 听译 Room/清理 | 已完成 | agent | → main | FN-10 | [FN-11](handoffs/FN-11.md) |
| FN-12 听译管线+UI | 部分完成（stub） | agent | → main | FN-10, FN-11 | [FN-12](handoffs/FN-12.md) |
| FN-13 听译模型壳 | 部分完成（JSON 占位包） | agent | → main | FN-12 | [FN-13](handoffs/FN-13.md) |
| FN-14 听译真 ASR/MT | 已完成 | agent | `→ main` | FN-10..FN-13 | [FN-14](handoffs/FN-14.md) |
| FN-15 拖拽与控制交互 | 已完成 | agent | → main | FN-05, FN-08 | [FN-15](handoffs/FN-15.md) |
| FN-16 音轨/倍速/快进退 | 已完成 | agent | → main | FN-05, FN-08, FN-15 | [FN-16](handoffs/FN-16.md) |
| FN-17 锁屏与方向锁 | 已完成 | agent | → main | FN-08, FN-16 | [FN-17](handoffs/FN-17.md) |
| FN-18 同目录连播 | 已完成 | agent | → main | FN-05, FN-17 | [FN-18](handoffs/FN-18.md) |
| FN-19 缓冲提示 | 已完成 | agent | → main | FN-05 | [FN-19](handoffs/FN-19.md) |
| FN-20 播放器产品体验 | 已完成 | agent | → main | FN-16..FN-19 | [FN-20](handoffs/FN-20.md) |
| FN-21 听译模型交付 | 已完成 | agent | → main | FN-14, FN-20 | [FN-21](handoffs/FN-21.md) |
| FN-22 SMB/缩略图性能 | 已完成 | agent | → main | FN-07, FN-09, FN-21 | [FN-22](handoffs/FN-22.md) |
| FN-23 自适应预翻译 | 已完成 | agent | → main | FN-14, FN-21, FN-22 | [FN-23](handoffs/FN-23.md) |
| FN-24 应用图标焕新 | 已完成 | agent | → main | FN-03 | [FN-24](handoffs/FN-24.md) |
| FN-25 Release 启动崩溃修复 | 已完成 | agent | → main | FN-14, FN-21, FN-24 | [FN-25](handoffs/FN-25.md) |
| FN-26 Release 反射/JNI 兼容 | 已完成 | agent | → main | FN-22, FN-25 | [FN-26](handoffs/FN-26.md) |
| FN-27 听译空结果诊断与恢复 | 已完成 | agent | → main | FN-23, FN-26 | [FN-27](handoffs/FN-27.md) |
| FN-28 播放器旋转续播与画布恢复 | 已完成 | agent | → main | FN-05, FN-15, FN-20 | [FN-28](handoffs/FN-28.md) |
| FN-29 锁屏后听译 SMB 音频自动恢复 | 已完成 | agent | → main | FN-27, FN-28 | [FN-29](handoffs/FN-29.md) |
| FN-30 播放首帧与退出生命周期 | 已完成 | Codex | main | FN-05, FN-20, FN-28 | [FN-30](handoffs/FN-30.md) |
| FN-31 大目录浏览内存与无障碍优化 | 已完成 | Codex | `agent/FN-31-browse-memory-a11y` | FN-07, FN-08, FN-22 | [FN-31](handoffs/FN-31.md) |
| FN-32 可见区域优先的缩略图调度 | 已完成 | Codex | `agent/FN-32-thumbnail-visible-priority` | FN-22, FN-31 | [FN-32](handoffs/FN-32.md) |
| FN-33 品牌 Logo 与应用主题统一 | 已完成 | Codex | `agent/FN-33-brand-system` | FN-24 | [FN-33](handoffs/FN-33.md) |
| FN-34 播放器返回不卡主线程 | 已完成 | Codex | `main` | FN-30 | [FN-34](handoffs/FN-34.md) |
| FN-35 Room 数据迁移与听译写入减负 | 已完成 | Codex | `agent/FN-35-room-stability` | FN-11, FN-23 | [FN-35](handoffs/FN-35.md) |
| FN-36 听译 cue 缓存热路径优化 | 已完成 | Codex | `agent/FN-36-listen-cache-hotpath` | FN-23, FN-35 | [FN-36](handoffs/FN-36.md) |

> FN-14：产品路径为 **PCM → Vosk small 离线 ASR → ML Kit 本机 MT**。首次需下载
> 源语言模型；质量受 small 模型与对白清晰度限制。详见 handoff。

**当前发布**：`0.3.1-internal`（versionCode 4）

| 产物 | 路径 | 约大小 |
|---|---|---|
| Debug universal | `app/build/outputs/apk/debug/app-universal-debug.apk` | 168.7MB |
| Debug arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 93.9MB |
| Debug x86_64 | `app/build/outputs/apk/debug/app-x86_64-debug.apk` | 100.9MB |
| Release universal | `app/build/outputs/apk/release/app-universal-release-unsigned.apk` | 146.8MB |
| Release arm64 | `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` | 72.0MB |
| Release x86_64 | `app/build/outputs/apk/release/app-x86_64-release-unsigned.apk` | 79.0MB |

NAS 验收勾选：`docs/NAS-ACCEPTANCE-CHECKLIST.md`  
后续 backlog：`docs/POST-MVP.md`
