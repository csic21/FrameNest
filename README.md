# FrameNest

FrameNest（栖影）是面向 Android 手机和平板的 NAS/SMB 视频播放器。

当前仓库处于 **规划完成、尚未生成 Android 代码** 的阶段。这样可以先用两个
短技术验证确定播放器和 SMB 数据路径，再由其他 Agent 按稳定边界实现，避免把
未经验证的选型固化进项目骨架。

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

## 从这里开始

1. 阅读 [产品范围](docs/PRODUCT.md)。
2. 阅读 [架构边界](docs/ARCHITECTURE.md)。
3. 按 [实施计划](docs/PLAN.md) 的波次分配 Agent。
4. 将 [任务卡](tasks/TASKS.md) 中对应的 `FN-XX` 段落直接交给 Agent。
5. 要求每个 Agent 按 [交接模板](tasks/HANDOFF.md) 回报。

首个实现任务是 `FN-00`。完成后可并行执行 `FN-01`、`FN-02`、`FN-03`。

