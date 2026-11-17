# 怎么开 PR（PR 操作指引）

> 本文件是仓库内所有 Agent 的 PR 操作规范，**必须遵照执行**。
> 配套：怎么发 issue 见 [ISSUE_GUIDE.md](ISSUE_GUIDE.md)；流程总览见 [AGENTS.md](../AGENTS.md) §3。

## 定位：PR 是最小可审查单元

- 一 issue 一 PR：PR 是 issue 的实施载体。**合并只代表改动进了 `main`，不代表症状消失** —— 行为类 PR 用 `Refs #NN`，issue 由发现者回签后关闭。
- 拆分原则：每个 PR 都能独立通过 CI、独立合并、独立回滚；拆不开说明边界没想清，回 issue 里补 plan。

## 关闭方式：Closes 还是 Refs

| 关键词 | 适用 PR | 关闭时机 |
|---|---|---|
| `Closes #NN` | 纯文档（`docs/`、`*.md`）、纯重构（不改外部行为） | 合并即关闭 |
| `Refs #NN` | 行为类：`fix` / `feat` / `perf`，以及任何触及运行时代码路径的 PR | 合并只进待验收队列（`needs-live-verify`），发现者回签后才关闭 |

判据一句话：**合并后是否还需要在真实设备上再观察一次症状才能确认有效** —— 需要就是行为类。

- 多分节 issue 的每个 PR 都用 `Refs #NN`，全部分节合并后统一回签、一次关闭。
- 同一 PR 既改文档又改行为：按行为类走。
- 行为类 PR 合并后由修复者给 issue 贴 `needs-live-verify`，回签通过后由回签者摘标签并关 issue。

## 拆分与命名规范

| 维度 | 标识 | 语义 |
|---|---|---|
| 分节（步骤） | `P+字母`：`PA`/`PB`/`PC`… | 把大 issue 切成有序、可独立合并的步骤；**一个分节 = 一个 PR** |
| 优先级 | `P+数字`：`P0`/`P1`/`P2` | P0 阻断（安全/数据损坏/主链不通）、P1 常见路径缺陷、P2 边角与增强 |

- 先按优先级定实施顺序，同优先级内按分节字母序推进；分节编号在 issue 内全局唯一，不随优先级重置。
- 引用格式：`#<issue>-P<字母>`，如 `#3-PA`。
- 落点：issue 分节标题写 `### PA · <标题>（P1）`；多分节分支写 `<type>/<issue>-p<字母>-<slug>`；PR 标题与 commit 带「分节 PA」后缀。

## 分支

```powershell
git worktree add .tmp/wt-<issue> -b <type>/<issue>-<slug> main
```

- 从最新 `main` 切，**用 git worktree，不在主工作区切分支**。
- `type` 与 issue 的类型对齐：`fix` / `feat` / `docs` / `refactor` / `chore` / `test` / `perf`。
- worktree 用完执行 `git worktree remove .tmp/wt-<issue>`，不要留在 `.tmp/` 里发酵。

## 标题与 commit

- PR 标题：`type(scope): 摘要（issue #NN）` —— 分支、标题、commit 三处都带 issue 号。
  - 示例：`fix(vd-server): 编码帧改为有界队列发送，网络反压不再憋停 VD（issue #3）`
- commit message 同样带 issue 号；一个 PR 允许多个 commit，但每个 commit 要自解释，不写 `wip` / `fix`。

## 正文分段

固定结构，必填段缺一不可：

| 段 | 必填 | 内容 |
|---|---|---|
| `Closes #NN` / `Refs #NN` | 是 | 按上节二选一，放正文末尾 |
| `## 问题` | 是 | 复述 issue 的现象 + 根因（带 `文件:行号`） |
| `## 改动` | 是 | 按文件列改动，每个文件说清「为什么」 |
| `## 测试` | 是 | 贴 `.\gradlew.bat` 的原始输出（成功/失败行），不写「已通过」 |
| `## 基线验证` | 修测试/门禁类 issue 时必填 | 见下 |
| `## 影响面` | 是 | 行为变化、兼容性、明确不动的部分 |
| `## 验收对照` | 大 issue 拆分时必填 | 按分节逐条对照，未完成项显式列出并转新 issue |

### 基线验证是硬要求

修「测试/门禁误报或漏报」类 issue 时，必须证明回归真的钉住了缺陷：

- 同一检查在**未打补丁的 base**（pristine worktree）上必须红，且红的签名与 issue 症状一致；
- 打补丁后转绿；
- 两者输出都贴进 PR。只有「打了补丁就绿」没有「不打补丁就红」= 假绿，不予合并。

## 开 PR 前自查

1. 本机跑与 CI 对齐的构建与单测，确认不是靠开发机私有状态绿出来的：
   ```powershell
   .\gradlew.bat :app-client:assembleDebug :app-client:testDebugUnitTest
   ```
   改动涉及其他模块时，补跑对应模块单测（如 `:protocol-core:test :vd-server:test :app-server:test`）。
