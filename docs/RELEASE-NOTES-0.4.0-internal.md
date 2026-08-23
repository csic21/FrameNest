# FrameNest 0.4.0-internal 变更说明

面向内部测试者的说明。

## 安装

真机（arm64）覆盖安装，保留已有服务器、凭证与历史：

```bash
./gradlew assembleRelease
# 使用与现有安装相同的 debug 证书签名后：
adb install -r app-arm64-v8a-release.apk
```

应用 id：`com.framenest`  
版本：`0.4.0-internal`（versionCode 5）

## 相对 0.3.1 你能多做什么

1. 播放页：音轨、倍速、锁屏、方向锁、上一集/下一集、缓冲、拖动预览、滑动快进、横屏全屏  
2. 听译（Beta）：设置中下载本机模型后，无字幕片也可边播边出原文/译文  
3. 服务器页可扫描局域网 SMB；浏览页可切换列表/网格  
4. 新的品牌 Logo 与主题  

## 请勿

- 把密码发到聊天 / issue  
- 提交含真实主机与密码的配置  
- 把听译宣称为通用精准字幕（当前是 Vosk small + ML Kit Beta）  

## 反馈时附上

设置 → 导出诊断日志（已脱敏）+ 机型 / Android 版本 + 操作步骤  

详见 `CHANGELOG.md` 与 `docs/KNOWN-ISSUES.md`。
