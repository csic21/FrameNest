# FN-68 安全审计与修复

审计日期：2026-10-11。起点：main `9122fad` / 0.6.6-internal。发布目标：0.6.7-internal / 18。

## 已修复

| 发现 | 影响/条件 | 修复及回归 |
|---|---|---|
| 两套凭证脱敏规则不一致；播放器规则按空白截断密码；部分服务器错误直接显示原消息 | 错误内容回显带空格或转义引号的凭证时，诊断分享/日志/UI 可能保留部分秘密 | 统一扫描器；覆盖转义、换行、未闭合值、仅用户名/含 @ 的 URL；全部异常日志接入 |
| 诊断环形缓冲只限制条数 | 单条异常消息过大时持续占用内存 | 脱敏后每条最多 4096 字符，标签 128 字符，保留 200 条；250 条大消息回归 |
| Vosk ZIP 目录内容未计入解压上限 | 非标准目录载荷经 closeEntry 排空；生产固定摘要降低攻击可达性，属于纵深加固 | 非空目录立即拒绝；测试恶意目录及正常空目录 |
| ML Kit 间接 OkHttp 3.0.0 / Okio 1.6.0，SMBJ 间接 Bouncy Castle 1.79 命中安全公告 | 包含受影响实现；没有声称全部漏洞在应用业务路径均可利用 | 约束至 4.12.0 / 3.6.0 / 1.85，严格摘要验证并执行 Android 加密与链接测试 |

依赖命中的公告：

- OkHttp：[CVE-2016-2402](https://github.com/advisories/GHSA-4hc2-jh7r-wrc3)、[CVE-2021-0341](https://github.com/advisories/GHSA-3cqm-mf7h-prrj)，涉及证书校验。
- Okio：[CVE-2023-3635](https://github.com/advisories/GHSA-w33c-445m-f8w7)，涉及异常 GZIP 处理。
- Bouncy Castle：[CVE-2025-14813](https://github.com/advisories/GHSA-574f-3g2m-x479)、[CVE-2026-0636](https://github.com/advisories/GHSA-c3fc-8qff-9hwx)、[CVE-2026-8763](https://github.com/advisories/GHSA-9pwp-9qqc-pr26)、[CVE-2026-13506](https://github.com/advisories/GHSA-qp49-qgx5-5m26)。公告分别涉及 GOST、LDAP、名称约束与 ASN.1。

最终使用 Gradle `releaseRuntimeClasspath.incoming.resolutionResult.allComponents` 的 ModuleComponentIdentifier 导出 186 个模块版本（包含 BOM/变体，不按依赖树文本重复计数），向 OSV querybatch 复扫；186 条返回均为空，无分页。仅发送公开坐标与版本，没有上传源码、凭证或媒体。完整版本与结果见 [扫描快照](security/FN-68-dependency-scan.json)。数据库覆盖随时间变化，空结果不是无漏洞证明。

## 审查范围及现有保护

- 凭证：Android Keystore 支持的加密偏好存储；Room 仅持别名。备份及设备迁移排除私有数据。凭证对象 toString 不含密码。
- Android 入口：主 Activity 仅启动器入口，不根据外部 Intent 执行下载/安装；FileProvider 不导出，仅允许 diagnostics/app-updates 缓存路径。
- SMB：SMBJ 默认签名与 SMB3 加密，旧 NAS 兼容是显式服务器设置；读取只读。独立 libVLC 数据路径不能用 SMBJ 配置推断加密。
- 模型：生产 Vosk/SenseVoice 固定来源、HTTPS 重定向范围、长度、SHA-256、续传及解压边界。旧 JSON 占位模型目录没有配置远端 URL，旧可选 HTTP helper 不在当前生产模型下载路径；以后启用必须先替换为有界验证下载。
- 字幕：下载上限 16 MiB，文件名清理，私有会话缓存；未对 libVLC 的 SRT/ASS 原生解析器做模糊测试。
- 更新：固定仓库与 HTTPS CDN，清单/包大小上限，APK 摘要、包名、递增版本、系统要求、当前签名相等校验，安装由 Android 系统确认。
- 供应链：工作流固定 Action commit；发布请求固定源码，签名证书固定，发布前下载全部产物重验；原有 Gradle 摘要不被替换。
- 对 532 个既有 Git 跟踪路径执行私钥头/GitHub token/AWS access ID 特征检查，未命中；跳过超过 2 MB 文件。没有读取本机签名密钥、扫描历史提交或访问真实 NAS。

## 验证与边界

- 严格模式 `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`：566 单测通过；Lint 0 error、62 warning、4 hint（存量告警），Debug 与 AndroidTest 编译通过。
- `python3 -m unittest discover -s scripts/tests -v`：35 项通过。
- Android API 35 arm64 平板及同模拟器 360dp 手机配置运行 SecurityCompatibilityTest，结果见任务交接。
- 依赖扫描仅覆盖可解析 Maven 坐标，未覆盖 AAR 内嵌的 libVLC/ONNX Runtime/Vosk 等原生组件完整 SBOM、未知漏洞或恶意媒体模糊测试；未验证真实 NAS、真实设备翻译模型下载/翻译、完整覆盖升级。不能声称“所有漏洞已消除”。

决策：[0028](decisions/0028-security-boundaries-and-dependencies.md)。发布及公开产物核验：[FN-68 交接](../tasks/handoffs/FN-68.md)。
