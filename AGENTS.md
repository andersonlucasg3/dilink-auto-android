# DiLink-Auto — collaboration and development guide (AGENTS.md)

This file is intended for AI agents and developers who join this repository. It defines the **change workflow** and the **hard constraints**.

Related documents:

- How to open an issue: [docs/ISSUE_GUIDE.md](docs/ISSUE_GUIDE.md)
- How to open a PR: [docs/PR_GUIDE.md](docs/PR_GUIDE.md)
- How to publish, how to configure signing: [docs/release.md](docs/release.md)

User documentation is in [README.md](README.md); architecture, protocol, control-center app and desktop receiver details are in `docs/`.

## 0. Hard constraints (violating them is a failure)

- **Language:** README, documentation and release texts in **English** (canonical). Translations live under `docs/<language>/` (`pt-BR`, `zh-CN`, `fr`, `ru`, `be`, `kk`, `uk`, `uz`); when a translated page diverges from the English original, the English one wins. Code comments inherited from the fork are in Chinese and will be translated gradually; new code must use Portuguese or English.
- **Commands for the user:** Windows + PowerShell environment. Do not give the user Linux commands or `cmd` syntax. CI runs on `ubuntu-latest` (GitHub-hosted) and uses bash scripts — do not confuse the two.
- Python (e2e scripts) must run with the `.vnev` virtualenv activated.
- VD deployment must use `background = false` (see `VdServerDeployer`); starting the engine uses `exec app_process` to keep the shell stream attached, without `&`.
- Video send order must be guaranteed by a single-threaded dispatcher: **enqueue order = call order**.
- Do not commit a real domain, license plate, group number or any personal data; review before committing.
- **The official signing keystore and password never enter the repository**: they stay only on the publisher's machine and in GitHub Actions secrets; only variable names and path templates may appear in the repository.
- **A published package must be officially signed**: `ALLOW_DEBUG_SIGNED_RELEASE=1` is a local emergency measure only; every Release asset must pass `apksigner verify --print-certs` with the expected fingerprint.
- **Before publishing, set `app.versionName` and `app.versionCode` in `gradle.properties`** (semver; monotonic `versionCode`) and make sure `git status --porcelain` is empty (otherwise the package version string gains `-dirty`).
- **A published tag is never recreated or force-pushed**; fix it with a new version.

## 1. Repository structure (find your way around before changing anything)

| Module | Responsibility |
|---|---|
| `:protocol-core` | Shared layer: frames, messages, connection/NIO, frame codec, black-screen detection |
| `:protocol` | Platform adaptation: ADB, Android hooks |
| `:vd-server` | Phone shell process: EGL + GLES2 + MediaCodec, virtual display, touch injection |
| `:app-client` | Phone app: engine deployment, session orchestration, settings and UI |
| `:app-server` | Control-center app: decoder, mirroring, connection state machine |
| `:app-desktop` | Windows desktop receiver (JavaCV/FFmpeg) |

## 2. Evidence and verification discipline

- **No evidence, no fix**: every claim needs a `file:line` or a reproducible command + raw output. A finding without evidence is considered fabricated.
- **The conclusion is what is on disk**: `git status`, the existence of files and the raw build/test output must agree. "I already fixed it" does not count.
- **Before fixing a red gate, establish the baseline**: the same check must fail on the base without the patch, with a failure signature consistent with the issue's symptom; "green only after the patch" is false green and does not go in.
- **A real field problem requires a field log**: without an `adb` device or a control-center diagnostic log, do not conclude by guessing — follow `docs/ISSUE_GUIDE.md` and mark `needs-repro`, or write why reproduction was not possible.

## 3. Change workflow (issue → plan → worktree → PR → review/validation)

Every change in the repository (code, docs, configuration, scripts) follows five steps; missing one is non-compliance:

1. **Open the issue first**: follow [docs/ISSUE_GUIDE.md](docs/ISSUE_GUIDE.md) with the four elements (symptom / evidence chain / impact area / fix candidates). The issue is the only basis for delegating and accepting work; a behavior issue must include **a reproduction command pasteable into PowerShell** — if it cannot, mark `needs-repro`.
2. **Comment the plan on the issue**: investigation conclusions (with `file:line`), the solution, what explicitly will not be done, and how to validate, including a line "reproduced with the issue command: success / failure (raw output)". If reproduction fails, the issue does not match the current state: return it to the issue author and do not implement.
3. **Create a worktree**: `git worktree add .tmp/wt-<issue> -b <type>/<issue>-<slug> develop`, without switching branches in the main directory; branch, PR title and commit message carry the issue number.
4. **Open the PR**: follow [docs/PR_GUIDE.md](docs/PR_GUIDE.md), with the complete sections. **Merge only with green CI and no conflicts.** Behavior PRs use `Refs #NN`; only documentation or pure refactoring uses `Closes #NN`.
5. **After the merge, review and validate**: the fixer checks item by item "reported vs actual" and marks `needs-live-verify` on a behavior issue; then the discoverer or a validation agent with fresh context repeats the reproduction command according to the template in [docs/PR_GUIDE.md](docs/PR_GUIDE.md), and **only closes the issue with validation approved** (the fixer does not validate their own fix). Open items become a new issue.
   - **The unit of role is the agent, not the GitHub account**: the agent who authored the PR and the agent who authored the issue/validator must be different; the same GitHub account (e.g. `ID-VerNe`) may represent different rounds of different agents, which does not constitute self-approval and does not require creating another reviewer account. The review record must state "which agent / which session", not judge independence by account.

