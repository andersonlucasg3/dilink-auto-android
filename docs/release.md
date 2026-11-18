# Publishing and signing (Release Guide)

> For maintainers and agents. This document records DiLink-Auto's **official signing** and **release chain**, plus the pitfalls found while setting up this base.
> Related: repository workflow in [AGENTS.md](../AGENTS.md) §3, PR rules in [PR_GUIDE.md](./PR_GUIDE.md).

## 0. Summary in one sentence

- Release packages are signed with the `dilink-auto-release` keystore (certificate `CN=DiLink-Auto`); the keystore **does not enter the repository** and exists only in the publisher's machine backup and in the GitHub Actions secrets (`RELEASE_KEYSTORE_BASE64`).
- Publishing = bump `app.versionName`/`app.versionCode` on `main`, create the semver tag `vX.Y.Z`, push to origin → `Build (Release)` compiles on a GitHub-hosted runner (`ubuntu-latest`) and creates the GitHub Release.
- When the signing certificate changes, **new and old packages do not install on top of each other**: you must uninstall (phone and control center).
- Packages from the fork `ID-VerNe/dilink-auto-android` (1.0.x) use **another** certificate (`84417b25…36a`); to migrate from them to this repository's packages, uninstall first.

## 1. Version and tag rules

The repository uses **semantic versioning** (`MAJOR.MINOR.PATCH`), as in the historical tags `v0.17.1` and `v0.19.0-dev-N`.

| Item | Location / rule |
|---|---|
| Single source of the version | `gradle.properties`: `app.versionName` (`0.19.0`) and `app.versionCode` (integer, monotonic; currently `26111724`) |
| `versionCode` | Manual, **always increasing** — `pm install -r` refuses a downgrade. Bump it together with the version. The counter continues from the highest code ever published (`26111723`, produced by the fork-era date scheme) instead of restarting from the pre-fork `60`; see pitfall 13 |
| Release tag | `vX.Y.Z` (e.g. `v0.19.0`), triggers `Build (Release)` |
| Pre-release tag | `vX.Y.Z-dev-N` (e.g. `v0.19.1-dev-3`), triggers `Build (Pre-Release)` |
| Build string | `BUILD_REVISION` is computed in `build.gradle.kts` with `git rev-parse --short HEAD` + `git status --porcelain`; an **untracked file** already leaves the string with `-dirty` |

The build validates the version format: `app.versionName` must match `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-dev[-N]` (a bare `-dev` means dev number 0), and `app.versionCode` must exist. The version is computed once at the build root and shared by `app-client` / `app-server` / `vd-server` (via `rootProject.extra`), so the three never diverge in the same build.

`app-client`'s `Versioning` still understands the old `YYYYMMDD-HHMM` format (inherited from the fork) to compare old package versions; it is **no longer** used to generate the version. See `docs/client.md`.

The current code version is at the top of [README.md](../README.md). Historical tags are read-only — **never force-push an already published tag**; fix it with a new version.

Known exception: the `v1.0.1` asset was built **before** official signing (signed with debug). The tag is not recreated; assets with the official signature start at `v1.0.2` (from the fork). The first release of this repository with the official chain is `v0.19.0`.

## 2. Official keystore

