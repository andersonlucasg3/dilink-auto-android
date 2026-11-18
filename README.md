# DiLink-Auto

Mirror your phone's apps onto your car's built-in screen, with full touch interaction. Open-source, no Google Services required.

This is the **original repository** of DiLink-Auto ([andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android)). In November 2026 it merged back the 252 commits of the long-running fork [ID-VerNe/dilink-auto-android](https://github.com/ID-VerNe/dilink-auto-android), which kept the project alive while the upstream was quiet. Development continues focused on **Chinese phone ROMs + BYD DiLink head units**, with dedicated tuning for the **entry-level DiLink 4.0** so that it goes from "slide show" to usable on weak hardware.

**Documentation / Documentação / 文档 / Документация / Documentation / Құжаттама / Документація / Hujjatlar**

GitHub does not automatically show documentation in your language. Pick one:

| Language | README | Setup | Architecture | Client | Server | Protocol | Progress |
|----------|--------|-------|--------------|--------|--------|----------|----------|
| English | **this page** | [Setup](./docs/setup.md) | [Arch](./docs/architecture.md) | [Client](./docs/client.md) | [Server](./docs/server.md) | [Proto](./docs/protocol.md) | [Progress](./docs/progress.md) |
| Português (BR) | [README](./docs/pt-BR/README.md) | [Setup](./docs/pt-BR/setup.md) | [Arch](./docs/pt-BR/architecture.md) | [Client](./docs/pt-BR/client.md) | [Server](./docs/pt-BR/server.md) | [Proto](./docs/pt-BR/protocol.md) | [Progress](./docs/pt-BR/progress.md) |
| 简体中文 | [README](./docs/zh-CN/README.md) | — | — | — | — | — | — |
| Français | [README](./docs/fr/README.md) | — | — | — | — | — | — |
| Русский | [README](./docs/ru/README.md) | — | — | — | — | — | — |
| Беларуская | [README](./docs/be/README.md) | — | — | — | — | — | — |
| Қазақша | [README](./docs/kk/README.md) | — | — | — | — | — | — |
| Українська | [README](./docs/uk/README.md) | — | — | — | — | — | — |
| Oʻzbekcha | [README](./docs/uz/README.md) | — | — | — | — | — | — |

A dash means those deep-dive guides are not translated yet; **English is canonical** and wins whenever a translated page diverges.

Process guides are also translated in two languages:

| Language | Issue guide | PR guide | Release guide |
|----------|-------------|----------|---------------|
| English | [Issue](./docs/ISSUE_GUIDE.md) | [PR](./docs/PR_GUIDE.md) | [Release](./docs/release.md) |
| Português (BR) | [Issue](./docs/pt-BR/ISSUE_GUIDE.md) | [PR](./docs/pt-BR/PR_GUIDE.md) | [Release](./docs/pt-BR/release.md) |
| 简体中文 | [Issue](./docs/zh-CN/ISSUE_GUIDE.md) | [PR](./docs/zh-CN/PR_GUIDE.md) | [Release](./docs/zh-CN/release.md) |

Simplified Chinese additionally has the test-coverage report ([IMPLEMENTATION_REPORT_TESTING](./docs/zh-CN/IMPLEMENTATION_REPORT_TESTING.md)), the dual-mode receiver proposal ([FEATURE_DUAL_MODE_RECEIVER](./docs/zh-CN/FEATURE_DUAL_MODE_RECEIVER.md)) and both two-phone E2E documents ([runbook](./docs/zh-CN/e2e-dual-device-runbook.md), [scenarios](./docs/zh-CN/e2e-scenarios.md)).

Current version: **0.19.0** (`versionCode 26111724`). Downloads: [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest).

Versioning follows this repository's semver scheme: `app.versionName` is `MAJOR.MINOR.PATCH` (pre-releases add `-dev-N`) and `app.versionCode` is a hand-maintained monotonic integer. Tags are `v0.19.0` (release) and `v0.19.1-dev-3` (pre-release).

The problem it originally solved: phones with Chinese ROMs (Xiaomi HyperOS, etc.) cannot install Android Auto, and the head unit only speaks Android Auto — nothing bridges the gap. DiLink-Auto works with any Android 10+ phone, with or without Google Services.

---

## Tested hardware

**BYD Qin PLUS DM-i 2023, Champion 55KM Leading trim.** Its head unit is the entry-level DiLink 4.0 — Snapdragon 439 (8x Cortex-A53, all little cores), Adreno 505, 4 GB RAM, 16 GB eMMC, 1280x800 screen, 2.4 GHz Wi-Fi only, Android 9 / API 28, H.264 hardware decoding capped at 1080p. All performance tuning was done against this hardware.

---

## What changed since v0.18.0-dev

The short version of the evolution between the fork point (`84df5cb`, upstream/main) and the current `main`. Each item corresponds to real commits, not plans; the detailed chronology lives in [docs/progress.md](./docs/progress.md).

### 1. Direct VD streaming architecture

The phone no longer relays video, touch or audio. The VD server binds `9638` (video), `9639` (input) and `9640` (audio, opened after a two-way negotiation) and talks to the receiver directly. That removes 4 sockets + 2 process context switches per frame, leaving 2 sockets + 0 context switches. The phone becomes a pure orchestrator: handshake and capability negotiation, VD lifecycle (`VD_PORTS_BOUND`), and head-unit log routing. The protocol gained `FrameCodec.readFrameBlocking()`, `MessageType.VD_PORTS_BOUND` and `AudioPayload`, and `VirtualDisplayClient` was reduced to lifecycle only.

### 2. Entry-level DiLink 4.0 tuning (core work)

Against the hardware above:

- **Encode size capped at 1920x1080.** The phone used to upscale the VD to physical pixels (1080x2340 → encode 2340x1658) to avoid IME clipping; that exceeded the 439's hardware decode limit, fell back to software decoding and 8x A53 ran H.264 above 1080p at single-digit fps. Fixed by decoupling VD size from encode size — the VD stays upscaled (keeping the IME fix), the encode size follows the car-reported viewport and is clamped to 1920x1080.
- **Bitrate 8 Mbps → 4 Mbps.** Sustained throughput inside a 2.4 GHz car environment measured 5-15 Mbps, so 8 Mbps left no headroom. The adaptive fallback was tightened as well: floor 1.5 Mbps, 2 s recovery window, 0.5 Mbps steps (it used to oscillate between 8 and 2 Mbps).
- **Frame rate 30 → 24 → 20.** The default is now 20 fps (50 ms per frame). See issue #71: 2.5 h of navigation left the phone hot, and lowering the frame rate is the second pressure-release valve next to the VD upscale cap.
- **TextureView → SurfaceView.** Skips Adreno 505's per-frame full-screen GL composition (about 2-5 ms/frame). An `outputSurfaceValid` gate prevents rendering into a destroyed surface when the decoder is hidden/shown across navigation.
- **Thread priorities.** A53 has no big cores, so scheduling hints matter: encoder/decoder/socket-drain threads use `THREAD_PRIORITY_URGENT_DISPLAY`, `LifeWriter` uses `THREAD_PRIORITY_BACKGROUND`.
- **Logging defaults.** In release builds `carLogEnabled` defaults to off (it used to be on, and log frames competed with video for the 2.4 GHz link); `ConcurrentLinkedQueue.size()` (O(n)) became an `AtomicInteger`.
- **AppIconCache cleanup on disconnect.** ~80-150 PNGs used to accumulate forever on the 16 GB eMMC (5-9 MB of heap). A new `clear()` runs from `handleDisconnect`, and `prepareAll` decodes in parallel with a `Semaphore(4)`, recycling intermediate bitmaps.
- **`Debug.getPss()` removed.** That deprecated binder call ran on every connection, costing 100-500 ms on A53; it is now collected only on crash.
- **`@Immutable AppTileData`.** `AppInfo` carries a `ByteArray`, which made `AppTile` non-skippable and forced full recomposition on every `appList` reassignment. An immutable wrapper that excludes `iconPng` lets the grid skip recomposition.

### 3. API 28 head-unit compatibility

On the real BYD unit (`k65v1_64_bsp`, Android 9 / API 28), `MediaCodecInfo.isHardwareAccelerated()` (API 29+) throws `NoSuchMethodError` and kills the process. The speculative hardware-decoder picker was removed and `createDecoderByType` restored (`REGULAR_CODECS` already ranks hardware decoders first; on BYD it picks `OMX.qcom.video.decoder.avc`). `minSdk` went 24 → 26 for `app-server` and `protocol`, and the NewApi lint gate was re-enabled. `am display move-stack` (API 29+) is now gated on `SDK_INT` for API 26-28 and logs instead of treating a shell failure as success.

### 4. UI and interaction

- **Nav bar redesign:** reduced to Eject (disconnect) / Home / Back. Notification button, recents bar, clock and network info were removed, along with dead code across four modules (car-side `NotificationScreen`, `RecentAppsState`; phone-side `NotificationService`; `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` message types). **Notification forwarding does not exist in this codebase.**
- **App pinning:** long-press an app icon for Pin to Top / Unpin, persisted in SharedPreferences.
- **App allowlist:** a new `AllowlistScreen` lets the user choose which apps are pushed to the car; unselected apps are filtered at `sendAppList`, shrinking the wire payload and the car's icon-decoding work. Common navigation apps are pre-selected on first run. Toggling sends `ACTION_ALLOWLIST_UPDATED` so a running service re-sends the list and the car grid updates live.
- **Portrait app support:** dynamic DPI calculation (`VideoConfig.calculateOptimalDpi`) keeps portrait apps (Amap, WeChat) from being squeezed into a thin strip inside the landscape VD; the IME is restored on session end (the phone used to keep the linkpc IME); multi-touch injection index fixed.
- **Car-side startup DPI override:** the handshake gained a `dpiOverride` field (0 = automatic, [120, 480] = user value), driven by the car's `startup_dpi` setting and sent on both orientations. Fixes the too-small default DPI on 1280x800.
- **Color tokens and type flooring:** scattered `Color.Gray` / `0xFF888888` moved to `onSurfaceVariant`, duplicate error reds merged, labels 12sp → 14sp. "WiFi Direct Mode" renamed to "WiFi ADB Mode"; the car-side `adb tcpip` text was removed.
- **Rotation black-screen fix:** `onCarViewportChanged` sets state to CONNECTING and re-deploys the VD on rotation, but the streaming layout gate used `isConnected` (false for the ~2 s of re-deploy), so `CarShell` fell back to `CarLaunchScreen`, the `TextureView` left the composition and the new surface could not restart the decoder. The gate now also accepts `state == CONNECTING && appList.isNotEmpty()`, keeping the streaming layout (and its video-wait overlay) in place during rotation.

### 5. Session stability

An active session is never killed by a reconnect attempt — previously a Wi-Fi gateway retry or a manual connection could kill a running session. The reconnect loop stops after 3 consecutive ADB failures. TCP ADB reconnects when the phone IP changes, and falls back to TCP ADB automatically when USB is unavailable.

### 6. PipelineServer stabilization

`VirtualDisplayServer` (1287 lines, 9 threads, 2 queues) became `PipelineServer` (single thread, 0 queues): frame clock → GL render → encoder drain → TCP write, with natural flow control (a stuck TCP write blocks the next `eglSwapBuffers`). It also fixed: a GL texture created in a temporary EGL context and then destroyed (the pipeline thread bound texture 0); `app_process` being killed when the non-interactive shell exited (fixed with `shellBackground`, which keeps the shell stream attached); and the physical-panel restore order (it used to call `input keyevent 224` after the shell had already closed).

### 7. Diagnostic log switch

Settings → Debug gained a diagnostic log switch. When off there are zero disk writes. The state is propagated to the car via the `LOG_TOGGLE` data message and, since issue #72, also synced automatically after every handshake so the car log follows the phone switch instead of being silently reset on reconnect. Defaults: on for debug/pre-release builds, off for release; an explicit user choice persists.

### 8. Simplified Chinese UI

Both client and server ship `values-zh-rCN` / `values-zh`. App strings exist for English, Portuguese (BR), French, Russian, Belarusian, Kazakh, Ukrainian, Uzbek and Chinese.

### 9. Code health

Several rounds of SRP/DRY audits and refactors: shared constants across modules (`AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp`, `AppIconLimits`) and extracted collaborators.

- **`protocol-core` / `protocol`:** `NioWriter` (socket-channel spin write with timeout), `BackoffPolicy` (parameterized exponential backoff), `DecodeLivenessTracker`, `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator`, `AppIconLimits`; dead `UsbAdbConnection` code removed.
- **`vd-server`:** `AsyncSocketSender`, `AudioEncoderSelector`, `ProcessWatchdog`, `VideoEncoderSelector`, `PortAcceptor`, `ActivityDumpParser`, `VdDisplayHealer`.
- **`app-client`:** `ConnectionService` slimmed down; `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository`, `SessionState` extracted; `InputInjectionService` deprecated.
- **`app-server`:** `CarConnectionService` down to ~407 lines; the state machine split into `CarSessionCoordinator` (9637/9638/9639/9640 multi-channel state machine and re-handshake), `CarAdbTracker` (USB/TCP ADB tracking) and `CarAppListReceiver` (app list and icon safety limits).
- **`app-desktop`:** consumes `AppIconLimits`, `DecodeLivenessTracker`, `BackoffPolicy`, aligned with the latest protocol primitives.

### 10. VD leak fix, idempotent cleanup, black-screen self-healing

**Root cause:** every reconnect leaked one VirtualDisplay. The old code used `pkill -9`, which skips the JVM shutdown hook, so `PipelineServer.cleanup()` never ran — the VD was not released, the physical panel stayed off, and the `screen_off_timeout` setting was lost (the snapshot captured the sentinel `2147483647` the code itself had written). A few reconnects later the car screen went black.

**Fix (12 files):** a stop-request sentinel (`/sdcard/DiLinkAuto/stop-request`) that the engine watchdog consumes so it exits through the normal path and runs the full `cleanup()` (ART does not run shutdown hooks on a bare SIGTERM); a `pgrep -f` based probe (toybox rejects `pkill -0`); a `kill -9` fallback that locates the pid with `pgrep -f` instead of `pkill -f` (the latter also kills the wrapper shell and makes the ADB result unreadable); an `AtomicBoolean` `cleanupGuard` so the 7 call sites do not run `cleanupSession()` repeatedly (one disconnect used to trigger 9 "Force-waking physical display" calls); `waitForVdServerExit()` on both sides so two engines never fight over the same VD and ports; `PhoneDisplayRestorer` on its own process-lifetime scope with `NonCancellable`; a reordered `cleanup()`; `DisplayPowerController.restoreSetting()` value validation; and `BlackScreenDetector` (`BLACK_SCREEN_SUSTAIN_MS = 3000`) moved into `protocol-core` so car and desktop share it, with `rehandshakeForBlackScreen()` latched to at most one attempt per session.

Regression automation lives in `scripts/`: `verify-blackscreen-fix.sh`, `e2e-reconnect-cleanup.sh`, `verify-reconnect-cleanup.sh` (32 checks driven through the real desktop UI; last full run 32/32, 2m48s).

### 11. Windows receiver `:app-desktop`

Besides the head unit, the same protocol supports a **Windows desktop program** as the receiver: the phone screen mirrors into a PC window and the mouse becomes touch. The module lives in `app-desktop/` (Kotlin/JVM + Compose Desktop 1.6.2, JDK 17) and reuses `protocol-core` — no phone-side changes.

- **Transport:** three TCP connections straight to the phone — 9637 (control + data), 9638 (video), 9639 (input) — same as the car, no relay.
- **Deployment split:** `connectionMethod` in the handshake response decides who deploys the VD server. With Shizuku the phone does it; otherwise the desktop deploys with the local `adb.exe` (`connect` → kill the old engine **and wait for it to really exit** → `exec app_process`), which requires `"dev_mode": true` in `config.json`.
- **Decoding:** JavaCV/FFmpeg software decoding plus D3D11VA hardware decoding, with automatic fallback when hardware fails at startup or produces zero frames.
- **UI:** three nav pages — Mirror / Apps (launcher) / Display (DPI override, hardware decode toggle, "Apply and reconnect", keep-awake, phone physical screen).
- **Self-healing:** 10 s of sustained black screen reconnects automatically, at most twice per process.
- **Config and logs:** `%APPDATA%\DiLinkAuto` (`config.json` / `desktop.log`), overridable via `DILINK_DESKTOP_HOME`; `DILINK_ADB` sets the adb path.

```bash
# Self-contained app-image (bundled JRE): copy build/compose/binaries/main/app/DiLinkAuto
./gradlew :app-desktop:createDistributable

# Headless probe: validates handshake/VD/fps only; without Shizuku set DILINK_DEV_MODE=1 for ADB deploy
app-desktop --probe
```

> Status: the Shizuku path matches the car side; the **ADB deployment path has not been verified on a real device** yet. The module is not part of the release assets (its JavaCV/FFmpeg dependency is GPL, while this project is MIT).

### 12. Unit tests and security hardening

**Test coverage:** two modules had no test source set at all (`vd-server` had no `src/test`, `protocol`'s was empty). Starting at `8157c86`, JUnit4 unit tests were added across all 6 modules, covering the riskiest previously untested logic (protocol framing, ADB crypto primitives, VD deploy args, adaptive bitrate, shell quoting gate, version gates, install-status classification, crash report format). A further round of behaviour-preserving "testability seams" added 44 more cases. Today the repository has 1,200+ `@Test` cases across 6 modules (JUnit4 + kotlinx-coroutines-test, no Robolectric/Mockito/MockK; Android modules use `isReturnDefaultValues=true` with hand-written fakes). The inventory, locked cross-module invariants and open issues are in [docs/IMPLEMENTATION_REPORT_TESTING.md](./docs/IMPLEMENTATION_REPORT_TESTING.md).

**Peer hardening (`a7d2b72`):** every socket peer used to be treated as trusted — a 6-byte malformed frame could kill the phone process or the shell-UID VD server. All five decoders now `require()`-check before raw `getX()`; reader coroutines catch `Throwable`, log the cause and disconnect instead of dying; `MAX_PAYLOAD_SIZE` went from 128 MB to 16 MB and `NioReader`'s buffer has a hard cap reclaimed at frame boundaries.

**VD channel authorization (`8601fb1`):** ports 9638/9639 used to accept any host on the same Wi-Fi, letting anyone inject touches and run `am start` / `pm uninstall` as shell. They now accept only connections from the receiver currently being served; rejected peers are closed and the server keeps waiting.

**Shell quoting (`5eae3e7`):** `VdDeploy` command lines go through `shellQuote`, so peer-controlled strings never reach `sh` bare.

**Desktop (`1f6836a`):** `adbPort` / `jarPath` from the handshake response are validated before use (they used to be passed straight into deployment, i.e. arbitrary code execution from any peer); adb child processes no longer leak.

**Car session (`bc2634c` / `35d7821`):** sessions use a generation latch so `cleanupGuard` is not consumed early by the network-change path; black-screen recovery no longer calls `stop()` on the decoder (nobody restarted it, so the recovery path guaranteed a permanent black screen).

**Release supply chain:** the release keystore used to be committed; it is now referenced only, with signing secrets in CI (git history still needs cleaning). CI no longer runs PR code on self-hosted runners (`9517205`) nor produces unsigned / over-stripped APKs and orphan artifacts (`efde81f`). All workflows run on GitHub-hosted `ubuntu-latest`.

---

## Current feature status

**Working:**

- H.264 video mirroring, 20 fps default, 4 Mbps CBR, Main profile (tuned for entry-level DiLink 4.0; works on higher-end units too)
- Audio mirroring: AAC-LC 48 kHz stereo at 128 kbps over a dedicated TCP connection (9640), 150 ms jitter buffer, constant silence padding at 46.875 fps, two-way `FEATURE_AUDIO` capability negotiation, persisted audio switch on the client
- Full touch input (multi-touch, pinch-to-zoom)
- App launcher: search, alphabetical order, 64dp icons, long-press menu (uninstall, app info, pin)
- App allowlist: only selected apps are pushed to the car
- Car-side startup DPI override (fixes the small default DPI on 1280x800)
- Orientation following: the VD is rebuilt when the car rotates, portrait apps are no longer squeezed into a strip
- Shizuku support: deploy the VD server without USB ADB and self-update the car app with a silent `pm install`
- Car app self-update: the phone compares `versionCode` and updates the car app over Wi-Fi ADB
- Phone screen off while streaming (saves battery); the physical panel is restored on disconnect
- Guided permission onboarding
- Simplified Chinese UI plus 8 other UI languages
- Direct VD architecture: no relay on the phone; video, touch and audio go straight through
- VD leak fix: sentinel-based graceful stop (full `cleanup()`, `kill -9` only as a timeout fallback), idempotent cleanup guard, VD exit waiting
- Black-screen self-healing: the car rebuilds the VD (re-handshake) after 3 s of sustained black screen; the Windows receiver uses the same detector with a 10 s window
- Windows receiver (`app-desktop`): mirror window, mouse touch, app launcher, DPI/hardware-decode panel, `jpackage` app-image; the adb deployment path is implemented but **not yet verified on a real device**

**Removed (relative to the pre-merge upstream):**

- Notification forwarding (phone notifications → car screen): nav-bar notification button, notification list and `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` protocol messages are gone
- Recents bar, clock, network info

**Not implemented:**

- Media controls
- Navigation widgets (audio mirroring is complete)

**Known limitations:**

- Samsung Auto Blocker must be disabled for USB ADB to work (Settings → Security → Auto Blocker → off).
- Samsung battery management needs an explicit exemption.
- Samsung Knox may show a security prompt the first time the virtual display is accessed.
- The phone hotspot must be enabled manually (Android 16 restriction).
- Occasional visual glitches from a decoder restart race; the next keyframe (~1 s) recovers.
- Streaming latency is roughly 100-200 ms under load.
- Some apps do not fill the screen (letterboxed / portrait-only). DiLink-Auto mirrors a landscape virtual display, so apps that do not support landscape show black bars — that is decided by the app.
- On API 26-28 units, if the `DisplayControl` reflection fails, the `cmd display power-reset` / `power-off` shell fallback is API 29+ too, so the physical panel may not come back (it logs instead of failing silently).
- Install progress is not shown on non-English UI languages: `InstallStatus.parse` classifies localized status strings by English substrings, so Chinese (and others) fall into `IDLE`. Pinned by tests, not fixed ([C-6](./docs/IMPLEMENTATION_REPORT_TESTING.md)).
- First install of the car app could be skipped with an extreme version number: `readInstalledVersion` returns `"0"` when the car app is absent, so an embedded `versionName` of `0` / `0.0` / `0.0.0` compares as "up to date". The current `versionName` is `0.19.0`, so it does not trigger ([C-5](./docs/IMPLEMENTATION_REPORT_TESTING.md)).

---

## How it works

1. **Enable the hotspot** — turn on the phone's Wi-Fi hotspot; the car connects to it.
2. **Plug in** — connect the phone to the car's USB port (or join the same Wi-Fi network).
3. **Auto-install** — the phone installs the car app over Wi-Fi ADB (first time only, one tap).
4. **Auto-connect** — four Wi-Fi TCP streams: control (9637), video (9638), touch (9639), audio (9640, opened after negotiation).
5. **Use your apps** — launch any app from the car launcher; it runs on the phone, appears on the car screen and receives touch.

The phone runs apps on a virtual display and encodes them to H.264, which is pushed to the car. Touch events from the car screen are injected back into the phone as real touch events. The phone's physical screen stays off to save battery and remains usable independently.

Architecture, connection flow and design decisions: [docs/architecture.md](./docs/architecture.md).

---

## Requirements

**Phone:**

- Android 10+ (`minSdk = 29`)
- USB debugging enabled (Developer options)
- All Files Access permission (guided on first launch)

**Car:**

- BYD DiLink 3.0+, verified on the **BYD Qin PLUS DM-i 2023 Champion 55KM Leading** (entry-level DiLink 4.0, Snapdragon 439 / Android 9 / 1280x800)
- Android 8.0+ (`minSdk = 26`)
- One free USB-A port (or the same Wi-Fi network)

**The phone hotspot must be on** — the car joins it. No pairing codes, no Google account.

**No internet connection is required.** DiLink-Auto streams locally over the phone hotspot; the phone and the car talk directly. Internet is only needed by the apps running on your phone.

---

## Install

Download the APK from [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest), or build it yourself:

1. **Build:** `./gradlew :app-client:assembleDebug`
2. **Install on the phone:** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **Enable USB debugging** (Settings → Developer options)
4. **Open DiLink-Auto** and follow the All Files Access onboarding
5. **Enable the hotspot and plug into the car** — the car app is installed automatically on first run over Wi-Fi ADB

The car APK and the VD server JAR are both packaged inside the phone APK, so nothing has to be installed on the car separately and installing the car app needs no internet.

> **Signing upgrade note:** switching from a debug-signed build to an officially signed one changes the certificate — **uninstall the old package first**, otherwise installation fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Full release and signing rules: [docs/release.md](./docs/release.md).

---

## Project structure

The phone APK (`app-client`) embeds the car APK (`app-server`) and the VD server JAR (`vd-server`). The receiver is replaceable: the same protocol runs on the car (`app-server`) or on Windows (`app-desktop`).

```
DiLink-Auto/
├── protocol-core/  Pure-JVM shared library (frames, messages, constants, VD deploy sequence; no Android dependency)
├── protocol/       Platform adaptation (ADB, Android hooks, USB/TCP connections)
├── app-client/     Phone APK — orchestration, VD deployment, car self-update, app allowlist
├── app-server/     Car APK — UI, connection state machine, video decoder
├── app-desktop/    Windows receiver — mirror window, FFmpeg decoding, adb deployment, jpackage
├── vd-server/      VirtualDisplay server (compiled to a JAR, deployed by the phone)
├── scripts/        Automation (log verification, end-to-end reconnect/cleanup)
├── docs/           Documentation (multilingual entry point)
└── gradle/         Build system
```

| Module | Role | minSdk |
|--------|------|--------|
| `protocol-core` | Pure-JVM shared protocol library (Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector) | — |
| `protocol` | Shared protocol library (UsbAdbConnection, AdbProtocol, VideoConfig, ConnectionFactory, ConnectionKeepAlive, UsbAdbKeyStore) | 26 |
| `app-client` | Phone app (ConnectionService, ShizukuVdDeployer, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository, FileLog) | 29 |
| `app-server` | Car app (CarSessionCoordinator, CarAdbTracker, CarAppListReceiver, AudioPlayer, VideoDecoder, CarShell, HomeScreen, PersistentNavBar) | 26 |
| `app-desktop` | Windows receiver (DesktopApp, DesktopConnectionService, VideoDecodePipeline, SwingVideoView) | — |
| `vd-server` | Shell-UID process (PipelineServer, AudioPipeline, AudioEncoderSelector, PortAcceptor, TouchInjector, GlPipeline, DisplayPowerController) | 29 |

---

## Build

```bash
# Build the phone APK (runs buildVdServer + embedServerApk, bundling vd-server.jar and app-server.apk into the client assets)
./gradlew :app-client:assembleDebug

# APK location:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (phone, embeds the car APK)

# Unit tests (6 modules, JUnit4 + kotlinx-coroutines-test, no Robolectric/Mockito/MockK)
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

Requires JDK 17 and the Android SDK (compileSdk 34). Release builds need `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` (`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` optional); a missing required variable fails at configuration time instead of silently producing an unsigned APK that cannot be installed over the official one. The keystore itself is never committed.

On Windows the equivalent commands use `.\gradlew.bat`.

---

## Documentation

Full documentation (setup, architecture, protocol, client, server, progress) starts at [docs/README.md](./docs/README.md). Process norms live in [AGENTS.md](./AGENTS.md), [docs/ISSUE_GUIDE.md](./docs/ISSUE_GUIDE.md), [docs/PR_GUIDE.md](./docs/PR_GUIDE.md) and [docs/release.md](./docs/release.md).

---

## Contributing

Pull requests are welcome. Development happens on `main` and `develop` (git-flow: features branch from `develop`, releases merge into `main` and are back-merged). Before opening a PR, make sure `./gradlew :app-client:assembleDebug` and the unit test commands above pass.

---

## License

MIT, see [LICENSE](./LICENSE). Originally created by [andersonlucasg3](https://github.com/andersonlucasg3); thanks to the original author and to everyone who kept the project alive in the fork.
