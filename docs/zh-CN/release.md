# 发布与签名（Release Guide）

> 面向维护者与 Agent。本文记录 DiLink-Auto 的**正式签名**与**发版链路**，以及搭建这套基建时实际踩过的坑。
> 配套：仓库流程见 [AGENTS.md](../../AGENTS.md) §3，PR 规范见 [PR_GUIDE.md](./PR_GUIDE.md)。

## 0. 一句话现状

- 发布包由 `dilink-auto-release` keystore 签名（证书 `CN=DiLink-Auto`）；keystore 本体**不入库**，只存在于发布者本机的备份与 GitHub Actions secrets（`RELEASE_KEYSTORE_BASE64`）中。
- 发版 = 在 `main` 上 bump `app.versionName`/`app.versionCode`，打语义化 tag `vX.Y.Z` 推到 origin → `Build (Release)` 在 GitHub 托管的 runner（`ubuntu-latest`）上编译并创建 GitHub Release。
- 签名证书变更后，**新旧包不能互相覆盖安装**：必须卸载（手机端和控制中心端都一样）。
- fork `ID-VerNe/dilink-auto-android`（1.0.x）的包用的是**另一把**证书（`84417b25…36a`）；要从这些包迁移到本仓库的包，必须先卸载。

## 1. 版本号与 tag 规则

本仓库使用**语义化版本**（`MAJOR.MINOR.PATCH`），与此前的历史 tag `v0.17.1` 和 `v0.19.0-dev-N` 一致。

| 项 | 位置 / 规则 |
|---|---|
| 版本号唯一来源 | `gradle.properties`：`app.versionName`（`0.19.0`）与 `app.versionCode`（整数，单调递增；当前为 `26111724`） |
| `versionCode` | 手工维护、**始终递增** —— `pm install -r` 会拒绝降级。它要随版本号一起 bump。计数器从历史上发布过的最大 code（`26111723`，由 fork 时代的日期方案产出）继续，而不是从 fork 之前的 `60` 重新开始；见坑 13 |
| 正式发版 tag | `vX.Y.Z`（如 `v0.19.0`），触发 `Build (Release)` |
| 预发布 tag | `vX.Y.Z-dev-N`（如 `v0.19.1-dev-3`），触发 `Build (Pre-Release)` |
| 构建版本串 | `BUILD_REVISION` 由 `build.gradle.kts` 用 `git rev-parse --short HEAD` + `git status --porcelain` 计算；**有一个未跟踪文件**就会让版本串带上 `-dirty` |

构建会校验版本格式：`app.versionName` 必须匹配 `MAJOR.MINOR.PATCH` 或 `MAJOR.MINOR.PATCH-dev[-N]`（单独的 `-dev` 视为 dev 序号 0），并且 `app.versionCode` 必须存在。版本在构建根一次性算出，由 `app-client` / `app-server` / `vd-server` 共享（通过 `rootProject.extra`），因此同一次构建中三者绝不会漂移。

`app-client` 的 `Versioning` 仍能识别旧的 `YYYYMMDD-HHMM` 格式（继承自 fork），用于比较旧包版本；它**不再**用于生成版本号。见 `docs/client.md`。

当前代码版本以 [README.md](../README.md) 顶部为准。历史 tag 保持只读 —— **永远不要强推一个已发布的 tag**；用一个新版本去修。

已知例外：`v1.0.1` 的资产是在正式签名**之前**构建的（用 debug 签名）。该 tag 不重打；带正式签名的资产从 `v1.0.2` 起（来自 fork）。本仓库第一个走正式签名链的版本是 `v0.19.0`。

## 2. 正式 keystore

| 项 | 值 |
|---|---|
| 本机路径 | `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks`（**在仓库之外**；fork 的 git 历史里仍存在一份同名旧副本——2026-11 合并只把它从工作树移除，历史本身没有重写） |
| 类型 / 别名 | PKCS12 / `dilinkauto` |
| 密钥 | RSA 2048（SHA384withRSA），有效期至 2051-04-20 |
| 证书 DN | `CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR` |
| 证书 SHA-256 | `e91b0551d832aa08af964398f644b002f9669031e3426e47075889bb6cdfd726` |

