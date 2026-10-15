# 0032 — 可选流式实验与最终字幕背压

日期：2026-10-14，FN-72。此实现是隔离的可测试接口，未接入生产设置或默认引擎。
尤其当前 Zipformer 候选仅中英，不能用于用户要求的日语验收。

## 边界

- 保留 SenseVoice 默认与已有离线 AsrEngine。新增 StreamingAsrEngine 接受最多30秒
  的NAS音频区间，输入是连续16kHz单声道、每块不超过200ms的PCM Flow。
- 新输入适配器每次只请求现有pcmWindow seam最多3秒，按下游需求发送小块。
  不整片载入、不落盘；短读视为真正输入结束，不补虚假媒体音频来凑覆盖。
- Partial带段号/修订号/媒体时间，只能显示于会话内存，不入字幕缓存，不调用MT。
- Final才进入单路MT队列，最多2条等待；队列满时NAS生产者暂停，不丢终句。
  最多保留2条排队、1条在译、1条等待发送；每条正文最多2048字符，token也有限额。
  这不是麦克风实时溢出策略。
- source final先发布；MT成功并且所有终句处理完、收到明确Finished并覆盖整个请求
  区间，才发出窗口完成候选。缓存端仍须核验原有lease/generation。空识别不是数字
  静音证明；partial、MT空结果/失败、短读、取消都不能假称完整双语覆盖。
- 持续同代次的终句顺序不回退、重复final不重复翻译。seek/换轨/换语言/退出须作废
  当前流请求代次和缓存lease；每个NAS区间使用递增请求代次。

## sherpa v1.12.32 适配约束

使用官方固定tag Kotlin API的OnlineRecognizer/OnlineStream，不传AssetManager，
native调用和释放在IO单路mutex下串行。取消/新代次会取消活动收集Job，打断等待PCM
或等待消费者；native decode本身可能不可中断，释放等待在途调用结束，不越过JNI锁。

Kotlin结果没有native start_time/isFinal，因此不盲用reset后的时间戳。端点处flush旧
stream后重建，已消费真实PCM边界作为新起点。词仅保留真实可配对的开始时间，不编造
结束时间。端点final start/end表示输入区间，不声称是词级发声边界。

结束前补有限零样本供native尾部上下文，随后inputFinished并drain；人工tail不计入
真实PCM数量或Finished媒体时间。默认参考上游示例的0.66秒，但具体checkpoint尾字
准确率、端点重建的上下文损失和重复/漏词必须用原生真实音频验收；mock通过不够。

默认端点规则包含1.4秒有语音后的静音等待（固定v1.12.32规则2），这不是零延迟
同传。partial可早出，第一阶段只翻译final。将来若做稳定前缀临时译文，必须可撤回、
合并限流并隔离revision，不能把几次相同partial冒充永久final。

## 许可与模型

可选中英模型元数据固定至HF revision，精确文件尺寸/SHA来源于官方LFS指针与tokens
文本；仓库内仅保存元数据，未下载权重、未建立下载器、未更改默认。加载前验证私有
目录、文件范围、尺寸、SHA，拒绝越界符号链接和路径。此元数据不能用于别的tar/NPU包。

日语需要另选真实支持日语的模型（当前调查Moonshine Japanese与已有SenseVoice），
以允许下载的日语视频做真实对照。ML Kit不能用桌面假译文代替双语验收。

## 官方依据

- 固定API：https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
- 固定文件解码flush示例：https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/python-api-examples/online-decode-files.py
- 固定Stream：https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/kotlin-api/OnlineStream.kt
- 候选及Apache-2.0模型卡：https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/tree/e2382758de9a0219b4efe682b95af30b399db3b8
- 具体ONNX目录：https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/tree/e2382758de9a0219b4efe682b95af30b399db3b8/exp/32

## 验收与发布门槛

独立JVM测试覆盖背压、尾部flush的调用顺序/覆盖不膨胀、代次取消、token映射、私有
模型校验与PCM连续性；桥接源码以固定上游API编译核对。尚未验证Android native实际
执行、真实模型准确率/尾字完整性、真实双语latency或手机功耗。

用户后续授权独立分支/PR/CI及验收成功后发布；这些授权不意味着当前实验已通过。
日语实样、真实翻译、Android CI及固定签名发布链未验收之前，不切换默认、不宣称提速。
