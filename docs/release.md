# 发布与签名（Release Guide）

> 面向维护者与 Agent。本文记录 DiLink-Auto 的**正式签名**与**发版链路**，以及在这套基建落地过程中踩过的坑。
> 配套：仓库流程见 [AGENTS.md](../AGENTS.md) §3，PR 规范见 [PR_GUIDE.md](./PR_GUIDE.md)。

## 0. 一句话现状

- 发布包由 `dilink-auto-release` keystore 签名（证书 `CN=DiLink-Auto`），keystore 本体**不入库**，只存在于发布者本机备份与 GitHub Actions secrets（`RELEASE_KEYSTORE_BASE64`）。
- 发版 = 在 `main` 上把版本号 bump 并合并 → 打日期-时间 tag `vYYYYMMDD-HHMM` 推到 origin → `Build (Release)` 在 GitHub 托管 runner（`ubuntu-latest`）上构建并创建 GitHub Release。
- 签名证书变更后，**新旧包不能互相覆盖安装**，必须卸载重装（手机端和车机端都一样）。
- 2026-11 起本仓库（原 `andersonlucasg3/dilink-auto-android`）重新成为发布主线：fork `ID-VerNe/dilink-auto-android` 的 1.0.x 用另一把证书（`84417b25…36a`），从 fork 包切到本仓库包必须先卸载。

## 1. 版本号与 tag 规则

版本号自 issue #224 起改为**日期-时间**格式，不再用 `1.0.3` 这类语义化版本号 —— 日常迭代按天甚至按小时发布，语义化版本的 `+1` 不携带信息。

| 项 | 位置 / 规则 |
|---|---|
| 版本号唯一来源 | `gradle.properties` 的 `app.versionName`，格式 `YYYYMMDD-HHMM`（如 `20261117-2315`） |
| `versionCode` | **由 `app.versionName` 派生**：`20261117-2315` → `26111723`（`YYMMDDHH`）。不要再手填 |
| 正式发版 tag | `vYYYYMMDD-HHMM`（如 `v20261117-2315`），触发 `Build (Release)` |
| 预发布 tag | 历史形态 `vX.Y.Z-dev-N`，触发 `Build (Pre-Release)`；日期版本不走这条 |
| 构建版本串 | `BUILD_REVISION` 由 `build.gradle.kts` 用 `git rev-parse --short HEAD` + `git status --porcelain` 计算；工作区**有未跟踪文件**就会带 `-dirty` |

`versionCode` 派生规则在根 `build.gradle.kts` 单点实现，三个模块（`app-client` / `app-server` / `vd-server`）共用同一个 `rootProject.extra`，保证同一次构建里两边版本号绝不漂移。`app.versionName` 不是日期格式时（例如临时回到 `1.0.3` 形态）自动回退读 `app.versionCode`，两者都没有则构建直接失败。

**同一小时内多次构建得到同一个 `versionCode`** —— `pm install -r` 与 Android 都允许同 code 覆盖安装，日常迭代不需要为此等待或手工 bump。

当前代码版本以仓库根 [README.md](../README.md) 顶部为准，历史 tag 保持只读 —— **永远不要为了修一个发布包去强推已存在的 tag**，下一个时间戳自然会带新改动。

已知例外：`v1.0.1` 的 Release 资产是在正式签名改造**之前**构建的（debug 签名）。它的 tag 不重打，正式签名资产从 `v1.0.2` 起。

## 2. 正式 keystore

| 项 | 值 |
|---|---|
| 本机路径 | `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks`（**不在仓库内**；仓库历史里出现过同名旧副本，2026-11 合并时已删除） |
| 类型 / 别名 | PKCS12 / `dilinkauto` |
| 密钥 | RSA 2048（SHA384withRSA），有效期至 2051-04-20 |
| 证书 DN | `CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR` |
| 证书 SHA-256 | `e91b0551d832aa08af964398f644b002f9669031e3426e47075889bb6cdfd726` |

> SHA-256 是发布校验的唯一硬标准：Release 资产对不上这个指纹，就当发版失败处理。

### 2.1 生成（换证书时才需要，生成后不可再改）

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks" `
  -storetype PKCS12 -alias dilinkauto -keyalg RSA -keysize 2048 -validity 9125 `
  -dname "CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR"
```

口令自己保管，不要写进任何提交物、issue、PR 或聊天记录。

### 2.2 本机构建时注入

