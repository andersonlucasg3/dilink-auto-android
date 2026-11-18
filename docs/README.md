# DiLink-Auto Documentation

Entry point for the developer documentation. The user-facing introduction is the [README](../README.md) at the repository root.

This repository is the original DiLink-Auto repository; in November 2026 it merged back the fork that kept the project alive, and it targets **Chinese phone ROMs + BYD DiLink head units**, tuned for the **entry-level DiLink 4.0** (Snapdragon 439 / Android 9 / 1280x800) on a BYD Qin PLUS DM-i 2023 Champion 55KM Leading.

**Languages:** canonical documentation is kept in **English**, with translations under `docs/<language>/`. README translations exist for pt-BR, zh-CN, fr, ru, be, kk, uk and uz; the deep-dive guides are currently complete in English and pt-BR.

**The repository change process (issue → plan comment → worktree → PR → reconciliation/review) is mandatory**; the overview is in the root [AGENTS.md](../AGENTS.md), and the operational detail is in the Issue Guide and PR Guide below.

### Translation coverage

| Language | README | Deep-dive guides | Process guides |
|---|---|---|---|
| English (canonical) | `README.md` | all | all |
| Português (BR) | [pt-BR](./pt-BR/README.md) | setup, architecture, client, server, protocol, progress | issue, PR, release |
| 简体中文 | [zh-CN](./zh-CN/README.md) | testing report, dual-mode proposal, both E2E documents | issue, PR, release |
| Français · Русский · Беларуская · Қазақша · Українська · Oʻzbekcha | `README.md` only | — | — |

The **GitHub Pages site** ([`index.html`](./index.html), assets in [`css/`](./css/style.css) and [`js/`](./js/i18n.js)) is the same documentation served with a language switcher and live Release badges; it is generated from static files, so it must be updated by hand when the facts below change.

## Documentation index

| Document | Audience | Content |
|----------|----------|---------|
| [Setup Guide](./setup.md) | User | Installation steps, permission onboarding, troubleshooting, entry-level DiLink 4.0 notes |
| [Architecture](./architecture.md) | Developer | Module design, direct VD architecture, connection flow, design decisions |
| [Protocol Specification](./protocol.md) | Developer | Wire format, message types (`VD_PORTS_BOUND`, `dpiOverride`), port allocation |
| [Client (Phone) App](./client.md) | Developer | ConnectionService orchestration, VD deployment, car app self-update, app allowlist |
| [Server (Car) App](./server.md) | Developer | State machine, SurfaceView decoder, three-button nav bar, DPI override |
| [Progress Tracker](./progress.md) | Contributor | Feature status, milestones, evolution after the fork |
| [Test coverage implementation report](./IMPLEMENTATION_REPORT_TESTING.md) | Contributor | Test coverage across all 6 modules, locked cross-module invariants, implemented testability seams, open risks |
| [Feature proposal: dual-mode receiver](./FEATURE_DUAL_MODE_RECEIVER.md) | Developer | Receiver "mode" button (car reverse control / proxy forwarding); design and testable core done, wiring and live verification pending |
| [Issue Guide](./ISSUE_GUIDE.md) | Contributor / Agent | Issue norms: `type(scope)` title, four required elements, pasteable reproduction command, `needs-repro` and sign-off |
| [PR Guide](./PR_GUIDE.md) | Contributor / Agent | PR norms: `Closes` / `Refs`, worktree branches and naming, sections, baseline verification, merge conditions, reconciliation and sign-off |
| [Release Guide](./release.md) | Maintainer / Agent | Official signing keystore and secrets, version and tag rules, publishing steps, `apksigner` verification, pitfalls and emergency procedures |
| [Two-phone E2E runbook](./e2e-dual-device-runbook.md) | Contributor | Real-device two-phone end-to-end runbook |
| [Two-phone E2E scenarios](./e2e-scenarios.md) | Contributor | Scenario matrix and exploration strategy for the two-phone end-to-end suite |

> The Windows receiver (`app-desktop/`) has no dedicated document yet: module notes, transport and packaging commands are in the "Windows receiver" section of the root [README](../README.md), and the transport and design decisions are in [architecture.md](./architecture.md).

## Differences from the pre-merge upstream

The main technical direction of this repository relative to the last upstream state (see each document for details):

