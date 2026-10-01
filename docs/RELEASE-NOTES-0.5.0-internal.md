# FrameNest 0.5.0-internal 变更说明

面向内部测试者的说明。

## 安装

真机（arm64）覆盖安装，保留已有服务器、凭证与历史：

```bash
# 下载本发布的 framenest-0.5.0-internal-arm64.apk 后：
adb install -r framenest-0.5.0-internal-arm64.apk
```

应用 id：`com.framenest`  
版本：`0.5.0-internal`（versionCode 8）

## 相对 0.4.2 你能多做什么

1. 拖动进度条或左右滑动时，先看到目标时刻的预览图，松手后画面才跳过去。
2. 进入文件夹时，视频是一块 16:9 封面，旁边有文件大小；封面出来后右下角有时长。片子自带封面时先用那张图；没有或是纯色时，较长的片子会改到片中前段的画面。升级后第一次打开已看过的目录会重新取这些封面。

## 请勿

- 把密码发到聊天 / issue
- 提交含真实主机与密码的配置
- 把听译宣称为通用精准字幕（当前是 Vosk small + ML Kit Beta）

## 反馈时附上

设置 → 导出诊断日志（已脱敏）+ 机型 / Android 版本 + 操作步骤

详见 `CHANGELOG.md` 与 `docs/KNOWN-ISSUES.md`。