把四个变量写进 `%USERPROFILE%\.dilink-auto\release-signing.env`（`KEY=VAL` 单行格式），然后 dot-source 注入当前 PowerShell 会话：

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
$env:RELEASE_KEYSTORE_PASSWORD; $env:RELEASE_KEY_ALIAS   # 确认已注入
```

变量语义（读自 `app-client/build.gradle.kts:34-92`、`app-server/build.gradle.kts:28-72`）：

| 变量 | 必需 | 说明 |
|---|---|---|
| `RELEASE_KEYSTORE_PASSWORD` | 是 | 缺它且没显式放行 → **配置阶段**抛 `GradleException` |
| `RELEASE_KEY_PASSWORD` | 是 | 同上 |
| `RELEASE_KEYSTORE_FILE` | 否 | 不设时用 `app-client/dilink-auto-release.keystore` 默认相对路径（该文件已不在仓库，CI 必须显式设置） |
| `RELEASE_KEY_ALIAS` | 否 | 默认 `dilinkauto` |
| `ALLOW_DEBUG_SIGNED_RELEASE=1` | 否 | 仅本地应急：显式允许 release 变体用 debug key 签名，**不要**用于任何对外发布的包 |

### 2.3 GitHub Actions secrets

`Build (Release)` / `Build (Pre-Release)` 都需要这四个（大小写严格）：

```
RELEASE_KEYSTORE_BASE64      # jks 单行 base64，解码到 $RUNNER_TEMP
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

`RELEASE_KEYSTORE_BASE64` 生成方式（PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks")) |
  Set-Content -NoNewline "$env:USERPROFILE\.dilink-auto\keystore.b64" -Encoding ascii

$env:NO_PROXY=""
gh secret set RELEASE_KEYSTORE_BASE64 -R andersonlucasg3/dilink-auto-android < "$env:USERPROFILE\.dilink-auto\keystore.b64"
```

## 3. 本地构建与校验

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
.\gradlew.bat :app-server:assembleRelease :app-client:assembleRelease
```

校验签名（**每次发版前都跑**）：

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$sdk\apksigner.bat" verify --print-certs app-client\build\outputs\apk\release\app-client-release.apk |
  Select-String "certificate DN", "SHA-256"
& "$sdk\apksigner.bat" verify --print-certs app-server\build\outputs\apk\release\app-server-release.apk |
  Select-String "certificate DN", "SHA-256"
