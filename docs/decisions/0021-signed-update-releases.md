# 0021：固定签名、显式请求与静态更新清单

- 日期：2026-10-08
- 状态：接受；维护者已确认签名配置，真实 Actions 与首发验收待执行
- 任务：FN-59（客户端 FN-58；最终集成 FN-57）

## 背景

FrameNest 旧发布手工上传 APK，Gradle 没有持续 release 签名配置。实际核验
v0.5.2-internal 为 Android Debug 证书。每次 CI 新生成 debug key 会使同包名版本
无法覆盖安装，不能作为应用内更新链。用户要求参考纯粹骑行的更新/发布体验，
并明确表示由自己处理密钥。随后维护者确认四个 signing secrets 已配置，明确
授权固定证书 SHA-256 为旧版相同的 `31324de514179999b94c2afc395b2190e3c8c242b8cf13f699656303822fd8e8`。

## 决定

1. 长期私钥由维护者自行创建/备份，并在 GitHub 安全配置四个 signing secrets。
   项目只保存经维护者确认的目标公开证书 SHA-256；本次已按明确授权填入，
   与旧版证书一致。未配置或无效时发布失败。
   不生成私钥、不从 secret/APK 自动采纳新证书，也不复制其他项目的 secrets。
2. 历史 debug 签名本身不自动构成信任；本次另获明确指纹确认，保留覆盖兼容性。
   如果未来维护者确认的正式签名不同，明确提示首次手动迁移及卸载
   丢失应用数据的风险；不关闭 Android/客户端的签名校验，不声称无损覆盖升级。
3. main 的独立 release-request.json 提交是唯一发布触发。源码 SHA 必须完整且
   是祖先；两次提交之间只允许请求文件变化。所有构建固定到该 SHA。
4. 测试/构建只有 contents:read，最终 publish job 用内置 GITHUB_TOKEN 的
   contents:write。先验证 signed APK，后创建 tag + draft，全部 asset 重下核验
   后再公开；既有 tag/asset 不移动、不覆写。中断重试先核验旧数据。
5. 保留 arm64-v8a、x86_64 和 universal 三包。每个包都核对身份、版本、最低系统、
   native ABI、可调试状态、证书、大小与 SHA-256。
6. update.json 放在同一 GitHub release；客户端固定读取 latest/download/update.json，
   APK 使用固定版本链接。不需要 Supabase、账户、额外更新后端或匿名 API 额度。
   -internal 版本沿用仓库原有 stable release 通道（GitHub prerelease=false）。

## 拒绝的替代方案

- 每次源码 push 自动发布：用户尚未验收也会更新，不符合显式发布意图。
- CI 临时 debug key 或未确认的新 key：签名链不可持续或未经信任。
- 按最新 tag 临时下载任意 APK：缺少包身份、ABI、digest 与版本绑定。
- 无条件覆盖 tag/asset：破坏已发版本的可追溯性和在途更新的一致性。
- 先 public release 再上传：客户端可能读取到半个版本。
- 新增更新后端/长期 PAT：当前静态 GitHub 托管足够，无需扩大权限。

## 后果与验收边界

维护者确认的 secret 配置不能替代实际校验；公有发布和覆盖安装不能凭本地模拟
测试宣称成功。集成任务需触发真实 Actions 验证现有 key/所有 APK，再检查所有公开
文件和客户端入口。真实手机/平板安装、不同签名拒绝及 NAS 数据风险须单独记录。
操作步骤和失败恢复见 [RELEASING.md](../RELEASING.md)；客户端约束见决策 0020
与 [UPDATES.md](../UPDATES.md)。