> SHA-256 是发布校验的唯一硬标准：如果 Release 资产对不上这个指纹，就说明发版失败了。

### 2.1 生成（只在更换证书时需要；生成后不再改变）

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks" `
  -storetype PKCS12 -alias dilinkauto -keyalg RSA -keysize 2048 -validity 9125 `
  -dname "CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR"
```

口令自己保管；不要写进任何提交、issue、PR 或聊天记录。

### 2.2 注入本机构建

把四个变量写进 `%USERPROFILE%\.dilink-auto\release-signing.env`（每行 `KEY=VAL`），再把它们加载进 PowerShell 会话：

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
$env:RELEASE_KEYSTORE_PASSWORD; $env:RELEASE_KEY_ALIAS   # 确认已注入
```

变量语义（读自 `app-client/build.gradle.kts` 与 `app-server/build.gradle.kts`）：

| 变量 | 必需 | 说明 |
|---|---|---|
| `RELEASE_KEYSTORE_PASSWORD` | 是 | 缺它（且没有显式放行）→ **配置阶段**抛 `GradleException` |
| `RELEASE_KEY_PASSWORD` | 是 | 同上 |
| `RELEASE_KEYSTORE_FILE` | 否 | 不设置时使用默认相对路径 `app-client/dilink-auto-release.keystore`（该文件不在仓库中；CI 中必须设置它） |
| `RELEASE_KEY_ALIAS` | 否 | 默认 `dilinkauto` |
| `ALLOW_DEBUG_SIGNED_RELEASE=1` | 否 | 仅限本地应急：允许 release 变体用 debug key 签名；**不要**用于对外发布的包 |

### 2.3 GitHub Actions secrets

`Build (Release)` 与 `Build (Pre-Release)` 都需要这四个（大小写严格）：

```
RELEASE_KEYSTORE_BASE64      # jks 单行 base64，解码到 $RUNNER_TEMP
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

`RELEASE_KEYSTORE_BASE64` 的生成方式（PowerShell）：

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

两行都必须显示 `CN=DiLink-Auto, …` 和 `e91b0551…d726`。

## 4. 发布流程（照抄即可）

1. **设置版本号**：修改 `gradle.properties` 里的 `app.versionName`（语义化版本）和 `app.versionCode`（单调递增）。同步 `README.md`、`docs/README.md`、`docs/setup.md` 和 `docs/client.md` 中的版本引用。
2. **向 `main` 提 PR**（AGENTS.md §3 的五步），CI `Build` 全绿。
3. **确认 `main` 干净**：`git status --porcelain` 为空；否则包版本串会带上 `-dirty`。
4. **打 tag 并推送**（tag 必须严格等于 `v` + `app.versionName`；流水线会校验）：
   ```powershell
   git fetch origin main
   git tag v0.19.0 <merge-commit>
   git push origin v0.19.0
   ```
5. **盯流水线**：`gh run list -R andersonlucasg3/dilink-auto-android --limit 3`，并用 `gh run watch <id>` 盯到成功。
6. **验收资产**：从 Release 下载 `DiLinkAuto-Phone.apk` / `DiLinkAuto-Car.apk`，跑第 3 节的 `apksigner`；指纹必须一致。不一致就撤下/回滚。
7. **在 issue 里贴证据**：run 链接 + `apksigner` 原始输出。

## 5. 坑清单（都是实际踩过的）

