# DiLink-Auto AI Agent 协作与开发指南（AGENTS.md）

本文件面向进入本仓库的 AI Agent 与协同开发者，规定**仓库改动流程**与**硬性约束**。

配套文档：

- 怎么发 issue：[docs/ISSUE_GUIDE.md](docs/ISSUE_GUIDE.md)
- 怎么开 PR：[docs/PR_GUIDE.md](docs/PR_GUIDE.md)
- 怎么发版、签名怎么配：[docs/release.md](docs/release.md)

面向使用者的说明在 [README.md](README.md)；架构、协议、车机端、桌面端的实现细节在 `docs/`。

## 0. 硬性约束（违反即不合格）

- 沟通、代码注释、README、文档一律用中文。
- 运行环境是 **Windows + PowerShell**：不要给 Linux 命令，不要用 cmd 语法。
- Python 命令必须从 `.vnev` 激活虚拟环境后再运行。
- Node 包管理用 **pnpm**。
- 本项目**禁止 Tailwind CSS v4**，必须使用 v3.x 及其配套 PostCSS 配置。
- VD 部署必须 `background = false`（见 `VdServerDeployer`）；启动引擎用 `exec app_process` 保持 shell 流附加，不要用 `&` 后台化。
- 视频发送顺序必须由单线程派发器保证：**入队顺序 = 调用顺序**。
- 不提交真实域名、车牌、群号等隐私信息；提交前自查。
- **正式签名 keystore 与口令绝不入库**：keystore 只在发布者本机与 GitHub Actions secrets，仓库里只允许出现变量名与路径模板。
- **对外发布的包必须是正式签名**：`ALLOW_DEBUG_SIGNED_RELEASE=1` 只用于本地应急，任何 Release 资产都要用 `apksigner verify --print-certs` 校验证书指纹。
- **发版前必须把 `gradle.properties` 的 `app.versionName` 改成当前时间**（`YYYYMMDD-HHMM`，`versionCode` 自动派生），且工作区 `git status --porcelain` 为空（否则包版本串带 `-dirty`）。
- **已发布的 tag 不重打、不强推**；修问题用新版本号发新 tag。

## 1. 仓库结构（改代码前先定位）

| 模块 | 职责 |
|---|---|
| `:protocol-core` | 协议帧、编解码、连接/NIO 读写、黑屏检测等共享层 |
| `:protocol` | ADB、Android hooks 等平台适配 |
| `:vd-server` | 手机端 shell 进程：EGL + GLES2 + MediaCodec 编码管线、虚拟显示、触摸注入 |
| `:app-client` | 手机端 App：部署引擎、会话编排、设置与 UI |
| `:app-server` | 车机端 App：解码器、镜像界面、连接状态机 |
| `:app-desktop` | Windows 桌面接收端（JavaCV/FFmpeg） |

## 2. 证据与验证纪律

- **无证据不修复**：每条判断都要有 `文件:行号` 或可复现命令 + 原始输出。没有证据的 finding 视为虚构。
- **完成声明以磁盘为准**：`git status`、文件是否存在、构建/单测原始输出，三者对得上才算完成，不采信"我改好了"。
- **修红门禁先做基线**：同一检查在未打补丁的 base 上必须先红，且红的签名与 issue 症状一致；只有"打补丁后绿"属于假绿，不予合并。
- **实车问题先取现场日志**：没有 `adb` 设备或车机诊断日志时，不要靠猜下结论，按 `docs/ISSUE_GUIDE.md` 贴 `needs-repro` 或写明不可复现的原因。

## 3. 仓库改动流程（issue → plan → worktree → PR → 对账/回签）

所有仓库改动（代码、文档、配置、脚本）按五步走，缺一步即不合规：

1. **先发 issue**：按 [docs/ISSUE_GUIDE.md](docs/ISSUE_GUIDE.md) 立单，正文四要素（现象 / 证据链 / 影响面 / 修复候选）齐全。issue 是派活与验收的唯一依据；行为类 issue 必须带**一条可直接粘贴进 PowerShell 的复现命令**，给不出就贴 `needs-repro`。
2. **写 plan 追评到 issue 下面**：探索结论（带 `文件:行号` 证据）+ 方案 + 明确不做 + 验证方式，并包含一行「用 issue 的复现命令复现：成功 / 失败（贴原始输出）」。复现失败说明 issue 与现状不符，退回发现者补充，不进实施。
3. **开 worktree 改代码**：`git worktree add .tmp/wt-<issue> -b <type>/<issue>-<slug> main`，不在主工作区切分支；分支、PR 标题、commit message 三处都带 issue 号。
4. **开 PR**：按 [docs/PR_GUIDE.md](docs/PR_GUIDE.md)，正文分段齐全。**CI 全绿 + 无冲突才合并**。行为类 PR 用 `Refs #NN`，纯文档/纯重构才用 `Closes #NN`。
5. **合并后对账与回签**：修复者先逐项核对「自述 vs 实际」，并给行为类 issue 贴 `needs-live-verify`；再由发现者或全新上下文的验证 agent 按 `docs/PR_GUIDE.md` 的回签模板复跑复现命令，**回签通过才关 issue**（修复者不能给自己回签）。欠账转新 issue。
   - **角色单位是 Agent，不是 GitHub 账号**：PR 作者 Agent 与 issue 作者/回签 Agent 必须是不同 Agent；同一 GitHub 账号（如 `ID-VerNe`）可以代表不同 Agent 的不同轮次，不构成自签，也不要求另建 reviewer 账号。评审记录应写清「哪个 Agent / 哪个会话」，而不是以账号是否相同判定独立性。

