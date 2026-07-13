# 决策：SMB 客户端与播放/取帧数据路径

- 状态：accepted（客户端与应用层数据路径）；设备实测指标 partial
- 日期：2026-07-14
- 任务：FN-02

## 问题

FrameNest MVP 需要在家庭局域网内稳定访问 SMB2/3 NAS，并让下列能力共享同一条远程读路径：

1. 目录浏览（FN-04）
2. 视频播放与 seek（FN-05，对接 FN-01 播放内核）
3. 列表缩略图取帧与播放前首帧（FN-07）

必须现在选定：

- SMB2/3 客户端库
- 凭证如何传递（禁止写入 URL / 日志 / Room 明文）
- 播放器与缩略图使用的数据路径（direct `smb://` / seekable datasource / localhost Range proxy）

## 证据

### 客户端库

| 库 | 协议 | 结论 |
|---|---|---|
| **SMBJ 0.14.0**（`com.hierynomus:smbj`） | SMB2/3 | **选用**。API 覆盖 connect/authenticate、DiskShare list、metadata、`File.read(buf, offset, …)` 随机读、session/connection close。Android 上通过标准 TCP 套接字工作。 |
| jcifs-codelibs / jcifs-ng | SMB1/2/3（历史包袱重） | **未切换**。SMBJ 未出现编译或 API 级阻塞；不引入第二套客户端。仅当后续 NAS 实测发现 SMBJ 致命兼容问题再对比。 |

### 实现与自动化验证（本分支）

- 包：`com.framenest.smb` + 隔离 harness `com.framenest.smb.spike.SmbSpikeActivity`
- 单元测试覆盖：错误分类（auth / network / not-found / permission / disconnected）、路径排序、凭证脱敏、`SmbClient` fake 随机读契约、benchmark 采样点
- `./gradlew assembleDebug testDebugUnitTest`：**通过**
- 真实 NAS：本环境**未配置** `smb.local.properties`，故 first-byte / 吞吐 / seek 墙钟数据未在设备上采集。Harness 已就绪，步骤见下方。

### 能力对照（API 级，SMBJ）

| 能力 | 结果 |
|---|---|
| 认证（NTLM username/password/domain） | 支持；`AuthenticationContext` 使用 `CharArray` 密码，不进 URL |
| 列 share | **无一等 API**（需 MS-SRVS RPC）。Spike 对用户提供的 candidate share 做 tree-connect 探测 |
| 列目录 + metadata | `DiskShare.list` / `fileExists` / `FileStandardInformation` |
| 随机读 | `File.read(byte[], fileOffset, offset, length)` → `SmbRandomAccess` |
| 断线 | `disconnect()` / `close()` 释放 session+connection；错误映射 `STATUS_CONNECTION_*` → `SmbError.Disconnected` |
| 错误分类 | `SmbErrorMapper`：`Auth` / `Network` / `NotFound` / `Permission` / `Disconnected` / `Unknown`；日志经 `redactSecrets` |

### 播放数据路径比较（指导 FN-01 / FN-05 / FN-07）

| 路径 | 优点 | 风险 | 判定 |
|---|---|---|---|
| **A. Direct `smb://` 进播放器** | 实现短；若 libVLC 内置 SMB 成熟则 seek 由内核处理 | 凭证必须用 **options 传递**，绝不能 `smb://user:pass@host/...`；与缩略图路径分裂；断网恢复与错误文案难与 App 统一；不同 libVLC 构建 SMB 支持不一致 | 仅作 **可选加速路径**：当 FN-01 证实 libVLC 在目标设备上 SMB 稳定且 options 可安全传凭证时，播放可选用；**不得**成为缩略图唯一路径 |
| **B. Seekable/random-access datasource（SMBJ）** | App 统一会话与错误分类；列表缩略图与播放可复用 `SmbRandomAccess`；凭证永不进 URL；便于限流/单并发取帧 | 需为播放器提供可 seek 的数据源适配（MediaDataSource / 自定义 input / 管道）；实现量大于 A | **默认推荐**，作为 FN-04/05/07 的主数据路径 |
| **C. 本机 HTTP Range proxy** | 任何只认 HTTP 的解码器都能用 | 多一跳延迟与内存；端口/生命周期复杂；仍需 SMB 客户端喂数据；安全面变大 | **仅当 A 与 B 在 FN-01+FN-05 联调均不可接受时** 再做最小实现 |

### 待补测指标（真实 NAS + 同一 FN-01 样本）

在设备上打开 spike，对 ≥100MB 视频记录：

