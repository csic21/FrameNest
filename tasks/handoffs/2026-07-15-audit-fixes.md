# 2026-07-15 代码与使用逻辑审计修复交接

## FN-04 交接

- 状态：代码完成；真实 NAS 验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`data/server`、`data/discovery`、`data/history`、`feature/servers`、`smb`。
- 验收结果：服务器凭证写入/删除具有失败补偿；端口严格校验；连接测试结果不会覆盖新表单；删除服务器同步清理历史与听译；mDNS 启动失败可结束 UI 状态且 session executor 会释放；SMB 日志对消息、cause、suppressed 全链脱敏。
- 执行过的命令及结果：定向单测、`compileDebugKotlin`、`lintDebug` 及最终全量检查均成功。
- 决策记录：更新 `docs/decisions/0002-smb-data-path.md`，明确 NAS 信息只在运行时输入，不注入 APK。
- 未解决问题：无已知代码阻塞；真实 NAS 发现、连接、删除补偿尚未实机验证。
- 集成 Agent 需要做的共享文件改动：已在 `ServersScreen` 注入历史仓库，无后续接线。
- 后续任务可依赖的接口/行为：`PlaybackHistoryRepository.purgeServer(serverId)`、`DiscoveryPhase.MDNS_FAILED`。

## FN-05 交接

- 状态：代码完成；真机播放验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`data/history/PlaybackProgressRules.kt`、`feature/player/PlayerAudioFocus.kt`、`PlayerViewModel.kt`、`ResumeSeekGate.kt` 及测试。
- 验收结果：重试在关闭媒体前保存实时位置并优先续播；短视频不再因“剩余不足 30 秒”在 0 秒即标记完成；音频焦点延迟时等待，拒绝时不启动播放。
- 执行过的命令及结果：播放器/历史定向单测及最终全量检查成功。
- 决策记录：更新 `docs/decisions/0006-player-state-cleanup.md`。
- 未解决问题：未在来电/其他媒体抢焦点及真实 SMB 断线环境中实测。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：`retryResumePosition`、`AudioFocusRequestResult`。

## FN-06 交接

- 状态：代码完成；真机字幕切换验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`feature/subtitle/SubtitleMatcher.kt`、`feature/player/SubtitleSelectionGate.kt`、`PlayerViewModel.kt` 及测试。
- 验收结果：异步字幕扫描/加载不会覆盖用户稍后的关闭或手动选择；内嵌字幕语言按完整 token/别名匹配，避免 `fr` 等字符串误命中 `en`。
- 执行过的命令及结果：字幕定向单测及最终全量检查成功。
- 决策记录：无新的架构决策。
- 未解决问题：未在含多条内嵌/外挂字幕的真实视频上实测。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：`SubtitleSelectionGate` generation token。

## FN-07 交接

- 状态：代码完成；手机/平板长列表验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`data/thumbnail`、`feature/browser/BrowseScreen.kt` 及测试。
- 验收结果：内存缩略图为 16 MiB/64 项 LRU；离屏 row 释放 UI 状态；清缓存会取消 worker/retry、清空队列并用 generation 阻止旧任务回写；失败项在仍可见时按退避自动重试。
- 执行过的命令及结果：缩略图定向单测、编译及最终全量检查成功。
- 决策记录：无新的架构决策。
- 未解决问题：未在真实大目录滚动与清缓存竞态下做设备压力测试。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：`BoundedLruCache`、`ThumbnailCacheGeneration`、`retain/release` 生命周期。

## FN-08 交接

- 状态：代码完成；手机/平板视觉验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`feature/player/PlayerScreen.kt`。
- 验收结果：紧凑宽度控制区拆为两行，播放按钮保持首要位置；竖屏底部控制区避让系统导航栏。
- 执行过的命令及结果：debug/release Compose 编译、lint 与 APK 构建成功。
- 决策记录：无。
- 未解决问题：当前无连接设备，未做手机与平板截图验收。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：无新增接口。

## FN-09 交接

- 状态：代码完成；安装验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`app/build.gradle.kts`、设置缓存统计、README、发布/验收文档。
- 验收结果：本地 NAS 信息不再编译进 BuildConfig/APK；设置页普通缓存与听译缓存不再重复计数；APK 文档路径与 ABI split 实际输出一致。
- 执行过的命令及结果：`testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleRelease`，BUILD SUCCESSFUL；`rtk proxy git diff --check` 通过。
- 决策记录：更新 `docs/decisions/0002-smb-data-path.md`。
- 未解决问题：lint 仅保留既有版本升级/API 风格 warning；依项目约束未做依赖升级。无设备，未安装 APK。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：debug NAS spike 使用运行时输入。

## FN-14 交接

- 状态：代码完成；Room 仪器回归与模型删除真机验收待设备。
- 分支与提交：共享工作树，未提交。
- 改动路径：`data/listen_translate`、`feature/listen_translate`、`feature/settings`、设置页及测试。
- 验收结果：Room job upsert 不再以 REPLACE 级联删除 cue；MT 失败仍保存并显示 ASR 文本；失败清理不会抹掉错误；清理听译模型包含 ML Kit 下载包；缓存大小展示拆分正确。
- 执行过的命令及结果：听译定向 JVM 单测、`compileDebugAndroidTestKotlin` 及最终全量检查成功。
- 决策记录：更新 `docs/decisions/0005-listen-translate.md`。
- 未解决问题：`ListenTranslateRoomUpsertTest` 已编译但因无设备未执行；模型删除需真机下载模型后验收。
- 集成 Agent 需要做的共享文件改动：无。
- 后续任务可依赖的接口/行为：DAO 使用 Room `@Upsert`；`MlKitTranslationModelCleaner.deleteAll()`。

## 最终验证

- `rtk ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease`：BUILD SUCCESSFUL。
- `rtk proxy git diff --check`：通过。
- `rtk adb devices -l`：无连接设备，因此未运行 `connectedDebugAndroidTest`，也未完成手机/平板/真实 NAS 验收。
