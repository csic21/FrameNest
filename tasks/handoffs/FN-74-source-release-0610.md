## FN-74 交接

- runtime：run38739397163，artifact11976112287/SHA c582c3564ffafbae9051b5fad29081fbd744a0c370e9a06d94415971f74b690b；两次各198行与stock1.13.8文本/PTS相同，CER38/436，保留词误识别、无提速、arm64 16KB未验收等限制。

- 状态：本地候选准备；runtime approval=true（固定报告hash），未推送、未合并、未签名、未发布。
- 分支：agent/FN-74-source-release-0610（独立 worktree，基于 522a06c）。
- 改动：0.6.10-internal/code21；release source=true/Hy=false/pending runtime gate；同repo固定Actions AAR导入；12项发布asset集合及固定release URL/hash复现入口；README/CHANGELOG/发布说明。
- 验收：132 Python tests通过（含真实AAR full notices/hash/资产与URL fail-closed）；现有43 release guards/hooks测试通过。新Gradle release配置尚未在Android CI执行，无signed APK结果。
- 决策：0036；模型/MT/native不变。唯一权限差异是既有android job增加actions:read读取同repo固定artifact；无新凭证或持久授权。
- 未解决：精确source runtime验收已独立通过并获父任务发布授权；仍需候选CI与最终signed APK检查。arm64 16KB未实测，不宣称提速。
- 集成：保持source app与stock PR6分支不动；不要更新release-request.json直到已集成且精确source验收通过。