| 指标 | 如何测 | 目标参考（家庭千兆 Wi-Fi） |
|---|---|---|
| firstByteMs | `SmbBenchmark` / Random read head | 通常 < 300ms（同网段） |
| sequential Mbps | 顺序读 8MB | 应接近 Wi-Fi 实际吞吐（数十 Mbps 级） |
| randomSeekAvgMs | 8 次 64KB 分散读 | 应稳定可 seek；异常尖峰记入 handoff |
| 错误恢复 | Disconnect 后重连；错误密码 | UI 显示 `Auth` vs `Network`，无凭证泄露 |

## 决定

1. **SMB 客户端：SMBJ 0.14.0**  
   应用代码只依赖 `com.framenest.smb.SmbClient` 等包装类型，不向 UI 泄露 SMBJ 类型。

2. **主数据路径：`SmbPlaybackDataPath.SEEKABLE_SMB_DATASOURCE`**  
   - FN-04：`listDirectory` / `metadata` / 后续 share 探测  
   - FN-05：通过 `openRandomAccess`（或同等会话读）向播放器提供可 seek 读；**禁止**把密码拼进 `smb://`  
   - FN-07：复用同一路径做有限区间读 + 单并发取帧；缓存 key 仍按架构要求含 server/share/path/size/modifiedTime  

3. **Direct `smb://`（路径 A）**  
   留给 FN-01 验证 libVLC 是否可在 **options 传凭证、URL 无密码** 的前提下作为播放捷径。即使 A 可用，缩略图仍走 B，避免列表内隐式依赖 libVLC SMB。

4. **Localhost Range proxy（路径 C）**  
   默认不实现。仅在 A/B 均失败时由后续任务最小引入。

5. **Share 枚举**  
   MVP 允许用户输入 share 名（服务器管理表单）。可选：对已知候选名做 tree-connect 探测。完整 `NetShareEnum`（MS-SRVS）非阻塞项，需要时可后补 RPC，不阻塞 FN-04。

6. **安全**  
   - 密码：`CharArray` / Keystore（FN-04），从不写 URL、日志、fixtures、截图、提交  
   - 日志：仅 `SmbCredentials.safeSummary()` 与 `SmbErrorMapper.redactSecrets`  
   - 本地 NAS 配置：`smb.local.properties`（gitignore）→ `BuildConfig` 预填 spike  

## 未选择的方案

- **jcifs-codelibs 作为主客户端**：SMBJ 已满足 API 需求；避免双栈与 SMB1 历史问题。  
- **默认 localhost HTTP proxy**：增加延迟与复杂度，当前无证据表明 B 不可行。  
- **URL 内嵌用户名密码**：明确违反产品与安全约束。  
- **为“将来换协议”做通用 Protocol Provider**：超出 MVP，架构禁止。

## 后果

### FN-04（服务器与浏览）

- 依赖 `SmbClient`：`connect` / `listDirectory` / `metadata` / `listShares(known)` / `disconnect`  
- 错误 UI 绑定 `SmbError` 密封类型  
- 排序：`SmbPathUtils.sortEntries`（目录优先 + 名称不区分大小写）  
- Share 字段用户可编辑；不要假设能枚举全部 share  

### FN-05（播放）

- 正式播放接线以 **B** 为准；若 FN-01 证明 A 更优，可对播放单独启用 A，但须同一错误与凭证策略  
- `SmbRandomAccess`（或会话级读）是播放 seek 的数据契约  
- 断网 → `SmbError.Network` / `Disconnected`，支持重试（重新 `connect`）  

### FN-07（缩略图 / 首帧）

- 远程读复用 B；默认单并发  
- 不要为每个列表项常驻播放器实例  
- 读区间由取帧点决定，经 `readAt` / `readFullyAt`  

### 集成 / 共享文件

- 已加依赖：`smbj`、coroutines（version catalog + `app/build.gradle.kts`）  
- 已加权限：`INTERNET`、`ACCESS_NETWORK_STATE`  
- Spike Activity 已注册；**未**改 FN-03 导航壳。可选：主页入口跳转 `SmbSpikeActivity`  

### 本地 NAS 实测步骤（补齐 partial 验收）

```bash
cp smb.local.properties.example smb.local.properties
# 编辑 host/user/password/share/testFile — 勿提交该文件
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.framenest/.smb.spike.SmbSpikeActivity
# Connect → List dir → Random read → Run benchmark → Disconnect
# 将脱敏后的 benchmark 行贴回本决策「证据」或 FN-05 handoff
```

### 公开接口（后续可依赖）

```text
com.framenest.smb.SmbClient
com.framenest.smb.SmbjClient
com.framenest.smb.SmbCredentials
com.framenest.smb.SmbEntry / SmbFileMetadata
com.framenest.smb.SmbRandomAccess
com.framenest.smb.SmbError / SmbException / SmbErrorMapper
com.framenest.smb.SmbPathUtils
com.framenest.smb.SmbBenchmark
com.framenest.smb.SmbPlaybackDataPath
```
