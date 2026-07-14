# FrameNest 提交与合并记录

**记录日期**：2026-07-14  
**当前分支**：`main`  
**HEAD**：`6f7314c` — brand: premium FrameNest launcher icon  
**工作区**：干净（无未提交改动）  
**远程**：尚未配置 `git remote`（仅本地仓库）

---

## 状态结论

| 项 | 状态 |
|---|---|
| 代码是否合并到 `main` | **是**（FN-00～FN-09 + post-MVP + logo） |
| 是否有未保存/未提交文件 | **无**（`git status` clean） |
| 当前版本 | **0.3.1-internal**（versionCode 4） |
| 是否已 push 到 GitHub 等 | **否**（无 remote） |

查看最新状态：

```bash
cd /Users/karl/workspace/ai/FrameNest
git status
git log --oneline -20
```

若需推送到远端：

```bash
git remote add origin <你的仓库 URL>
git push -u origin main
```

---

## 按波次汇总

### 规划与骨架

| Commit | 说明 |
|---|---|
| `bfb4627` | 初始化产品/架构/任务文档 |
| `708e8a4` | **FN-00** Android 工程骨架 |
| `653e294` | FN-00 交接 |

### Wave 1（FN-01 / 02 / 03）

| Commit | 说明 |
|---|---|
| `a316ec6` | **FN-01** libVLC 播放内核 spike |
| `40ba65b` / `8ffce51` | **FN-02** SMBJ + seekable 数据路径 |
| `8adf86c` / `e458737` | **FN-03** 自适应 App 外壳 |
| `6131bd3` | Merge FN-01 → main |
| `2b743c4` | Merge FN-02 → main |
| `1fdd50a` | Merge FN-03 → main |
| `e10544d` | Wave 1 集成说明 |

### Wave 2（FN-04 / 05）

| Commit | 说明 |
|---|---|
| `3c449a5` / `47e58a2` | **FN-04** 服务器管理与 SMB 浏览 |
| `c3ad8d7` | **FN-05** SMB 播放与历史 |
| `a6f9818` | Merge FN-04 → main |
| `fcb0b99` | Merge FN-05 → main（含 Room 统一） |
| `c30ff6a` | Wave 2 集成说明 |

### Wave 3（FN-06 / 07 / 08）

| Commit | 说明 |
|---|---|
| `17e4a32` | **FN-06** 内嵌 + 外挂字幕 |
| `677151d` / `67fe589` | **FN-07** 列表缩略图 + 首帧门闩 |
| `474c5c1` / `12afa46` | **FN-08** 自适应与无障碍打磨 |
| `bacc7ed` | Merge FN-06 → main |
| `915d5a6` | Merge FN-07 → main |
| `97fc0c4` | Merge FN-08 → main |
| `bea24aa` | Wave 3 集成说明 |

### Wave 4 / 封板（FN-09）

| Commit | 说明 |
|---|---|
| `0ba0596` | **FN-09** 设置/诊断/发布文档（0.3.0-internal） |
| `b2538ff` | STATUS 标记 FN-09 完成 |

### Post-MVP

| Commit | 说明 |
|---|---|
| `adc1345` | KI-05 导航 tab 记忆；删除 FakeCatalog |
| `52b08f3` | **0.3.1-internal**：ABI 分包、release minify、share 探测、缩略图并发、spike→debug |
| `6f7314c` | **品牌 Logo**：Adaptive Icon + `docs/brand/` |

---

## 时间线（`main` 完整 oneline，共 36 个提交）

```
6f7314c brand: premium FrameNest launcher icon (frame + nest + play)
52b08f3 chore: post-MVP batch 0.3.1 — size, shares, concurrency, debug spikes
adc1345 fix: remember tab when opening player; remove FakeCatalog
b2538ff docs: mark FN-09 complete on STATUS
0ba0596 FN-09: internal test release settings, diagnostics, and docs
bea24aa docs: record Wave 3 integration (FN-06/07/08)
97fc0c4 Merge FN-08: adaptive polish with FN-06/07 features
915d5a6 Merge FN-07: list thumbnails and first-frame gate
bacc7ed Merge FN-06: embedded and sidecar subtitles
12afa46 docs(FN-08): pin handoff commit hash
474c5c1 FN-08: adaptive polish, a11y, and phone/tablet UI tests
17e4a32 FN-06: embedded + sidecar subtitles with match ranking
67fe589 docs(FN-07): add task handoff
677151d FN-07: list thumbnails and first-frame play gate
9c8701a docs: start Wave 3 FN-06/07/08 in parallel
c30ff6a docs: record Wave 2 integration
fcb0b99 Merge FN-05: product player, history, and Wave 2 integration
a6f9818 Merge FN-04: server management and SMB browsing
c3ad8d7 FN-05: SMB playback vertical slice and history
47e58a2 docs(FN-04): pin handoff commit hash
3c449a5 FN-04: server management and SMB browser
93d6b1f docs: start Wave 2 FN-04 and FN-05 in parallel
e10544d docs: record Wave 1 integration on main
1fdd50a Merge FN-03: adaptive phone/tablet app shell
2b743c4 Merge FN-02: SMBJ client and seekable data path spike
6131bd3 Merge FN-01: libVLC player kernel spike
cb05aeb docs: record FN-01/02/03 wave-1 results as pending integration
a316ec6 FN-01: validate libVLC player kernel spike
8ffce51 docs: pin FN-02 handoff commit hash
40ba65b FN-02: SMBJ client spike, seekable data path, and decision record
e458737 docs: pin FN-03 handoff commit hash
8adf86c FN-03: adaptive phone/tablet app shell with fake navigation
d7f21a0 docs: mark FN-01/02/03 in progress with worktrees
653e294 docs: pin FN-00 handoff commit hash
708e8a4 FN-00: scaffold Android app skeleton with latest stable toolchain
bfb4627 docs: initialize FrameNest implementation plan
```

---

## 功能 Agent 分支（已并入 main，仍可作历史参考）

本地仍保留 worktree/分支（`+` 表示 worktree 检出）：

- `agent/FN-00-android-skeleton` … `agent/FN-09-release`
- `agent/post-mvp-polish`、`agent/post-mvp-batch`、`agent/brand-logo`

**不必再合并**；内容已在 `main`。确认无误后可清理：

```bash
# 示例：删 worktree 与分支（确认 main 完整后）
git worktree remove ../FrameNest-FN-01
# …
git branch -d agent/FN-01-player-spike
```

---

## 关键产物路径

| 产物 | 路径 |
|---|---|
| Debug 通用 APK | `app/build/outputs/apk/debug/app-universal-debug.apk` |
| Debug arm64 APK | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| 变更说明 | `CHANGELOG.md` |
| 任务看板 | `tasks/STATUS.md` |
| 交接目录 | `tasks/handoffs/` |
| 决策记录 | `docs/decisions/` |
| Logo | `docs/brand/`、`app/src/main/res/mipmap-*` |