| # | 坑 | 现象 / 后果 | 处理 |
|---|---|---|---|
| 1 | `runs-on: self-hosted` | 本仓库没有自托管 runner，tag 发布**永远排队**，看起来像“发了什么都没发生” | 三个 workflow（`build.yml` / `build-release.yml` / `build-pre-release.yml`）统一使用 `ubuntu-latest` |
| 2 | Actions 被禁用 | 没点过 “I understand my workflows… enable them” 时，push/PR **完全没有 check**，`gh pr checks` 返回空 | 在 Actions 页面启用；`actions/permissions.enabled=true` ≠ Actions 已启用 |
| 3 | 缺 keystore 就构建 release | 没有口令时构建**在配置阶段**抛 `GradleException`，而不是产出 unsigned 包 | CI 中 `Decode release keystore` 步骤先落盘并设置 `RELEASE_KEYSTORE_FILE`；secret 缺失时该步骤直接 `exit 1` |
| 4 | debug 变体也拿到正式签名 | 设置了 `RELEASE_*` 后，`app-client/build.gradle.kts` 会把 release 的 `signingConfig` 也套到 debug 上 —— **预发布流水线同样需要 keystore** | `build-pre-release.yml` 也在编译前解码 keystore |
| 5 | 未跟踪文件污染版本串 | `.tmp/`（worktree）未被忽略时，`BUILD_REVISION` 会永远带 `-dirty` | `/.tmp/` 已在 `.gitignore` 中；发版前仍要检查 `git status` |
| 6 | 签名变更后覆盖安装 | 已装旧包 → 新包报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`/签名不一致 | 首次迁移到正式签名时**先卸载旧包**；离开 fork 1.0.x（另一把证书）时同样要卸载 |
| 7 | 内嵌控制中心 APK 的变体 | 旧行为总是嵌入 `app-server-debug.apk`（审计 B-05）；使用正式签名后，自更新会推一个 debug 包，与已签名的控制中心冲突 | **已修复（issue #37）**：`embedServerApk` 按客户端变体选择 `app-server-release.apk` / `app-server-debug.apk`；`build-release.yml` 的 `Verify embedded car APK signature` 步骤比对两份产物的摘要，不一致即失败 |
| 8 | `environment: release` 没有保护规则 | environment 存在，但**没有** required reviewers；推上 tag 会直接发版 | 想要审批就在 Settings → Environments 中配置 reviewers；不要以为写了 `environment:` 就有闸门 |
| 9 | GitHub tag 过滤器里的 `+` 语义 | 过滤器里 `+` 表示“前一个字符一次或多次”；`v[0-9]+.[0-9]+.[0-9]+` 能匹配 `v1.0.2`，但它不是正则 | 按这个语义写 tag 规则，不要凭正则直觉写 |
| 10 | 旧 tag 的资产不会更新 | 改了构建配置后，旧 tag 的 Release 里仍是旧包 | 发布新版本；**不要**删除并重推 tag |
| 11 | PowerShell 读 `local.properties` | `sdk.dir` 的值带 `C\:\\…` 转义，会让 `Get-ChildItem -Directory` 报错 | 用 `$env:LOCALAPPDATA\Android\Sdk` 拼路径，或用 `-LiteralPath` |
| 12 | `gh` 因代理失败 | 本机代理让 `gh` 卡住 | 调用前设置 `$env:NO_PROXY=""` |
| 13 | fork 合并后的 `versionCode` 连续性 | 构建 `v20261117-2315`（日期方案，已废弃）发布过 `versionCode` 26111723。把语义化版本的计数器重新从 fork 之前的 `60` 开始会是一次**降级**，`pm install -r` 会拒绝 | **已处理**：语义化版本保持它的语义名称，但 code 从 `26111724` 继续，因此 `v0.19.0` 可以覆盖安装到那个构建之上。不要把这个数字“整理”回一个小数字 —— 那样安装就会要求先卸载 |
| 14 | 预发布语义化 tag 走错流水线 | `v0.19.1-dev-3` 不会触发 `Build (Release)`（过滤器排除了 `*dev*`），而 `v0.19.0` 也不会触发预发布流水线 | 给正确的渠道用正确的格式；release 流水线同样会拒绝带 `-dev` 的 tag |

## 6. 应急处置

| 情况 | 做法 |
|---|---|
| tag 推错（版本号不匹配） | 删除远端 tag 和对应的 Release，bump 版本号后重新发布；**已发布的 tag 不重打** |
| Release 资产签名不对 | 把该 Release 标记为 draft / 删除资产，检查 `Decode release keystore` 是否用了正确的 base64，然后重跑发布 |
| secrets 被删除 | 用 `gh secret list -R andersonlucasg3/dilink-auto-android` 核对这四项，缺哪个补哪个（见 2.3） |
| 流水线一直排队 | 检查 `runs-on` 是否又变回 `self-hosted`，以及仓库的 Actions 是否启用 |
| 用户升级时报签名冲突 | 让用户卸载旧版本后重新安装；不要试图“兼容”另一个签名 |
