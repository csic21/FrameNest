## Wave 3 集成交接

- 状态：完成
- 分支：`main`（`versionName` `0.3.0-wave3`）
- 合并顺序：FN-06 → FN-07 → FN-08
- 交付：
  - **FN-06**：内嵌轨 + 同目录外挂 SRT/ASS/SSA/VTT；匹配排序；`addSlave`；延迟/字号
  - **FN-07**：单并发缩略图（MediaMetadataRetriever + SMB seekable）；磁盘缓存；`PlayerState.canPlay` 依赖 firstFrameReady
  - **FN-08**：Fullscreen 播放导航；Dimens/触控 48dp；长文件名 ellipsis；旋转保导航
- 验收：`./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL
- 决策：`docs/decisions/0003-thumbnail-extract.md`
- 后续：**FN-09**
