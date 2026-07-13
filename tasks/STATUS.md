# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | agent/FN-00-android-skeleton → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 进行中 | agent | agent/FN-01-player-spike · `../FrameNest-FN-01` | FN-00 |  |
| FN-02 SMB 数据路径 spike | 进行中 | agent | agent/FN-02-smb-spike · `../FrameNest-FN-02` | FN-00 |  |
| FN-03 自适应 App 外壳 | 进行中 | agent | agent/FN-03-adaptive-shell · `../FrameNest-FN-03` | FN-00 |  |
| FN-04 服务器与 SMB 浏览 | 未开始 |  |  | FN-02, FN-03 |  |
| FN-05 SMB 播放与历史 | 未开始 |  |  | FN-01, FN-02, FN-03 |  |
| FN-06 字幕 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-07 缩略图与首帧 | 未开始 |  |  | FN-02, FN-04, FN-05 |  |
| FN-08 自适应体验打磨 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

分配任务时只编辑本表的 Owner、状态和分支列。具体范围与验收标准以
`tasks/TASKS.md` 为准。