| Item | Value |
|---|---|
| Local path | `%USERPROFILE%\.dilink-auto\dilink-auto-release.jks` (**outside the repository**; an old copy with the same name still exists in the fork's git history — the 2026-11 merge removed it from the tree, but the history itself has not been rewritten) |
| Type / alias | PKCS12 / `dilinkauto` |
| Key | RSA 2048 (SHA384withRSA), valid until 2051-04-20 |
| Certificate DN | `CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR` |
| Certificate SHA-256 | `e91b0551d832aa08af964398f644b002f9669031e3426e47075889bb6cdfd726` |

> The SHA-256 is the single verification criterion: if the Release asset does not match this fingerprint, publishing failed.

### 2.1 Generation (only when changing the certificate; afterwards it does not change)

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks" `
  -storetype PKCS12 -alias dilinkauto -keyalg RSA -keysize 2048 -validity 9125 `
  -dname "CN=DiLink-Auto, OU=Dev, O=DiLink-Auto, L=Sao Paulo, ST=SP, C=BR"
```

Store the password; do not write it in a commit, issue, PR or chat.

### 2.2 Injection into the local build

Put the four variables in `%USERPROFILE%\.dilink-auto\release-signing.env` (one `KEY=VAL` per line) and load them into the PowerShell session:

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
$env:RELEASE_KEYSTORE_PASSWORD; $env:RELEASE_KEY_ALIAS   # confirm that they loaded
```

Variable semantics (read in `app-client/build.gradle.kts` and `app-server/build.gradle.kts`):

| Variable | Mandatory | Description |
|---|---|---|
| `RELEASE_KEYSTORE_PASSWORD` | yes | Without it (and without an explicit override) → `GradleException` at the **configuration phase** |
| `RELEASE_KEY_PASSWORD` | yes | Same |
| `RELEASE_KEYSTORE_FILE` | no | If absent, uses the default relative path `app-client/dilink-auto-release.keystore` (a file that is not in the repository; in CI it is mandatory to set it) |
| `RELEASE_KEY_ALIAS` | no | Default `dilinkauto` |
| `ALLOW_DEBUG_SIGNED_RELEASE=1` | no | Local emergency only: allows signing the release variant with the debug key; **do not use** on a published package |

### 2.3 GitHub Actions secrets

`Build (Release)` and `Build (Pre-Release)` need all four (exact upper/lower case):

```
RELEASE_KEYSTORE_BASE64      # jks in one-line base64, decoded into $RUNNER_TEMP
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

How to generate `RELEASE_KEYSTORE_BASE64` (PowerShell):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.dilink-auto\dilink-auto-release.jks")) |
  Set-Content -NoNewline "$env:USERPROFILE\.dilink-auto\keystore.b64" -Encoding ascii

$env:NO_PROXY=""
gh secret set RELEASE_KEYSTORE_BASE64 -R andersonlucasg3/dilink-auto-android < "$env:USERPROFILE\.dilink-auto\keystore.b64"
```

## 3. Local build and verification

```powershell
. $env:USERPROFILE\.dilink-auto\use-signing.ps1
.\gradlew.bat :app-server:assembleRelease :app-client:assembleRelease
```

Verify the signature (**run before every publication**):

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$sdk\apksigner.bat" verify --print-certs app-client\build\outputs\apk\release\app-client-release.apk |
  Select-String "certificate DN", "SHA-256"
& "$sdk\apksigner.bat" verify --print-certs app-server\build\outputs\apk\release\app-server-release.apk |
  Select-String "certificate DN", "SHA-256"
```

Both lines must show `CN=DiLink-Auto, …` and `e91b0551…d726`.

## 4. Publishing workflow (can be followed to the letter)

1. **Set the version**: change `app.versionName` (semver) and `app.versionCode` (monotonic) in `gradle.properties`. Sync the version references in `README.md`, `docs/README.md`, `docs/setup.md` and `docs/client.md`.
2. **Open a PR to `main`** (the five steps of AGENTS.md §3), with the `Build` CI green.
3. **Confirm `main` is clean**: `git status --porcelain` empty; otherwise the package version string gains `-dirty`.
4. **Create and push the tag** (the tag must be exactly `v` + `app.versionName`; the pipeline validates):
   ```powershell
   git fetch origin main
   git tag v0.19.0 <merge-commit>
   git push origin v0.19.0
   ```
5. **Follow the pipeline**: `gh run list -R andersonlucasg3/dilink-auto-android --limit 3` and `gh run watch <id>` until success.
6. **Accept the assets**: download `DiLinkAuto-Phone.apk` / `DiLinkAuto-Car.apk` from the Release and run the `apksigner` from section 3; the fingerprint must match. If it does not match, unpublish/revert.
7. **Paste the evidence in the issue**: run link + raw `apksigner` output.

## 5. Pitfall list (all actually encountered)

| # | Pitfall | Symptom / consequence | Handling |
|---|---|---|---|
| 1 | `runs-on: self-hosted` | The repository has no self-hosted runner, so tag publishing stays **queued forever**, looking like "I published and nothing happened" | Use `ubuntu-latest` in all three workflows (`build.yml` / `build-release.yml` / `build-pre-release.yml`) |
| 2 | Actions disabled | Without "I understand my workflows… enable them", push/PR has **no checks at all** and `gh pr checks` comes back empty | Enable on the Actions page; `actions/permissions.enabled=true` ≠ actions enabled |
| 3 | Building a release without a keystore | Without the password, the build throws `GradleException` **at the configuration phase**, instead of generating an unsigned package | In CI, the `Decode release keystore` step writes the file and sets `RELEASE_KEYSTORE_FILE`; if the secret is missing, the step does `exit 1` |
| 4 | Debug variant also receives the official signature | With `RELEASE_*` set, `app-client/build.gradle.kts` applies the release `signingConfig` to debug as well — **the pre-release pipeline also needs the keystore** | `build-pre-release.yml` also decodes the keystore before compiling |
| 5 | Untracked file dirtying the version | If `.tmp/` (worktree) is not ignored, `BUILD_REVISION` stays `-dirty` forever | `/.tmp/` is already in `.gitignore`; still check `git status` before publishing |
| 6 | Installing on top after a signature change | Old package installed → new package gives `INSTALL_FAILED_UPDATE_INCOMPATIBLE`/signature mismatch | On the first migration to the official signature, **uninstall the old one**; when leaving fork 1.0.x (another certificate), also uninstall |
| 7 | Embedded control-center APK variant | The old behavior always embedded `app-server-debug.apk` (audit B-05); with official signing, self-update would send a debug package and conflict with the already-signed control center | **Fixed (issue #37)**: `embedServerApk` chooses `app-server-release.apk` / `app-server-debug.apk` according to the client variant; the `Verify embedded car APK signature` step of `build-release.yml` compares the digest of the two artifacts and fails if they diverge |
| 8 | `environment: release` without a protection rule | The environment exists, but **has no** required reviewers; a pushed tag publishes directly | To have approval, configure reviewers in Settings → Environments; do not assume `environment:` creates a gate |
| 9 | `+` semantics in the GitHub tag filter | In the filter, `+` means "the previous character one or more times"; `v[0-9]+.[0-9]+.[0-9]+` matches `v1.0.2`, but it is not regex | Write tag rules with that semantics, not with regex instinct |
| 10 | Old tag assets do not update | After changing the build configuration, the Release of an old tag still has the old package | Publish a new version; do **not** delete and re-push the tag |
| 11 | PowerShell reading `local.properties` | The `sdk.dir` value comes with `C\:\\…` escaping and breaks `Get-ChildItem -Directory` | Build the path with `$env:LOCALAPPDATA\Android\Sdk` or use `-LiteralPath` |
| 12 | `gh` failing due to proxy | The local proxy makes `gh` hang | Before calling: `$env:NO_PROXY=""` |
| 13 | `versionCode` continuity after the fork merge | Build `v20261117-2315` (date scheme, discarded) shipped `versionCode` 26111723. Restarting the semver counter at the pre-fork `60` would be a **downgrade** and `pm install -r` would refuse it | **Handled**: the semver release keeps its semantic name but the code continues at `26111724`, so `v0.19.0` installs on top of that build. Do not "tidy" the number back to a small one — the install would then require an uninstall |
| 14 | Pre-release semver tag on the wrong pipeline | `v0.19.1-dev-3` does not trigger `Build (Release)` (the filter excludes `*dev*`), and `v0.19.0` does not trigger the pre-release one | Use the right format for the right channel; the release pipeline also rejects a tag with `-dev` |

## 6. Contingency

| Situation | What to do |
|---|---|
| Wrong tag pushed (version does not match) | Delete the remote tag and the corresponding Release and publish a new version with a bump; **an already published tag is not recreated** |
| Wrong asset signature | Mark the Release as draft / delete the assets, check that `Decode release keystore` used the right base64 and run the publication again |
| Secrets deleted | Check the four with `gh secret list -R andersonlucasg3/dilink-auto-android` and recreate whichever is missing (see 2.3) |
| Pipeline always queued | Check whether `runs-on` went back to `self-hosted` and whether the repository's Actions are enabled |
| User reports a signature conflict on update | Ask them to uninstall the old version and install again; do not try to "make compatible" another signature |
