# 架构边界

## 原则

- 第一版只使用一个 Android `app` module；只有构建时间或复用需求被证实时才拆
  Gradle module。
- 按功能包隔离代码，UI 只消费状态，不直接操作 libVLC 或 SMB 客户端。
- 不提前设计 WebDAV/FTP 等未进入 MVP 的通用协议层。
- 播放器与 SMB 的最终接法由技术验证决定，不在验证前实现 localhost proxy。

## 建议包结构

```text
com.framenest
├── app/                 # Application、依赖装配、导航
├── core/model/          # 少量跨功能数据类型
├── player/              # 播放内核适配和 PlayerState
├── smb/                 # SMB 会话、目录读取、随机读取
├── data/server/         # 服务器元数据与凭证引用
├── data/history/        # 播放历史
├── data/thumbnail/      # 缩略图索引与磁盘缓存
├── feature/servers/     # 服务器管理 UI
├── feature/browser/     # 共享和目录浏览 UI
├── feature/player/      # 播放页与控制层
├── feature/subtitle/    # 字幕扫描、匹配、设置
└── feature/settings/    # 缓存和播放设置
```

## 运行时数据流

```text
Compose UI
  → feature state holder
    → SMB / player / repository implementation
      → NAS、libVLC、Room、磁盘缓存
```

## 需要先验证的两个决策

### D1：SMB 视频如何送入播放器

`FN-01` 与 `FN-02` 必须用同一 NAS 样本比较：

1. libVLC 直接打开 `smb://`，凭证通过受支持选项传递；
2. SMB 客户端提供 seekable/random-access 数据给播放器；
3. 仅当前两项不可接受时，才实现 app 内 localhost HTTP Range proxy。

评价项：首次出帧、seek、凭证泄露风险、断网恢复、内存、实现复杂度。

### D2：缩略图如何远程取帧

优先复用 D1 已验证的数据路径。禁止为列表中的每个条目常驻播放器。生成任务默认
单并发，测量后才提高并发。

## 持久化和安全

- Room 只存服务器名称、host、port、username、domain 和凭证别名。
- 密码通过 Android Keystore 支持的加密存储保存，不存明文，不写进 URL。
- 缩略图 key 至少包含 server/share/path/size/modifiedTime 的稳定摘要。
- 播放进度以 server/share/path 唯一定位；接近片尾时标为已看完而非继续续播。

## 状态边界

功能层对 UI 暴露不可变状态和用户事件。不要为了“以后更换内核”建立庞大接口；
`FN-01` 只抽象 UI 当前实际需要的 prepare/play/pause/seek/release、轨道和错误状态。

## 决策记录

技术验证结束后复制 `docs/decisions/TEMPLATE.md`，记录结论、证据、被拒方案和后续
影响。未写决策记录的 spike 不算完成。