2. **本机红线先跑基线**：同一检查在未打补丁 base 上复跑一遍，确认是代码问题还是环境问题 —— 环境问题不进修复，但要在 PR 里写明。
3. 确认分支基于最新 `main`，`git fetch origin main` 后无落后。
4. 实车/桌面端行为变更必须附现场证据：日志关键行、复现命令输出或截图路径；仅靠单测不算行为已验证。

## 合并条件（全满足才合）

1. **CI 全绿，且 CI 确实跑过**：用命令验证，不靠肉眼「按钮能点」：

   ```powershell
   gh pr checks <NN>
   ```
   - 输出**非空**（有 check）且**全部 success**（exit 0）才合格。输出为空 = CI 没跑（见下「fork 启用前置」），**不因按钮能点就合**。
   - 「CI 没跑」与「CI 红了」同等不合格；「本机等价门禁」不再代替 CI —— PR 正文「## 测试」段必须**同时**贴本机 `.\gradlew.bat` 原始输出与 CI run 链接，缺一即视为未验证。
   - `.github/workflows/build.yml` 在 push 与 pull_request 上都执行 `:app-client:assembleDebug :app-client:testDebugUnitTest`，跑在 `ubuntu-latest`。

   **fork 启用前置**：本仓库是 fork，GitHub 默认不自动运行 fork 的 workflow。首次使用前，owner 必须在仓库 Actions 页点过「I understand my workflows, go ahead and enable them」按钮，否则任何 PR/push 都零 check（`gh pr checks` 输出为空）。启用只对未来事件生效，**不回溯补跑**已合并的 PR。`actions/permissions` 显示 `enabled:true` 与「Actions 页未启用」是两个不同开关，前者为 true 不代表后者已点。

   `main` 当前未开分支保护，以上全靠自觉执行。
2. **无冲突**：GitHub 显示可合并。有冲突不强行合——在 worktree 里 `git merge origin/main`（或 rebase）解决，重跑构建与单测后再推。
3. 适用时有 `## 基线验证` 段。
4. 行为类 PR 合并后不直接关 issue，走下面的对账与回签。

## 合并后：对账与回签

对账是修复者自查，回签是发现者验收，两者都要做：

- **自述 vs 实际逐项核对（修复者）**：PR 说做了的每一条，合并后独立复核（`git status`、文件存在性、构建/单测原始输出）。
- **欠账转新 issue**：对账发现的遗漏、误伤、未实机闭环项，逐条转新 issue 跟进；合并不算完，对账才算。
- **行为类 issue 回签（发现者 / 全新上下文验证 agent）**：修复者合并后给 issue 贴 `needs-live-verify`；回签者按下面模板贴一条评论，结论为「症状消失」才摘标签并关闭。仍复现则摘标签、留 issue 并补证据。**回签者不能是本次 PR 的作者 Agent**。
  - **独立性按 Agent 判定，不按 GitHub 账号判定**：本仓库的开发流程由 Agent 推进，每个 Agent 相当于一个开发者。PR 作者 Agent 与 issue 作者/回签 Agent 必须是不同 Agent；同一 GitHub 账号（如 `ID-VerNe`）可以对应不同 Agent 的不同轮次，**不构成自签**，也不要求另建 reviewer 账号。回签评论应记录 Agent/会话标识，而不是用账号相同来认定角色冲突。
- 同根因复发时开第二代修复并互相引用，每代都留测试墓碑。

### 回签模板

贴到 issue 评论里，字段缺一不可：

- **环境**：`main` @ `<sha>`，本地实机 / CI，`<时间>`；涉及车机时写明车型、车机系统版本、连接方式（USB / WiFi ADB / 直连）
- **复现命令**：优先原样复用 issue「现象」段里的那条
- **期望输出**：修复后应看到什么
- **实际输出**：粘贴原始输出，不要只写「正常」
- **实机证据**：日志路径与关键行（如 `Pipeline:` 的 `tex=/cb=`、`[VideoDecoder]` 的 `drops=/inputFails=`）、连续观察时长
- **结论**：症状消失 / 仍复现（仍复现要贴差异）

## 模板（可直接抄）

```markdown
## 问题

<issue 现象复述 + 根因，带 `文件:行号`>

## 改动

`<文件>`
- <改动点 + 为什么>

## 测试

```
<.\gradlew.bat 的原始输出：任务成功/失败行>
```

本地同时通过：<模块单测列表>。

## 影响面

<行为变化 / 兼容性 / 明确不动的部分>

## 验收对照

- [x] <issue 验收项 1>
- [ ] <未完成项，转 #NN>

Refs #NN
```

行为类 PR 保持末行 `Refs #NN`，合并后按「合并后：对账与回签」等发现者回签；纯文档 / 纯重构 PR 换成 `Closes #NN`。