- **Direct VD architecture:** the VD server binds 9638 (video), 9639 (input) and 9640 (audio) and talks to the receiver directly; the phone relays nothing.
- **Complete audio mirroring loop:** dedicated 9640 TCP connection, AAC-LC 48 kHz stereo at 128 kbps, constant silence padding at 46.875 fps with statistics, 150 ms jitter buffer on the car, two-way `FEATURE_AUDIO` capability negotiation and an anti-false-green contract.
- **Replaceable receiver:** the same protocol runs on the car (`app-server`) or on Windows (`app-desktop`); the pure-JVM part lives in `protocol-core` and is shared by all three.
- **Entry-level DiLink 4.0 tuning:** encode size capped at 1920x1080, 4 Mbps, 20 fps, TextureView → SurfaceView, thread priorities, icon cache cleanup, and more.
- **API 28 head-unit compatibility:** the API 29+ hardware-decoder picker was removed and `minSdk` raised to 26.
- **Nav bar redesign:** Eject / Home / Back; notification forwarding, recents bar, clock and network info were removed.
- **App allowlist and app pinning:** the phone chooses which apps reach the car; the car long-presses to pin.
- **Car-side startup DPI override:** fixes the too-small default DPI on 1280x800.
- **Sustained black-screen self-healing:** the detector lives in `protocol-core` (`BlackScreenDetector`); the car re-handshakes and the desktop reconnects (with a retry cap).
- **Unit tests in every module:** all 6 modules have a `src/test` source set (previously `vd-server` and `protocol` had zero coverage), JUnit4 + kotlinx-coroutines-test, 1,200+ `@Test` cases across 100+ test files; the inventory and locked invariants are in [IMPLEMENTATION_REPORT_TESTING.md](./IMPLEMENTATION_REPORT_TESTING.md).
- **Security hardening:** the protocol no longer trusts peers unconditionally (malformed frames no longer kill the process, payload cap 128 MB → 16 MB), VD channel authorization, `VdDeploy` shell quoting, desktop deploy argument validation, release keystore moved out of the repository.
- **Simplified Chinese UI:** `values-zh-rCN` / `values-zh` for both client and server.

## Features removed relative to the pre-merge upstream

- Notification forwarding (phone notifications → car screen): nav-bar notification button, notification list, `NotificationService`, and the `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` protocol messages are all gone.
- Recents bar, clock, network info.

## Not implemented

- Media controls, navigation widgets. (Audio streaming mirroring is complete since 1.0.3.)

## Known risks (found by tests/audits, not fixed)

Each item is pinned by a characterization test (changing them is a product decision), and code and report agree:

| ID | Location | Symptom |
|----|----------|---------|
| C-5 | `app-client` `Versioning` / `CarAppInstaller` | `readInstalledVersion` returns `"0"` when the car app is not installed; if the embedded `versionName` is also `0` / `0.0` / `0.0.0`, the comparison says "already up to date" and **skips the first install**. The current `0.19.0` does not trigger it |
| C-6 | `app-client` `InstallStatus.parse` | The status string is localized via `context.getString`, but classification matches English substrings; Chinese and other non-English locales all fall into `IDLE` (no progress, no error), and `"not reachable"` / `"Invalid IP"` are not classified as ERROR. A structured status enum is the suggested fix |
| S-02 | `vd-server` `CarCommandRouter.dispatch` | `LAUNCH_APP` / `APP_UNINSTALL` decode has no guard (package names go through `shellQuote` plus a decoder shape check), so a malicious package name can still make the read thread throw; the `APP_INFO` branch has a try/catch |
| B-05 | `app-client` `embedServerApk` (`app-client/build.gradle.kts`) | It used to embed `app-server-debug.apk` unconditionally, which breaks car self-update signature matching once official signing is used. **Fixed (issue #37):** the embedded car package follows the client variant (release/debug), and `build-release.yml` has a certificate digest comparison gate |
| — | Decoder restart race | Occasional visual glitches, recovered by the next keyframe (~1 s); see "Known limitations" in the root README |

## Download

Code versions map one-to-one to Release tags; see [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest). Current code version: `app.versionName = 0.19.0` (`versionCode 26111724`, see [Release Guide](./release.md) §1).
