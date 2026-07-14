# 已知问题（0.3.0-internal）

| ID | 说明 | 影响 | 缓解 |
|---|---|---|---|
| KI-01 | 无真实 NAS 时无法完成完整发布验收场景 | 联调 | 使用家庭 NAS + `smb.local.properties`（勿提交） |
| KI-02 | SMB 共享列表依赖默认共享名/探测，非完整 SRVS 枚举 | 多 share 浏览 | 在服务器设置中填写默认共享 |
| KI-03 | 部分 HEVC/特殊封装缩略图失败 | 列表封面 | 占位图标；播放仍走 libVLC |
| KI-04 | 字幕字号在部分机型上为 best-effort | 观感 | 可手动切换轨/延迟 |
| KI-05 | 从「最近播放」进入时底栏仍可能高亮「服务器」 | 导航指示 | 返回后可切换 tab |
| KI-06 | Debug APK 含 libVLC 多架构体积较大 | 分发 | abiFilters arm64-v8a + x86_64 |

安全提醒：反馈问题时只分享**脱敏诊断日志**，不要粘贴密码或完整 `smb.local.properties`。