```

期望两行都是 `CN=DiLink-Auto, …` 与 `e91b0551…d726`。

## 4. 发布流程（照抄即可）

1. **改版本号**：只改 `gradle.properties` 的 `app.versionName` 为当前时间（`YYYYMMDD-HHMM`）；`versionCode` 自动派生，不要再写。同步文档里的版本号（`README.md`、`docs/README.md`、`docs/setup.md`、`docs/client.md`）。
2. **走 PR 合并到 `main`**（AGENTS.md §3 那五步），CI `Build` 全绿。
3. **确认 `main` 干净**：`git status --porcelain` 为空，否则包版本串会带 `-dirty`。
4. **打 tag 并推**（tag 必须与 `app.versionName` 逐字一致，流水线会校验）：
   ```powershell
   git fetch origin main
   git tag v20261117-2315 <merge-commit>
   git push origin v20261117-2315
   ```
5. **盯流水线**：`gh run list -R andersonlucasg3/dilink-auto-android --limit 3`，`gh run watch <id>` 到 success。
6. **验收资产**：下载 Release 里的 `DiLinkAuto-Phone.apk` / `DiLinkAuto-Car.apk` 跑第 3 节的 `apksigner`，指纹必须一致；不一致就摘 release 并回滚。
7. **在 issue 贴证据**：run 链接 + `apksigner` 原始输出。

## 5. 坑清单（都是实际踩过的）

| # | 坑 | 现象 / 后果 | 处理 |
|---|---|---|---|
| 1 | `runs-on: self-hosted` | 本仓库没有自托管 runner，tag 发布 **永远 queued**，看起来像"发了没反应" | 统一改 `ubuntu-latest`（`build.yml` / `build-release.yml` / `build-pre-release.yml` 三处） |
| 2 | fork 默认不跑 Actions | fork 的 Actions 页没点过 "I understand my workflows… enable them" 时，push/PR **零 check**，`gh pr checks` 输出为空 | owner 在 Actions 页启用；注意 `actions/permissions.enabled=true` ≠ 已启用 |
| 3 | 缺 keystore 就构建 release | 口令缺失时**在配置阶段**抛 `GradleException`，不是打出 unsigned 包 | CI 里 `Decode release keystore` 步骤先落盘并写 `RELEASE_KEYSTORE_FILE`；缺 secret 时该步骤直接 `exit 1` |
| 4 | `RELEASE_*` 齐全时 debug 变体也用正式签名 | `app-client/build.gradle.kts:83-86` 会把 release signingConfig 套到 debug 变体上，**预发布流水线同样需要 keystore** | `build-pre-release.yml` 也要先解码 keystore |
| 5 | 未跟踪文件污染版本串 | `.tmp/`（worktree 目录）未忽略时，`BUILD_REVISION` 恒为 `-dirty` | `/.tmp/` 已进 `.gitignore`；发版前仍要 `git status` 复核 |
| 6 | 签名变更后覆盖安装 | 老包装机 → 新包 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`/签名不一致 | 首次迁移到正式签名时**先卸载旧包**；从 fork 1.0.x（另一把证书）切到本仓库包同样要卸载 |
| 7 | 车机内嵌 APK 的变体 | 旧行为固定嵌入 `app-server-debug.apk`（audit B-05）；正式签名后手机端自更新会推 debug 签名包，与车机上已装的正式签名包冲突 | **已修复（issue #37）**：`embedServerApk` 按客户端变体选 `app-server-release.apk` / `app-server-debug.apk`；`build-release.yml` 的 `Verify embedded car APK signature` 步骤比对两份产物的证书摘要，不一致即失败 |
| 8 | `environment: release` 没有保护规则 | environment 已创建，但**没有** required reviewers，tag 推上去会直接发版 | 想加审批闸门需要在仓库 Settings → Environments 里配置 reviewers；不要以为写了 `environment:` 就有闸门 |
| 9 | GitHub 标签过滤器的 `+` 语义 | 分支/标签过滤器里 `+` 表示"前一个字符一次或多次"，`v[0-9]+.[0-9]+.[0-9]+` 才能匹配 `v1.0.2`；它不是正则 | 改 tag 规则时按这个语义写，别用正则直觉 |
| 10 | 历史 tag 的资产不会自动更新 | 改了构建配置后，旧 tag 的 Release 资产仍是旧包 | 用新 tag 发新版本；**不要**删 tag 重推 |
| 11 | PowerShell 读 `local.properties` | `sdk.dir` 值带 `C\:\\…` 转义，直接喂给 `Get-ChildItem -Directory` 会报错 | 脚本里直接用 `$env:LOCALAPPDATA\Android\Sdk` 拼路径或用 `-LiteralPath` |
| 12 | `gh` 走代理失败 | 本机代理让 `gh` 请求卡住 | 调用前 `$env:NO_PROXY=""` |
| 13 | `Leave fork network` 不可逆 | 只能在网页操作，没有 REST API。本仓库 2026-11-01 实测：脱离后 issues / PR / Release / secrets **全部保留**，只是 `parent` 与 fork 标记消失（`gh repo view --json isFork` 变 `false`、`parent` 变 `null`） | 操作不可逆，脱离前仍建议先导出备份（`gh issue list --json …` + 逐条评论）；备份不要提交进仓库 |
| 14 | 退出 fork 前删本地 remote 不等于脱离 | `git remote remove upstream` 只影响本地；GitHub 侧 `isFork` 仍为 `true` | 以 `gh repo view <repo> --json isFork,parent` 为唯一判据 |
| 15 | 日期格式版本号被比较逻辑塌陷 | `compareVersions` 旧实现按 `.` 切段、非数字 coerce 成 0，任意两个 `YYYYMMDD-HHMM` 版本号都判相等 → 车机自更新**永久静默跳过**，UI 还显示「已是最新版本」 | **已修复（issue #224）**：`Versioning.kt` 增加日期格式分支，按 `[YYYYMMDD, HHMM]` 两段比较；`VersioningTest` 有 7 例墓碑 |
| 16 | 日期 tag 匹配不上发布流水线 | `build-release.yml` 原 tag 过滤是 `v[0-9]+.[0-9]+.[0-9]+`，`v20261117-2315` 不触发，push 后毫无反应 | tag 过滤已加 8+4 位数字形态（Gradle 过滤器不能用 `{8}`，必须逐位写）；另有 `Verify tag matches versionName` 步骤拦住 tag 与 `app.versionName` 不一致 |

## 6. 应急处置

| 情况 | 做法 |
|---|---|
| tag 推错（版本号不匹配） | 删掉远端 tag 与对应 Release，重新 bump 版本号后发新 tag；**已发布过的 tag 不重打** |
| Release 资产签名不对 | 立即把该 Release 设为 draft / 删除资产，查 `Decode release keystore` 是否用了正确的 base64，然后重跑一次发版 |
| secrets 被清空 | `gh secret list -R andersonlucasg3/dilink-auto-android` 复核四项，缺什么补什么（见 2.3） |
| 流水线一直 queued | 先查 `runs-on` 是否又写回 `self-hosted`；再查仓库 Actions 是否被禁用 |
| 用户升级报签名冲突 | 让用户卸载旧版后重装；不要试图"兼容"另一个签名 |
