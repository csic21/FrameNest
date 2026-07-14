# Post-MVP backlog

FN-00～FN-09 已封板。**0.3.1-internal** 已落地一批后处理；剩余如下。

## 已完成（0.3.1）

- [x] ABI 分包 + release minify 基线  
- [x] 常见 share 名探测（非完整 MS-SRVS）  
- [x] 缩略图并发 1–2 可配置  
- [x] Spike 隔离到 debug source set  
- [x] allowBackup=false  
- [x] 真实 NAS 验收勾选表  

## 本机听译（规划中）

产品与存储边界见 [`docs/decisions/0005-listen-translate.md`](decisions/0005-listen-translate.md)。

| 任务 | 内容 | 状态 |
|---|---|---|
| FN-10 | 播放中本机取 PCM（MediaCodec 并行音轨）spike | 已完成 |
| FN-11 | Room job/cue + 清理（删服/打开失败/设置；卸载靠私有存储） | 已完成 |
| FN-12 | ASR→MT 管线、播放页开关与语言选择、叠层 | 已完成（stub 引擎） |
| FN-13 | 模型按需下载（仅 app 私有目录）与设置占用展示 | 已完成（asset pack + store API） |

硬约束：全本机；源/目标语言手选；原文+译文入库；**不写 NAS**；**卸载无残留**。

## 仍可选

1. 真 NAS 勾完 `docs/NAS-ACCEPTANCE-CHECKLIST.md`  
2. 完整 MS-SRVS share 枚举（需额外 RPC 依赖）  
3. App Bundle / Play 分发  
4. 播放页音轨 UI、更多字幕编码  
5. CI instrumented 矩阵（phone + tablet）  
6. Room schema export + 正式 migration  
7. 网络安全配置（cleartext 局域网可选）  
8. ~~局域网 SMB 发现（mDNS + 可选 445）~~ → 已落地，见 `docs/decisions/0004-lan-discovery.md`
