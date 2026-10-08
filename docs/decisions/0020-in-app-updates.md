# 0020：固定 GitHub Release 清单与用户确认安装

- 状态：接受
- 任务：FN-58
- 日期：2026-10-08

## 决策

使用固定地址 `https://github.com/csic21/FrameNest/releases/latest/download/update.json`。
不查询匿名 GitHub API，不增加服务端或账号。发布方将更新清单与同版本 APK 一起上传，
并将可更新发行版设为稳定 Release（即使版本名含 internal）。

版本用整数 versionCode 比较，versionName 仅展示。清单 schemaVersion=1，含 applicationId、
versionName、versionCode、tag、notes（纯文本）、minSdk 和 assets。每个 asset 包含 abi、size、
sha256（小写十六进制）及固定版本 GitHub Release APK URL。优先设备支持的精确 ABI，
再用仅含 arm64-v8a/x86_64 的 universal；不向 32 位设备提供不可安装的包。

自动检查每 24 小时最多一次尝试；失败同样限频。设置里的手动检查可立即重试。
检查可以在播放页完成，任何更新对话框仅在应用 RESUMED 且非播放路由时显示。
下载及安装都由用户发起，进入来源许可设置后不会自动开始安装。

## 安全与范围

- 清单最大 128 KiB；单个 APK 最大 512 MiB；只接受 HTTPS、准确仓库固定版本链接。
- 手动处理最多四次跳转，只接受 github.com 的固定仓库 releases 路径及 GitHub 的
  release-assets.githubusercontent.com / objects.githubusercontent.com 精确 CDN 主机。
  每一步保持 HTTPS，不允许凭证、异常端口、片段或路径穿越。
- 拒绝大小不一致、SHA-256 不一致、实际 APK 包名/版本/minSdk 不一致以及当前签名集合不同。
  相同签名校验有意比 Android 的证书轮换机制更保守，未来轮换需要另行设计迁移。
- 下载后校验一次，用户确认安装后再次校验；后台、导航、取消或后到的响应不能触发安装。
  系统安装器仍执行 Android 的最终验证和用户确认。
- 使用应用私有 cache/app-updates/，通过现有 FileProvider 的窄路径只读授权。
  网络下载串行、无断点续传；取消在下一次读取或 15 秒读超时结束，UI 立即撤销操作。
  不支持进程死亡后继续下载；下次下载清除这个专用目录的旧包，总量有界。
- 只保存上次自动检查时间和用户略过的版本号，不保存网络响应、签名私钥或 NAS 信息。
- 不会静默卸载、不绕过签名，也不会以清空数据来处理不兼容版本。

## 已拒方案

- 匿名 GitHub releases API：容易共享限流，且无需其额外元数据。
- 静默安装或下载完成后自动打开安装器：会打断播放，也越过用户意图。
- 信任清单中的包名和摘要而不读取 APK：不能证明可覆盖更新当前安装。
- 后台服务、WorkManager、自建后端：当前用户主动下载范围不需要额外基础设施。

## 验证边界

单测覆盖策略、状态、取消及晚到结果；仪器测试覆盖 JSON 解析、摘要/非 APK 拒绝和对话框。
真实设备上的系统来源许可页面、覆盖安装和历史/凭证保留必须另行验证；新发布的签名与
历史 0.5.2 包一致，需要 FN-59 的发布签名材料及实际签名检查支持。

Android 平台参考：
[来源安装许可 API](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())；
[SigningInfo 当前签名与轮换历史](https://developer.android.com/reference/android/content/pm/SigningInfo)。
