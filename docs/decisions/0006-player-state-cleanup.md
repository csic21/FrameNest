# 决策：播放状态机串行化与 seek/resume 时序修复

- 状态：accepted
- 日期：2026-07-14
- 任务：playback 状态机全面规整（未对应单独 FN-XX，由工作项 1–8 组成）

## 问题

VLC 播放控制层 `VlcPlayerController` 在 libVLC 事件线程与主线程之间，读写 8 个
transient 状态位（`awaitingFirstFramePause` / `holdForUserPlay` /
`seekPreviewActive` / `seekPreviewTargetMs` / `suppressEndReached` /
`ignoreEndReachedBudget` / `endedWhileHolding` / `openedCurrentMedia`），没有
可见性保证。8 个临近问题的具体表现：

- 暂停态拖动进度条：`TimeChanged` 一进入容差立即 pause，HW 解码器还没把新帧合成
  到 SurfaceView，于是「时钟跳了、画面停在旧帧」。
- `PlayerViewModel` 用 `startPositionMs > 0` 当「是否已触发 resume-seek」标志，但
  清理绑在 `phase == Ready/Paused` 上；用户早 tap play 改 phase 后，后续 Paused 会
  把人拽回 resume 点 → 「播不下被拽回原位」。
- `Paused` 事件分支用多个 flag 重算 phase，stale Paused 在 Ended 态会把 Ended 重写
  为 Paused，破坏 replay-from-0。
- transient flags 在 `prepare` / `closeCurrentMedia` / `reportExternalError` /
  `failOpen` / `release` 各自手抄复位，每处清的集合不完全一致（如 `failOpen` 不清
  `firstFrameReady`），产生错误态残留首帧 overlay。
- Surface 重建（旋转）会再触发 Vout，覆盖 seek-preview 结果。
- 进度 persist 在 `onLeaveOrBackground` 起独立 IO scope、`onCleared` 又起 daemon
  Thread 起超时存，常双写甚至回退 position。
- SMB path B 失败后的 `maybeFallbackSeekableSmbToDirect` 与 `retry` 的 open 无互斥。

## 证据

- `VlcPlayerController.kt`（改前）：`eventListener` lambda 直接在 libVLC 事件线程内
  读写上述 flags；`return@EventListener` 标签共 9 处分散。
- `PlayerScreen.kt:647` 注释已自陈「冻帧 + 时钟在跑」的问题原因。
- 旧 `Paused` 分支：4 条 `when` 分支按声明顺序兜底，与 `holdForUserPlay` 等组合能
  在 Ended 态发生错译。
- 5 处手动 flag 复位集合差异（grep 复位行）已逐一核对；`failOpen` 缺 `firstFrameReady`.
- `onLeaveOrBackground` / `onCleared` 双路径历史日志可佐证双写体感。

## 决定

1. **事件串行化**：`eventListener` 只做 `mainHandler.post { handleEvent(event) }`，
   `handleEvent` 在主线程处理全部事件；无 `@Volatile` 需求，控制器作为单线程状态机。
2. **seek-preview 帧落定才 pause**：新增 `FRAME_SETTLE_MS = 200L`；`TimeChanged`
   命中容差后改 `scheduleSeekPreviewSettle` 延后 pause，等待 HW 出帧；Vout 提前命中。
3. **Paused 单向驱动**：简化为 `Playing → Paused` 单一转换，首帧 Ready 由
   `onFirstVout` 直接设。
4. **集中 `resetTransientFlags(opening: Boolean)`**：5 个収尾路径只调它，删除手抄；
   `play()` 例外（`endedWhileHolding` 需 read-after-reset 例外）加注释。
5. **`ResumeSeekGate`**：抽出可测纯类，`shouldFire/markFired/reset/hasFired`；
   `play()` 主动 latch。`retry()` **不**盲目恢复 `request.startPositionMs`：若 gate
   已 fired，须在关闭媒体前快照当前 position，并优先以它作为重开起点，
   `lastSavedPositionMs` 仅作回退；若未 fired，
   保留原 `startPositionMs`（含 history 派生），避免「播到中段 → 报错 → 重试被拽回
   入口 resume」与「open 失败但 history 起点被 request=0 抹掉」。
6. **进度单点互斥写**：`lastSavedPositionMs` 加 `@Volatile`；`onLeaveOrBackground` 经
   `leaveSaveJob` 取消上一个；`onCleared` 在写前 `lastSavedPositionMs != savePosition`
   守卫，并 `cancel()` leave-save。
7. **Surface 重建与 preview 互斥**：`refreshVideoSurfaces` 入口若 preview active
   先 `finishSeekPreview("surface-refresh")`。
8. **open 互斥**：新增 `openJob`；init / retry / fallback 三处入共享槽，再入先
   `cancel()` 旧的。
9. **短视频完成判定**：绝对“剩余 30 秒”规则只用于至少 60 秒的视频；更短视频按
   90% 比例或真实 Ended 判定，避免只打开首帧就被记为已看完。

复用已有结构与约束：未改 `PlayerController` 接口签名、未改 libVLC 选项、未改产品
交互、未加新依赖；VM 处仍用 `(controller as? VlcPlayerController)?.closeCurrentMedia`
等 cast 访问非接口方法。

## 未选择的方案

- **给每个 flag 加 `@Volatile`**：可缓解可见性，但不消灭「事件线程 vs 主线程」复合
  read-modify-write 竞态；状态机仍不是串行，对应问题只在表面被掩盖。
- **引入 Mutex 锁**：增加复杂度，无收益于「逻辑时序错」，且易引入死锁。
- **抽 `PlayerController` 的伪实现以做 JVM 单测**：`VlcPlayerController` 强依赖
  native libVLC，伪实现会成第一个替代实现（仓库无此先例）；改选纯逻辑 `ResumeSeekGate`
  作为本次唯一可测点，遵循 `PlaybackProgressRulesTest.kt` 的零依赖风格。

## 后果

- 所有 transient flag 读写都限定在主线程；后续若新增 flag 须在 `handleEvent` 内
  或主线程方法内读写。
- 新增常量 `FRAME_SETTLE_MS`（200ms）；如遇更大延迟场景，优先调它而非
  `SEEK_PREVIEW_TIMEOUT_MS`。
- `ResumeSeekGate.reset()` 必须在 retry 重新 open 前 reset；retry 必须先快照 live
  position 再调用 `closeCurrentMedia()`，否则 state 会被重置为 0。
- `play()` / `retry()` 现在必调 `resumeSeekGate.markFired()` / `reset()`。
- transient flag 集中在 `resetTransientFlags` 后，扩展要及时维护该函数。
