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
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

## Wave 3 集成结果

| 任务 | 结果 |
|---|---|
| FN-06 | 内嵌 + 外挂字幕（匹配排序、addSlave、延迟/字号） |
| FN-07 | 列表缩略图（单并发 MMR）+ `canPlay` 首帧门闩 |
| FN-08 | 自适应/无障碍；播放页 NavigationSuiteType.None |

`versionName`：`0.3.0-wave3`。下一任务：**FN-09** 稳定与发布。
