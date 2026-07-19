# 性能与包体基线

## SMB 会话（FN-22）

- 目录浏览：同一服务器、同一连接配置下的连续 `load` 复用一条 SMB 会话。
  配置变更、客户端已断开或 SMB 操作失败后，下次操作重新认证。
- 缩略图：每个 worker 保持一条独立会话，不在并发 worker 间共享 `SmbClient`。
  因此同一服务器的 N 张连续缩略图从最多 N 次连接/认证降为每个活跃 worker
  通常 1 次（默认 worker=1，设置可选 2）。
- 密码不进入会话 key；仅新建连接时从凭证库取出，`connect` 返回后立即清零字符数组。

上述为可重现的连接次数改善，已由 fake client 单测验证连续浏览只连接一次。
真 NAS 的首屏时间、延迟与吞吐仍需设备验收，本文不虚报实测毫秒。

## APK 包体（2026-07-19）

干净构建，arm64-v8a + x86_64，release 已 R8/resource shrink。移除了对所有 `.so` 的
`keepDebugSymbols`；`libjnidispatch.so` 上游产物仍无法由 Android Gradle Plugin 剥离。

| 产物 | 修改前 | 修改后 | 变化 |
|---|---:|---:|---:|
| Debug arm64 | 102.3MB | 93.9MB | -8.4MB（-8.2%） |
| Debug x86_64 | 108.9MB | 100.9MB | -8.0MB（-7.3%） |
| Debug universal | 184.3MB | 168.7MB | -15.6MB（-8.5%） |
| Release arm64 | 79.6MB | 72.0MB | -7.6MB（-9.5%） |
| Release x86_64 | 86.3MB | 79.0MB | -7.3MB（-8.5%） |

Release universal 现为 146.8MB，同时包含两个受支持 ABI。修改前的 79.6MB universal
受 release `abiFilters` 与 split 不一致影响，实际只包含 arm64，因此不做虚假的同口径降幅对比。
内部真机分发应优先使用 arm64 分包；商店分发应使用 App Bundle 按 ABI 下发。
