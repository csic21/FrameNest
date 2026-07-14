# Post-MVP backlog（可选）

FN-00～FN-09 已封板。以下为有价值但未承诺的后续项：

## 体验

1. 真实 NAS 全量验收（PRODUCT 场景 1–7 在真机 + 真 NAS 勾选）
2. 完整 SMB share 枚举（MS-SRVS）若默认共享体验不够
3. 缩略图并发可配置（默认仍单并发）
4. 播放页音轨选择 UI、外挂字幕编码更多回退
5. 平板「最近播放 → 播放」专用 list-detail 历史详情

## 工程

1. APK 体积：按 ABI 分包 / App Bundle；release minify
2. 删除或隔离 spike 代码路径（PlayerSpike / SmbSpike）到 debug source set
3. CI 上 instrumented 测试矩阵（phone + tablet AVD）
4. Room schema export + 正式 migration 策略

## 安全 / 合规

1. 备份排除凭证存储确认（allowBackup 策略）
2. 网络安全配置（cleartext 仅局域网可选）