## 4. 构建与验证（PowerShell）

```powershell
# 本机全模块单测（按需挑模块，避免每次全跑）
.\gradlew.bat :protocol-core:test :vd-server:test :app-server:test

# 与 CI 对齐的最小门禁
.\gradlew.bat :app-client:assembleDebug :app-client:testDebugUnitTest
```

`.github/workflows/build.yml` 在 PR 上实际执行的是 `:app-client:assembleDebug :app-client:testDebugUnitTest`；其余模块的单测需要本机跑。PR 的「测试」段必须贴原始输出（不是「已通过」）。

## 5. 现场取证

- 手机引擎日志：`adb pull /sdcard/DiLinkAuto/vd-server.log`。重点看 `Pipeline:` 行里的 `tex= / cb= / swapFails=` 与是否出现 `Write timed out`。
- 车机日志：release 构建下诊断日志默认关闭（`CarLogWriter.setEnabled(BuildConfig.DEBUG)`），需先在设置里打开；重点看 `[VideoDecoder]` 的 `Fed ... drops= inputFails= queue=` 与 `Rendered` 计数。
- 桌面端日志：`%APPDATA%\DiLinkAuto\desktop.log`，每秒一行 `decoded fps / total / rebuilds / recv / cfg / hw`。
- 日志与截图属于证据，不要修改原文后贴出；长日志截取关键行并标注文件路径。

## 6. 文档与计划目录

| 目录 | 用途 |
|---|---|
| `docs/` | 面向使用者与后续维护者的架构/协议/端侧文档、流程指引 |
| `.plan/` | 内部方案、审计报告、实施计划（结论落定后回填 `docs/`） |
| `.handoffs/` | 会话交接记录 |
| `.agents/teamwork/` | 多 Agent 协作的派工、简报与交接记录 |

新增方案优先写 `.plan/`，稳定结论回填 `docs/`；不要把过程性讨论写进 issue 长文。

## 7. 发布与签名（详见 [docs/release.md](docs/release.md)）

### 7.1 硬事实

| 项 | 值 / 规则 |
|---|---|
| 版本号唯一来源 | `gradle.properties` 的 `app.versionName`，格式 `YYYYMMDD-HHMM`；`versionCode` 由它派生（`YYMMDDHH`），不再手填（issue #224） |
| 正式签名证书 | `CN=DiLink Auto`，SHA-256 `84417b25a0ef960efcaa7e787c4779aa38ff1ffb265c345d95135a540e34f36a` |
| keystore 位置 | 发布者本机 `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks`；CI 走 `RELEASE_KEYSTORE_BASE64` secret 解码到 `$RUNNER_TEMP` |
| 环境变量 | `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` 必需，`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` 可选 |
| 触发条件 | 日期-时间 tag `vYYYYMMDD-HHMM` → `Build (Release)`；历史 `vX.Y.Z-dev-N` → `Build (Pre-Release)` |
| runner | 两条发布流水线都是 `ubuntu-latest`；本仓库**没有** self-hosted runner |

### 7.2 发布步骤（PowerShell）

```powershell
# 1. 版本号（app.versionName）已在 main 上改成时间戳并合并，工作区干净
git status --porcelain
# 2. 打 tag（用 merge commit，不要用分支名；tag 必须与 app.versionName 逐字一致）
git fetch origin main
git tag v20261117-2247 origin/main
git push origin v20261117-2247
# 3. 盯流水线
gh run list -R ID-VerNe/dilink-auto-android --limit 3
# 4. 下载资产后校验签名（命令见 docs/release.md §3）
```

### 7.3 不能忘的坑（完整版见 `docs/release.md` §5）

- 缺 keystore 时 release 变体在**配置阶段**直接失败（`app-client/build.gradle.kts:54`），不是产出 unsigned 包。
- `RELEASE_*` 齐全时 debug 变体也会套正式签名（`app-client/build.gradle.kts:83-86`），预发布流水线同样要 keystore。
- `.tmp/` 等未跟踪目录会让 `BUILD_REVISION`（`build.gradle.kts:14-23`）带 `-dirty`；`/.tmp/` 已在 `.gitignore`。
- 正式签名上线后**旧包不能覆盖安装**，手机与车机都要先卸载。
- `app-client` 的 `embedServerApk` 现在按客户端变体内嵌车机包（issue #37）：release 客户端内嵌正式签名车机包，debug 客户端内嵌 debug 包；`build-release.yml` 有证书摘要比对门禁，别把这条门禁删了。
- `buildVdServer` 必须同时依赖 `:protocol:bundleLibRuntimeToJarDebug` —— release-only 构建里否则会因找不到 protocol 的 debug `classes.jar` 而 D8 失败。
- `environment: release` 当前**没有**保护规则（`/environments` 返回 `total_count: 0`），tag 推上去会直接发版，别以为有审批闸门。
- GitHub 的 tag 过滤器里 `+` 表示"前一个字符一次或多次"，不是正则的 `+`。
- `Leave fork network` 不可逆且无 API；本仓库 2026-11-01 实测脱离后 issues / PR / Release / secrets 均保留（只是 `parent` 变 `null`），但操作前仍建议导出备份。
