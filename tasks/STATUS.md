# 任务状态看板

状态只使用：`未开始`、`进行中`、`待集成`、`已完成`、`阻塞`。

| 任务 | 状态 | Owner | 分支/Worktree | 依赖 | 交接链接 |
|---|---|---|---|---|---|
| FN-00 工程骨架 | 已完成 | agent | → main | 无 | [FN-00](handoffs/FN-00.md) |
| FN-01 播放内核 spike | 已完成 | agent | → main | FN-00 | [FN-01](handoffs/FN-01.md) |
| FN-02 SMB 数据路径 spike | 已完成 | agent | → main | FN-00 | [FN-02](handoffs/FN-02.md) |
| FN-03 自适应 App 外壳 | 已完成 | agent | → main | FN-00 | [FN-03](handoffs/FN-03.md) |
| FN-04 服务器与 SMB 浏览 | 已完成 | agent | → main | FN-02, FN-03 | [FN-04](handoffs/FN-04.md) |
| FN-05 SMB 播放与历史 | 已完成 | agent | → main | FN-01..FN-04 | [FN-05](handoffs/FN-05.md) |
| FN-06 字幕 | 已完成 | agent | → main | FN-04, FN-05 | [FN-06](handoffs/FN-06.md) |
| FN-07 缩略图与首帧 | 已完成 | agent | → main | FN-02, FN-04, FN-05 | [FN-07](handoffs/FN-07.md) |
| FN-08 自适应体验打磨 | 已完成 | agent | → main | FN-04, FN-05 | [FN-08](handoffs/FN-08.md) |
| FN-09 稳定与发布 | 已完成 | agent | → main | FN-04..FN-08 | [FN-09](handoffs/FN-09.md) |
| FN-10 听译 PCM spike | 已完成（仅 spike） | agent | → main | FN-01, FN-05 | [FN-10](handoffs/FN-10.md) |
| FN-11 听译 Room/清理 | 已完成 | agent | → main | FN-10 | [FN-11](handoffs/FN-11.md) |
| FN-12 听译管线+UI | 部分完成（stub） | agent | → main | FN-10, FN-11 | [FN-12](handoffs/FN-12.md) |
| FN-13 听译模型壳 | 部分完成（JSON 占位包） | agent | → main | FN-12 | [FN-13](handoffs/FN-13.md) |
| FN-14 听译真 ASR/MT | 已完成 | agent | `→ main` | FN-10..FN-13 | [FN-14](handoffs/FN-14.md) |

> FN-14：产品路径为 **PCM → Vosk small 离线 ASR → ML Kit 本机 MT**。首次需下载
> 源语言模型；质量受 small 模型与对白清晰度限制。详见 handoff。

**当前发布**：`0.3.1-internal`（versionCode 4）

| 产物 | 路径 | 约大小 |
|---|---|---|
| Debug universal | `app/build/outputs/apk/debug/app-universal-debug.apk` | ~129MB |
| Debug arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | ~74MB |
| Debug x86_64 | `app/build/outputs/apk/debug/app-x86_64-debug.apk` | ~79MB |
| Release | `app/build/outputs/apk/release/` | minify 已启用 |

NAS 验收勾选：`docs/NAS-ACCEPTANCE-CHECKLIST.md`  
后续 backlog：`docs/POST-MVP.md`