## 4. Build and verification (PowerShell)

```powershell
# Local unit tests per module (choose the modules, avoid always running everything)
.\gradlew.bat :protocol-core:test :vd-server:test :app-server:test

# Minimum gate aligned with CI
.\gradlew.bat :app-client:assembleDebug :app-client:testDebugUnitTest :protocol-core:test
```

`.github/workflows/build.yml` runs on PRs exactly `:app-client:assembleDebug :app-client:testDebugUnitTest :protocol-core:test`; tests for the other modules must run on the machine. The PR "Tests" section must include the raw output (not "passed").

## 5. Field collection

- Engine log on the phone: `adb pull /sdcard/DiLinkAuto/vd-server.log`. Look at `tex= / cb= / swapFails=` in `Pipeline:` lines and whether `Write timed out` appears.
- Control-center log: in release builds the diagnostic log is off by default (`CarLogWriter.setEnabled(BuildConfig.DEBUG)`), it must be enabled in settings; look at `Fed ... drops= inputFails= queue=` and the `Rendered` counter in `[VideoDecoder]`.
- Desktop log: `%APPDATA%\DiLinkAuto\desktop.log`, one line per second with `decoded fps / total / rebuilds / recv / cfg / hw`.
- Logs and screenshots are evidence: do not edit the original text before pasting; for a long log, excerpt the key lines and provide the file path.

## 6. Documentation and plan directories

| Directory | Use |
|---|---|
| `docs/` | Architecture/protocol/apps and process guides for users and maintainers |
| `.plan/` | Internal plans, audit reports, implementation plans (the conclusion goes back to `docs/`) |
| `.handoffs/` | Session handoff records |
| `.agents/teamwork/` | Delegation, briefings and multi-agent collaboration records |

Prefer writing new plans in `.plan/` and move stable conclusions into `docs/`; do not turn an issue into meeting minutes.

## 7. Publishing and signing (details in [docs/release.md](docs/release.md))

### 7.1 Facts

| Item | Value / rule |
|---|---|
| Single source of the version | `app.versionName` in `gradle.properties`, semver (`0.19.0` or `0.19.1-dev-3`); `app.versionCode` is manual and monotonic (currently `26111724`) |
| Official signing certificate | `CN=DiLink-Auto`, SHA-256 `e91b0551d832aa08af964398f644b002f9669031e3426e47075889bb6cdfd726` |
| Keystore location | Publisher's machine: `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks`; in CI, the `RELEASE_KEYSTORE_BASE64` secret is decoded into `$RUNNER_TEMP` |
| Variables | `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` mandatory; `RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` optional |
| Trigger | semver tag `vX.Y.Z` → `Build (Release)`; tag `vX.Y.Z-dev-N` → `Build (Pre-Release)` |
| Runner | Both release pipelines use `ubuntu-latest`; the repository **does not have** a self-hosted runner |

### 7.2 Publishing steps (PowerShell)

```powershell
# 1. app.versionName / app.versionCode already adjusted and merged into main; clean tree
git status --porcelain
# 2. Create the tag (use the merge commit, not the branch name; the tag must be v + app.versionName)
git fetch origin main
git tag v0.19.0 origin/main
git push origin v0.19.0
# 3. Follow the pipeline
gh run list -R andersonlucasg3/dilink-auto-android --limit 3
# 4. Download the assets and validate the signature (commands in docs/release.md §3)
```

### 7.3 Pitfalls that must not be forgotten (full version in `docs/release.md` §5)

- Without a keystore, the release variant **fails at the configuration phase** (`app-client/build.gradle.kts`); it does not produce an unsigned package.
- With `RELEASE_*` set, the debug variant also receives the official signature; the pre-release pipeline also needs a keystore.
- Untracked directories such as `.tmp/` make `BUILD_REVISION` (`build.gradle.kts`) gain `-dirty`; `/.tmp/` is already in `.gitignore`.
- After the official signature, **an old package cannot be installed on top**: uninstall it on the phone and on the control center.
- `app-client`'s `embedServerApk` embeds the control-center package according to the client variant (issue #37): a release client embeds the officially signed control center, a debug client embeds the debug package; `build-release.yml` has the certificate digest comparison gate — do not remove it.
- `buildVdServer` must depend on `:protocol:bundleLibRuntimeToJarDebug` — without this, a release-only build fails in D8 because it cannot find the protocol's debug `classes.jar`.
- `environment: release` exists but **has no** protection rule (no required reviewers): a pushed tag publishes directly, do not assume approval.
- In the GitHub tag filter, `+` means "the previous character one or more times", not the regex `+`.
