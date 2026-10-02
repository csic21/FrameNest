# FrameNest 0.5.1-internal 变更说明

面向内部测试者的说明。

## 安装

真机（arm64）覆盖安装，保留已有服务器、凭证与历史：

```bash
# 下载本发布的 framenest-0.5.1-internal-arm64.apk 后：
adb install -r framenest-0.5.1-internal-arm64.apk
```

应用 id：`com.framenest`  
版本：`0.5.1-internal`（versionCode 9）

## 相对 0.5.0 你能多做什么

1. 进入文件夹时，封面默认三张一起取。竖屏按片子比例放进格子，不再被横向拉满。
2. 拖动进度条时，手指不松开就能看到那个时间的预览图。松手后画面才跳过去。
3. 播放中切去别的应用再回来，可以直接继续播，不必退出播放页重进。

升级后第一次打开已经看过的目录，封面会按新的比例再取一次。

## 请勿

- 把密码发到聊天 / issue
- 提交含真实主机与密码的配置
- 把听译宣称为通用精准字幕（当前是 Vosk small + ML Kit Beta）

## 反馈时附上

设置 → 导出诊断日志（已脱敏）+ 机型 / Android 版本 + 操作步骤

详见 `CHANGELOG.md` 与 `docs/KNOWN-ISSUES.md`。
