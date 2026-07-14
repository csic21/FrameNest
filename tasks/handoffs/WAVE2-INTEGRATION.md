## Wave 2 集成交接

- 状态：完成（真实 NAS 端到端需用户本机验证）
- 分支与提交：`main` @ `fcb0b99`（及后续 docs）
- 合并顺序：FN-04 → FN-05（解决 Room / 导航 / 播放请求冲突）
- 改动要点：
  - 统一 `com.framenest.data.server.AppDatabase`：`servers` + `playback_history`
  - `AppContainer` 提供 `serverRepository` / `browseRepository` / `historyRepository`
  - `PlayerRoute`：按 `serverId+share+path` 解密凭证 → `SeekableSmb` → `PlayerScreen`
  - Recent 读 Room 历史；无记录时空状态
- 验收：`./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL
- 数据路径：决策 0002 路径 B（SMBJ `openRandomAccess` + proxy FD + libVLC）
- 未解决问题：无真实 NAS 时无法完整验证浏览/播放/续播
- 后续：FN-06 / FN-07 / FN-08 可并行
