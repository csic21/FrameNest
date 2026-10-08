# 应用内更新

## 用户路径

- 应用回到前台时，最多每 24 小时自动检查一次。失败也限频。
- 播放时不展示更新窗口；离开播放页后再显示可用版本。
- 设置 → 检查更新可手动检查，或重新打开已找到/下载的版本。
- 查看版本说明 → 下载更新 → 查看进度。关闭或取消下载会撤销该操作，可重新下载。
- 下载完成先校验。首次安装可能需要点击“允许安装来源”，在 Android 设置里授予权限。
  返回后必须再次点击“安装更新”并确认“打开安装器”，最终安装仍由 Android 确认。
- 当前应用与新版签名不同会明确拒绝；不要用卸载当前应用来绕过，以免丢失本机数据。

下载是当前应用进程内的用户操作，不提供后台服务/进程死亡恢复或断点续传。
系统可能在后台终止进程；重新打开后可再次检查和下载。只缓存一个安装包，
不会删除播放历史、NAS 配置或凭证。

## 发布契约

清单地址固定为：
`https://github.com/csic21/FrameNest/releases/latest/download/update.json`

结构：

```json
{
  "schemaVersion": 1,
  "applicationId": "com.framenest",
  "versionName": "0.6.0-internal",
  "versionCode": 11,
  "tag": "v0.6.0-internal",
  "notes": "修复说明与新功能，纯文本",
  "minSdk": 26,
  "assets": [
    {
      "abi": "arm64-v8a",
      "size": 123456,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "url": "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-0.6.0-internal-arm64-v8a.apk"
    }
  ]
}
```

上例 size 和 sha256 仅示意；发布时必须由真实、签名后的 APK 生成。
每种 ABI 最多一项，只接受 arm64-v8a、x86_64 和 universal。
APK 的实际包名、版本码、minSdk 和安装中应用的当前签名证书集合必须匹配。
证书轮换暂不支持；兼容性校验有意保守。详细安全边界见决策 0020。

## 共享文件集成

FN-57 已完成以下接线。MainActivity 显式传入 Application 单例；测试可注入控制器，默认隔离网络检查。

1. 在 Application 中持有唯一实例：`val updateController by lazy { UpdateController(this) }`。
2. 在 `FrameNestApp` 根 composable 中调用
   `UpdateHost(updateController, isPlaybackRoute = isPlayerRoute(currentRoute))`。
   必须使用当前真实路由，不要用当前选中标签代替；不要放进单独设置目的地。
3. 设置页面放 `UpdateSettingsEntry(updateController)`，使用同一个 Application 实例。
4. Manifest 添加 `android.permission.REQUEST_INSTALL_PACKAGES`。
5. 现有 FileProvider paths 添加窄路径
   `<cache-path name="app_updates" path="app-updates/" />`；不要共享整个 cache 或 files。
6. 不需要新增 Activity、Service、Receiver、依赖或通知权限。

## 验证清单

- 单测：UpdatePolicyTest、UpdateControllerTest。
- Android：UpdateManifestParserTest、UpdateDialogTest（手机和平板）。
- 集成时检查播放路由与前后台门禁：自动响应晚到、下载中切入播放、安装校验期间切换路由、
  拒绝来源许可、从设置返回、连续点击、关闭/重试，均不得自行进入安装器。
- 真机覆盖安装须使用同一持续签名证书、递增 versionCode，并验证播放历史和 NAS 凭证保留。
- 单测/对话框测试不代表系统安装器已测，也不能替代真实签名发布的 APK 检查。
