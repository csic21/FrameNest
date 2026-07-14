## 播放状态机全面规整 交接

- 状态：完成
- 分支与提交：`agent/FN-player-state-cleanup`（见提交信息）
- 改动路径：
  - `app/src/main/java/com/framenest/player/VlcPlayerController.kt`（工作项 1/2/4/5/7 + Error 防 clobber）
  - `app/src/main/java/com/framenest/feature/player/PlayerViewModel.kt`（工作项 3/6/8 + retry 起点）
  - `app/src/main/java/com/framenest/feature/player/PlayerScreen.kt`（scrub 不 pause-resume）
  - `app/src/main/java/com/framenest/feature/player/ResumeSeekGate.kt`（新建，工作项 3）
  - `app/src/test/java/com/framenest/feature/player/ResumeSeekGateTest.kt`（新建，工作项 3 单测）
  - `docs/decisions/0006-player-state-cleanup.md`（新建决策记录）
- 验收结果：
  - 编译通过（`compileDebugKotlin` + unit tests）。
  - 新增 `ResumeSeekGateTest` 通过（含 `hasFired`）。
  - 全量单测通过。
  - 复核修复：`retry()` 不再盲目恢复 `request.startPositionMs`；`EncounteredError` /
    early EndReached 取消 seek-preview，`finishSeekPreview` 不覆盖 Error/Ended。
  - 真机安装/冒烟见合并后手测记录。
- 执行过的命令及结果：
  - `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "...ResumeSeekGateTest"` → SUCCESS
  - `./gradlew :app:testDebugUnitTest` → SUCCESS
- 决策记录：见 `docs/decisions/0006-player-state-cleanup.md`。未更改此前 accepted 决策。
- 未解决问题：
  - SMB 长片 / 暂停态拖动帧刷新等深度回归依赖用户 NAS 内容；建议在设备上按清单补验。
  - Ended 态可 seek 仍按现状（Ended 仅 replay）。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：
  - `VlcPlayerController` 接口签名未变；`handleEvent` / `resetTransientFlags` /
    `FRAME_SETTLE_MS` 均为 private。
  - `ResumeSeekGate`（internal）+ `hasFired` 可用于 retry 等「是否已接管」判断。
  - 新增 transient flag 必须加入 `resetTransientFlags(opening)`。
  - `play()` 必调 `resumeSeekGate.markFired()`；`retry()` 按 hasFired 选择 resume 起点。