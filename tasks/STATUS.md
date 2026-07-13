# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | agent/FN-00-android-skeleton → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 待集成 | agent | agent/FN-01-player-spike · `../FrameNest-FN-01` @ `a316ec6` | FN-00 | 分支内 `tasks/handoffs/FN-01.md` |
| FN-02 SMB 数据路径 spike | 待集成 | agent | agent/FN-02-smb-spike · `../FrameNest-FN-02` @ `8ffce51` | FN-00 | 分支内 `tasks/handoffs/FN-02.md` |
| FN-03 自适应 App 外壳 | 待集成 | agent | agent/FN-03-adaptive-shell · `../FrameNest-FN-03` @ `8adf86c` | FN-00 | 分支内 `tasks/handoffs/FN-03.md` |
| FN-04 服务器与 SMB 浏览 | 未开始 |  |  | FN-02, FN-03 |  |
| FN-05 SMB 播放与历史 | 未开始 |  |  | FN-01, FN-02, FN-03 |  |
| FN-06 字幕 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-07 缩略图与首帧 | 未开始 |  |  | FN-02, FN-04, FN-05 |  |
| FN-08 自适应体验打磨 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

## Wave 1 并行结果摘要

| 任务 | 结果 | 说明 |
|---|---|---|
| FN-01 | 部分完成 | 本地 libVLC 播放/首帧/seek/20×进出已过；**真实 NAS SMB 直连未测** |
| FN-02 | 部分完成 | SMBJ + seekable 数据路径决策已写；**真实 NAS 性能数字未采** |
| FN-03 | 完成 | 底部导航 / Rail + list-detail 假数据外壳 + 仪器测试 |

合并顺序建议：`FN-01` → `FN-02` → `FN-03`（每次合并后 `assembleDebug` + 单元测试）。  
注意：`libs.versions.toml`、`app/build.gradle.kts`、Manifest 三方均有改动，集成时需手工三方合并。

分配任务时只编辑本表的 Owner、状态和分支列。具体范围与验收标准以
`tasks/TASKS.md` 为准。
