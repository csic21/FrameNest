# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | agent/FN-00-android-skeleton → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 已完成 | agent | agent/FN-01-player-spike → main | FN-00 | [FN-01](handoffs/FN-01.md) |
| FN-02 SMB 数据路径 spike | 已完成 | agent | agent/FN-02-smb-spike → main | FN-00 | [FN-02](handoffs/FN-02.md) |
| FN-03 自适应 App 外壳 | 已完成 | agent | agent/FN-03-adaptive-shell → main | FN-00 | [FN-03](handoffs/FN-03.md) |
| FN-04 服务器与 SMB 浏览 | 已完成 | agent | agent/FN-04-servers-browser → main | FN-02, FN-03 | [FN-04](handoffs/FN-04.md) |
| FN-05 SMB 播放与历史 | 已完成 | agent | agent/FN-05-smb-playback → main | FN-01..FN-04 | [FN-05](handoffs/FN-05.md) |
| FN-06 字幕 | 未开始 |  |  | FN-04, FN-05 |  |
| FN-07 缩略图与首帧 | 未开始 |  |  | FN-02, FN-04, FN-05 |  |
| FN-08 自适应体验打磨 | 待集成 | agent | agent/FN-08-adaptive-polish | FN-04, FN-05 | [FN-08](handoffs/FN-08.md) |
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

## Wave 2 集成结果

| 任务 | 结果 | 说明 |
|---|---|---|
| FN-04 | 已合并 | Room 服务器 + 加密凭证 + 真实浏览 UI |
| FN-05 | 已合并 | 产品播放页 + 历史；数据路径 B（SMBJ seekable） |
| 集成 | AppDatabase 统一 | `servers` + `playback_history`；`PlayerRoute` 从凭证库构建 SeekableSmb |

`main` versionName：`0.2.0-wave2`。真实 NAS 端到端仍需用户配置服务器。

分配任务时只编辑本表的 Owner、状态和分支列。具体范围与验收标准以
`tasks/TASKS.md` 为准。
