# FrameNest 签名发布

FN-59 提供一次配置、以后复用的 GitHub Actions 发布流程。普通源码 push 只跑 CI，
不会发布。每次发布都要在 `main` 单独提交 `.github/release-request.json`，指定已经
集成并验收的完整 commit SHA。当前没有提前创建发布请求。维护者已确认四个 secrets
配置完成，且目标证书与旧版相同；必须由真实 Actions 验证签名后才允许公开发布。

## 首次配置：密钥由维护者自行保管

维护者自行保管并安全备份长期使用的 Android 签名密钥。本次沿用旧版证书，避免
破坏覆盖安装。不把密钥、口令、别名或
Base64 内容粘贴到聊天、Issue、源码、构建参数或日志。需要发布的仓库是
[`csic21/FrameNest`](https://github.com/csic21/FrameNest)。在该仓库的
Settings → Secrets and variables → Actions 中设置以下四个 repository secrets：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 维护者保管的固定 keystore 文件的 Base64 内容 |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名私钥的 alias |
| `ANDROID_KEY_PASSWORD` | 该私钥密码 |

此操作由维护者在 GitHub 安全界面完成。工作流不生成密钥，不采用 runner 临时 debug
密钥，不复制其他应用的签名或服务配置。发布仅用当前仓库 `GITHUB_TOKEN`；只有
`publish` job 有 `contents: write`，测试和签名构建 job 均为 `contents: read`。

另外，用维护者本机的 `keytool -list -v -keystore <文件> -alias <别名>` 查看公开的
证书 SHA-256（口令由本机交互提示输入）。将**经过维护者确认的目标证书指纹**移除冒号、
转成 64 位小写 hex，填入 `.github/android-signing-certificate.sha256`。
这个指纹是公开信息，不是私钥。2026-10-08 维护者已确认固定为
`31324de514179999b94c2afc395b2190e3c8c242b8cf13f699656303822fd8e8`，与旧版 APK
一致。此值已写入配置；若未配置或格式不正确，发布会失败。工作流不会把
“secret 中碰巧存在的 key”或下载到的 APK 自动提升为可信目标。

签名前先比较配置的证书和仓库固定指纹；构建后再用 `apksigner` 比较每个 APK 的所有
签名证书。临时 keystore 用 0600 权限放入 `$RUNNER_TEMP`，总是在 job 结束前删除；
不进入仓库、构建产物或 Gradle 写回缓存。所有 secret 仅注入签名步骤。

## 旧签名兼容性与未来迁移风险

旧的公开 `v0.5.2-internal` arm64 APK 已实际核对：

- 包名：`com.framenest`；versionCode：`10`；minSdk：`26`
- APK SHA-256：`f6f8d6b8da766cfdd353edca62c228098ba024ed91a679465cfa145aa8718233`
- 证书：Android Debug
- 旧证书 SHA-256：`31324de514179999b94c2afc395b2190e3c8c242b8cf13f699656303822fd8e8`

本次目标证书由维护者另外明确确认，恰好与上述值一致，因此不需要更换签名或
卸载迁移；仍需 Actions 验证 secret 中的私钥确实匹配。历史记录本身不构成未来
切换证书的授权。若将来维护者确认的目标证书与它不同，Android
不能覆盖安装旧包；应用内更新器也会拒绝签名不匹配的 APK。需要维护者/用户知情后
完成一次手动迁移。**卸载会删除本地设置、播放历史和已保存的 NAS 凭证**；不要把
卸载重装当成无损升级。先确认能重新配置 NAS、需要保留的数据已妥善处理，再自行
决定何时迁移。发布页会自动显示这一风险。切勿为了绕过安装限制而禁用签名检查。

后续版本必须继续使用同一把长期密钥、同一包名，并递增
versionCode；这样才能经应用内更新器覆盖安装。更换正式签名或实施密钥轮换需要
另行设计、审核和设备验证，当前流程不支持自动轮换。

## 每次发布

1. 在源码里同时递增 `app/build.gradle.kts` 的 versionName 和 versionCode，并填写
   `CHANGELOG.md` 对应章节。当前目标是 `0.6.0-internal` / `11`。
2. 将需要发布的功能/修复全部集成到 `main`。确认目标证书指纹已经由维护者审核提交，
   四个 signing secrets 都已由维护者配置。
3. 对**最终源码**运行单测、Lint、Debug/Release 构建和 AndroidTest 编译；完成可用的
   手机/平板以及真实 NAS 验收。编译 AndroidTest APK 不等于已经在设备上运行。
4. 取得已验收 `main` 的完整 40 位 SHA。从 `.github/release-request.example.json`
   复制到 `.github/release-request.json`，把 `source_sha` 换成该 SHA，`tag` 与
   `v` + versionName 一致。**单独提交请求文件**，不混入其他代码改动，再推送 main。
5. 在 Actions 查看 “Publish requested release”。请求验证要求 source 是请求提交
   的祖先，而且两者之间的文件差异仅有请求 JSON。构建和发布均 checkout 该固定源。
6. 工作流先执行 `testDebugUnitTest lintDebug assembleDebugAndroidTest`；签名材料
   验证后执行 `assembleRelease`，产出并校验全部 ABI APK、更新清单和来源证明。
7. 仅当全部检查通过才创建不可移动的 tag，再建立 draft release。全部文件上传并
   重新下载核对 SHA-256 后才公开 release，最后核对公开的 latest/update.json。

`-internal` 是本仓库的版本名称约定，GitHub Release 仍设置 `prerelease: false`，
与旧发布一致。否则 `/releases/latest/download/update.json` 不会指向它。这不是
Play Store 发布流程；仪器测试/真实 NAS 的未测项仍需在验收记录中明确说明。

新 tag 的语义版本和 Android versionCode 都必须前进。发现已有更高 stable tag，
或新版清单记录的 versionCode 未递增，拒绝发布。已有 tag 指向不同源码时永不移动。

## 产物和更新清单

每次 release 包含：

- `FrameNest-<versionName>-arm64-v8a.apk`
- `FrameNest-<versionName>-x86_64.apk`
- `FrameNest-<versionName>-universal.apk`
- 以上三个 APK 各自的 `.signature.txt`（仅公开证书验证信息）
- `update.json`、`release-manifest.json`、`SHA256SUMS`

保留 `com.framenest`、minSdk 26 和 arm64-v8a / x86_64；universal 作为两者兼容包。
APK 的包名、versionName、versionCode、minSdk、非 debuggable 状态、实际 native ABI、
证书、字节数、SHA-256 都在上传前核对。文件不能超过客户端的 512 MiB 限制。

`update.json` 契约为：

```json
{
  "schemaVersion": 1,
  "applicationId": "com.framenest",
  "versionName": "0.6.0-internal",
  "versionCode": 11,
  "tag": "v0.6.0-internal",
  "notes": "来自 CHANGELOG 对应版本章节的纯文本说明",
  "minSdk": 26,
  "assets": [
    {
      "abi": "arm64-v8a",
      "size": 123456,
      "sha256": "实际 APK 的 64 位小写 SHA-256",
      "url": "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-0.6.0-internal-arm64-v8a.apk"
    }
  ]
}
```

上面只展示一个 asset；实际清单恰好含 arm64-v8a、x86_64、universal 三项。
版本固定 URL 不使用 latest 下载 APK，避免检查和下载期间版本漂移。客户端固定入口：

`https://github.com/csic21/FrameNest/releases/latest/download/update.json`

完整客户端校验/权限/安装流程见 [UPDATES.md](UPDATES.md)。release-manifest 记录源
commit、repository、版本、公开签名指纹、Actions run/attempt 和三个 APK 的摘要。
SHA256SUMS 覆盖全部 APK、签名报告和 JSON（不包含自身）。发布页隐藏 provenance
marker 再记录包含 SHA256SUMS 在内的全部文件摘要，以支持中断恢复。
这是来源记录与 Android APK 签名验证，不宣称提供独立的 SLSA/Sigstore attestation。

## 失败与重试

- 没有 secrets、目标指纹未确认、secret 对应证书不匹配：在发布前失败，不生成替代密钥。
- 创建 tag 后失败：tag 保留原 SHA；重试只能复用同一个 tag/source。
- 上传途中失败：release 保持 draft；选择 Actions 的 **Re-run failed jobs**，复用
  成功 build job 的原始 artifact。已上传文件会先重下核对，缺少的文件再补传。
- 不要为一个已有部分产物的 draft 重建另一套 APK。若新构建字节不同，流程拒绝混合
  两次构建。完整 rerun 只有在已有 public release 所有产物验证一致时才作为只读成功。
- 所有文件已上传、公开前失败：只重试失败 job 即可，先验后公开。
- release 已公开、feed CDN 检查失败：不撤回、不覆写；检查服务可达性，再重跑失败 job。
  流程会验证已有 release，再检查 feed。等待窗口约 6 分钟（不含单次请求超时）。
- 发现名称相同但字节不同、不完整上传、未知 asset、无 provenance 的手工 release：
  停止并交给维护者审查。不 `--clobber`，不强推标签，不自动删任何公开产物。

本地不需凭证的脚本测试：

```bash
python3 -m unittest discover -s scripts/tests -v
python3 -m py_compile scripts/release.py
```

本地 release 构建也必须由维护者自行配置 `ANDROID_KEYSTORE_PATH`、
`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量。
缺失时 release pre-build/打包失败；Debug 构建不要求这些变量。可运行
`./gradlew :app:requireReleaseSigning` 单独检查必要配置（不输出其内容）。
真正的证书匹配由发布脚本和 apksigner 检查。不要把口令写入 shell 历史。

参考：[Android app signing](https://developer.android.com/studio/publish/app-signing)、
[GitHub release API](https://docs.github.com/en/rest/releases/releases)、
[GitHub release assets API](https://docs.github.com/en/rest/releases/assets)。
