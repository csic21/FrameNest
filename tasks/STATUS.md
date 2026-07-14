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
| FN-06 字幕 | 进行中 | agent | agent/FN-06-subtitles · `../FrameNest-FN-06` | FN-04, FN-05 |  |
| FN-07 缩略图与首帧 | 进行中 | agent | agent/FN-07-thumbnails · `../FrameNest-FN-07` | FN-02, FN-04, FN-05 |  |
| FN-08 自适应体验打磨 | 进行中 | agent | agent/FN-08-adaptive-polish · `../FrameNest-FN-08` | FN-04, FN-05 |  |
| FN-09 稳定与发布 | 未开始 |  |  | FN-04..FN-08 |  |

## Wave 3 并行约定

- **FN-06**：`feature/subtitle/**` + 播放器字幕 API/UI；勿改缩略图管线。
- **FN-07**：`data/thumbnail/**` + 浏览列表封面；播放页仅确保 first-frame-ready 门闩；单并发生成。
- **FN-08**：响应式布局/无障碍/触控/旋转；**不改** SMB/播放数据实现；与 FN-06/07 UI 冲突时写交接补丁。

分配任务时只编辑本表的 Owner、状态和分支列。具体范围与验收标准以
`tasks/TASKS.md` 为准。
