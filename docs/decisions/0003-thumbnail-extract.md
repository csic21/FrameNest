# 决策：列表缩略图取帧方式

- 状态：accepted（候选时间与列表呈现由 [0013](0013-folder-video-covers.md) 补充。列表封面的代理 FD 取帧由 [0015](0015-thumbnail-prefix.md) 替代，主路径再由 [0016](0016-vlc-folder-covers.md) 改为 libVLC 内存回调。默认并发改为 3，仍不为每一行建播放器）
- 日期：2026-07-14
- 任务：FN-07

## 问题

列表封面需要从 SMB 远程视频取一帧并缓存为位图，且：

1. 复用 0002 的 seekable SMB 数据路径
2. 不为每个列表项创建播放器实例
3. 默认单并发生成
4. 与播放页 `firstFrameReady`（真实 vout）严格分离

## 证据

| 方案 | 复杂度 | 与 0002 复用 | 每行实例 | 备注 |
|---|---|---|---|---|
| **MediaMetadataRetriever + ProxyFileDescriptor** | 低 | 直接复用 `SmbRandomAccess` → `SmbSeekableMedia` | 否 | 系统 API，适合单帧 JPEG |
| 单共享 libVLC 取帧 worker | 中高 | 可复用 seekable AFD | 否（共享一个） | 与播放内核争用/生命周期更重 |
| 每行 libVLC | 高 | 可 | **是（禁止）** | 滚动卡顿与内存不可接受 |

本任务在无真机 NAS 环境下以代码路径 + 单测验证策略；帧提取在设备上依赖 `MediaMetadataRetriever` 对常见 MP4/部分 MKV 的支持。

## 决定

1. **列表取帧**：`MediaMetadataRetriever` + `StorageManager.openProxyFileDescriptor`（既有 `SmbSeekableMedia`）读 `SmbRandomAccess`。
2. **并发**：`ThumbnailRepository` 单 consumer channel，**默认单并发**。
3. **缓存**：`cacheDir/thumbnails/<digest>.jpg`，key = SHA-256 摘要（serverId/share/path/size/modifiedTime）；`clearCache()` 可供设置页挂钩。
4. **候选时间**：优先 10s；短视频（&lt;15s）取约 20% 时长；失败/黑帧再试有限候选（最多 4）；生成失败指数退避，最多 3 次后永久跳过（key 变化或清缓存后可再试）。
5. **播放首帧**：仍仅由 libVLC `Vout` → `PlayerState.firstFrameReady` 门控；列表位图不得冒充可播放。

## 未选择的方案

| 方案 | 原因 |
|---|---|
| 每行 libVLC | 产品与架构明确禁止 |
| 默认共享 libVLC worker | 实现更重；MMR 足够覆盖 MVP 列表封面 |
| localhost Range proxy | 0002 仅作最后手段 |
| 用列表缩略图当播放页首帧 | 违反产品「视频预渲染」定义 |

## 后果

- FN-09 可调用 `ThumbnailRepository.clearCache()` / `approximateCacheSizeBytes()` 接设置 UI。
- 部分封装（冷门 MKV/HEVC）若 MMR 取帧失败，会走退避后占位图标，不影响浏览与播放。
- 若后续实测 MMR 在目标 NAS 样本上失败率高，可在本决策上 supersede 为单共享 libVLC 取帧 worker，仍保持单并发与同一缓存 key。
