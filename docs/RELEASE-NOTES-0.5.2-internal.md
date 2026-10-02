# FrameNest 0.5.2-internal 变更说明

面向内部测试者的说明。

## 安装

真机（arm64）覆盖安装，保留已有服务器、凭证与历史：

```bash
# 下载本发布的 framenest-0.5.2-internal-arm64.apk 后：
adb install -r framenest-0.5.2-internal-arm64.apk
```

应用 id：`com.framenest`  
版本：`0.5.2-internal`（versionCode 10）

## 相对 0.5.1 你能看到什么

1. 桌面图标换成两层圆角画框，中间是一个暖白播放三角。
2. 从文件夹点开片子再拖进度条，第一张预览少做一次连接。
3. 封面和拖动预览少缓冲一些。正在播放的片子没有改。

## 请勿

- 把密码发到聊天 / issue
- 提交含真实主机与密码的配置
- 把听译宣称为通用精准字幕（当前是 Vosk small + ML Kit Beta）

## 反馈时附上

设置 → 导出诊断日志（已脱敏）+ 机型 / Android 版本 + 操作步骤

详见 `CHANGELOG.md` 与 `docs/KNOWN-ISSUES.md`。
