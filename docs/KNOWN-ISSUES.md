# 已知问题（0.3.0-internal + post-MVP polish）

| ID | 说明 | 影响 | 缓解 / 状态 |
|---|---|---|---|
| KI-01 | 无真实 NAS 时无法完成完整发布验收场景 | 联调 | 使用家庭 NAS（勿提交凭证） |
| KI-02 | 无 MS-SRVS 完整枚举；改为用户默认共享 + 常见名探测 | 冷门 share 名 | 在服务器设置填写默认共享；见 `SmbShareCandidates` |
| KI-03 | 部分 HEVC/特殊封装缩略图失败 | 列表封面 | 占位图标；播放仍走 libVLC |
| KI-04 | 字幕字号在部分机型上为 best-effort | 观感 | 可手动切换轨/延迟 |
| KI-05 | ~~从「最近播放」进入时底栏高亮「服务器」~~ | — | **已修复**：播放页非顶栏 tab，记住离开前 tab |
| KI-06 | libVLC 体积仍大 | 分发 | ABI 分包 + universal；release minify；真机用 arm64 APK |

安全提醒：反馈问题时只分享**脱敏诊断日志**，不要粘贴密码或完整 `smb.local.properties`。
