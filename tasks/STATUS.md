# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | agent/FN-00-android-skeleton → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 已完成 | agent | agent/FN-01-player-spike → main | FN-00 | [FN-01](handoffs/FN-01.md) |
| FN-02 SMB 数据路径 spike | 已完成 | agent | agent/FN-02-smb-spike → main | FN-00 | [FN-02](handoffs/FN-02.md) |
| FN-03 自适应 App 外壳 | 已完成 | agent | agent/FN-03-adaptive-shell → main | FN-00 | [FN-03](handoffs/FN-03.md) |
| FN-04 服务器与 SMB 浏览 | 未开始 |  |  | FN-02, FN-03 |  |
| FN-05 SMB 播放与历史 | 未开始 |  |  | FN-01, FN-02, FN-03 |  |
| FN-06 字幕 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-07 缩略图与首帧 | 未开始 |  |  | FN-02, FN-04, FN-05 |  |
| FN-08 自适应体验打磨 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

## Wave 1 集成结果

| 任务 | 结果 | 说明 |
|---|---|---|
| FN-01 | 已合并（部分验收） | 本地 libVLC 播放/首帧/seek/20×进出已过；**真实 NAS SMB 直连未测** |
| FN-02 | 已合并（部分验收） | SMBJ + seekable 数据路径决策已写；**真实 NAS 性能数字未采** |
| FN-03 | 已合并 | 底部导航 / Rail + list-detail 假数据外壳 |

`main` 上 versionName：`0.1.0-wave1`。决策记录：`docs/decisions/0001-player-engine.md`、`0002-smb-data-path.md`。

分配任务时只编辑本表的 Owner、状态和分支列。具体范围与验收标准以
`tasks/TASKS.md` 为准。
