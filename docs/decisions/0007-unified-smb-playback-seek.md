# 决策：统一 SMB 产品播放与 seek 路径

- 状态：accepted（第 2 点交互节奏由 [0010](0010-player-scrub-preview-gestures.md) 替代；SMB 路径决定仍有效。代理 AFD 长度维持 UNKNOWN_LENGTH，取帧器使用的字节长度见 [0014](0014-fast-folder-covers.md)）
- 日期：2026-07-15
- 任务：FN-15 follow-up

## 问题

真机观察到部分较小视频拖动进度时画面刷新不稳定，而较大视频相对正常。实现中存在三处分叉：

1. 小于 4 GiB 的代理 AFD 声明固定长度，较大文件声明 `UNKNOWN_LENGTH`；
2. 播放中拖动会连续发送节流后的 fast seek，松手再发送 precise seek；
3. seekable SMB 在首帧前失败时会自动切换到 libVLC direct `smb://`。

这些分支让相同的用户操作可能进入不同的描述符、seek 和数据源行为，难以复现与验收。

## 决定

1. 产品的 `SeekableSmb` 无论文件大小都转换为无 user-info 的 `smb://` URI，由 libVLC
   SMB 模块直接播放；用户名、密码、domain 仅通过 media options 传递。产品播放不再使用
   `ProxyFileDescriptor`。该决定以真机日志中 `AppFuse mount point unavailable` 与
   `Transport endpoint is not connected` 为依据，替代决策 0002 对产品播放路径 B 的推荐。
2. 进度条拖动期间只更新本地 UI，结束手势状态后再提交唯一目标，避免 phase 同步更新导致
   Slider 重组时重复触发 finish。direct SMB 使用 `setTime(..., true)` 落到关键帧；本地文件
   使用 `setTime(..., false)`。SMB 历史进度在用户开始播放时执行一次 fast seek，不在暂停的
   首帧阶段做 precise seek，也不通过 `:start-time` 重开远程输入。
3. 所有正常完成的 SMB 视频保持硬件解码。真机 A/B 已确认强制软件解码会让高码率完整视频
   持续掉帧，因此不能作为 seek workaround。
4. 不做运行时数据路径 fallback；所有产品 SMB 视频从一开始就使用 direct SMB。
5. `ProxyFileDescriptor` 继续用于短生命周期缩略图和听译取样；AFD 一律声明
   `UNKNOWN_LENGTH`。目录枚举、字幕和听译使用各自 SMBJ 会话，不与播放传输共享。

## 被替代的行为

- 替代 FN-15 原有“拖动中按 120ms / 500ms 门槛发送 fast seek”的交互选择。
- 替代决策 0006 中“path B 失败后自动进入 path A fallback”的产品时序；其余状态机决定不变。
- 决策 0002 仍保留 direct SMB 作为可选诊断能力，但产品主路径不再自动切换。

## 后果

- SMB seek 不再因文件大小或一次打开失败而改变实现。
- 拖动中没有逐帧远程预览；每次松手只产生一次 fast seek，所有文件大小使用相同策略。
- 尚未下载完成、仍在增长或尾部索引未写完的视频不保证可 seek。真机中这类文件会表现为
  时钟变化、画面冻结、AAC 无效帧或 decoder deadlock；文件完整后应重新打开再播放。
- 播放不再依赖设备厂商的 AppFuse 长连接实现，也不会与缩略图代理 FD 竞争 mount。
- 字幕、同目录列表、缩略图和听译不会关闭或复用 libVLC 的播放传输。
