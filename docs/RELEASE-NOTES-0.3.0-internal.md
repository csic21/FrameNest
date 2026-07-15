# FrameNest 0.3.0-internal 变更说明

面向内部测试者的说明。

## 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

应用 id：`com.framenest`  
版本：`0.3.0-internal`（3）

## 你能做什么

1. 添加 NAS（默认端口 445），测试连接后浏览共享/目录  
2. 点视频播放：先看首帧，再点播放；可 seek、看最近播放续播  
3. 字幕：CC 面板切换内嵌/外挂，调延迟与字号  
4. 设置：清理缓存、默认字幕语言、导出脱敏诊断日志  

## 请勿

- 把密码发到聊天 / issue  
- 提交含真实主机与密码的配置  

## 反馈时附上

设置 → 导出诊断日志（已脱敏）+ 机型 / Android 版本 + 操作步骤  

详见 `docs/KNOWN-ISSUES.md` 与 `docs/RELEASE-ACCEPTANCE.md`。
