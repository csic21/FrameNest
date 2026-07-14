# Post-MVP backlog

FN-00～FN-09 已封板。**0.3.1-internal** 已落地一批后处理；剩余如下。

## 已完成（0.3.1）

- [x] ABI 分包 + release minify 基线  
- [x] 常见 share 名探测（非完整 MS-SRVS）  
- [x] 缩略图并发 1–2 可配置  
- [x] Spike 隔离到 debug source set  
- [x] allowBackup=false  
- [x] 真实 NAS 验收勾选表  

## 本机听译

产品与存储边界见 [`docs/decisions/0005-listen-translate.md`](decisions/0005-listen-translate.md)。

**产品状态：未可用（演示壳）。** 播放页可开关听译，但出字是时间窗 stub，不是真识别。

| 任务 | 内容 | 状态 |
|---|---|---|
| FN-10 | 播放中本机取 PCM（MediaCodec）— **debug spike only** | 基建完成；**未接产品播放器** |
| FN-11 | Room job/cue + 清理 | 基建完成 |
| FN-12 | 管线/UI/叠层 | **部分完成**：UI 通，引擎为 stub |
| FN-13 | 模型安装目录与设置 | **部分完成**：JSON 占位包，非神经模型 |
| FN-14 | 真听译：PCM→本机 ASR→本机 MT 进产品路径 | **未开始（产品验收以此为准）** |

硬约束（仍有效）：全本机；源/目标语言手选；原文+译文入库；**不写 NAS**；**卸载无残留**。

## 仍可选

1. 真 NAS 勾完 `docs/NAS-ACCEPTANCE-CHECKLIST.md`  
2. 完整 MS-SRVS share 枚举（需额外 RPC 依赖）  
3. App Bundle / Play 分发  
4. 播放页音轨 UI、更多字幕编码  
5. CI instrumented 矩阵（phone + tablet）  
6. Room schema export + 正式 migration  
7. 网络安全配置（cleartext 局域网可选）  
8. ~~局域网 SMB 发现（mDNS + 可选 445）~~ → 已落地，见 `docs/decisions/0004-lan-discovery.md`
