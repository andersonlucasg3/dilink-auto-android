# Phone Client App (app-client)

## Overview

The phone client is a **pure orchestrator**. It does not relay video, audio, or touch. The VD server binds `9638` (video), `9639` (input), and `9640` (audio, when negotiated) directly to `0.0.0.0` and the car connects to those ports without the phone in the middle — was 4 socket ops + 2 process context switches per frame, now 2 socket ops + 0 context switches. The phone owns only the lifecycle and control plane: handshake, audio capability negotiation (`FEATURE_AUDIO`), VD server deploy, car-app install, app allowlist filtering, and car log routing.

Flow:

1. Registers an mDNS service (`_dilinkauto._tcp`) and listens for TCP from the car on port `9637` (control + data, NIO ServerSocketChannel on `0.0.0.0`).
2. Accepts the control connection, reads `HANDSHAKE_REQUEST` (viewport, `screenDpi`, `appVersionCode`, `appVersionName`, `targetFps`, `dpiOverride`, `supportedFeatures`).
3. Opens a lifecycle ServerSocket on `127.0.0.1:19647` **before** sending `HANDSHAKE_RESPONSE` so the socket is ready when the VD server reverse-connects.
4. Sends `HANDSHAKE_RESPONSE` with `deviceName`, `vdServerJarPath`, `connectionMethod` (`CONNECTION_METHOD_SHIZUKU` when Shizuku is authorized, else `CONNECTION_METHOD_USB_ADB`), the negotiated `vdDpi`, and phone `supportedFeatures` (including `FEATURE_AUDIO` when enabled and available).
5. If Shizuku is available, deploys `vd-server.jar` directly on the phone via `ShizukuVdDeployer` (`CLASSPATH=... app_process / com.dilinkauto.vdserver.PipelineServer ...`). Otherwise the car deploys the VD server via USB or TCP ADB.
6. VD server reverse-connects on `localhost:19647`. Phone reads `MSG_DISPLAY_READY` (carries `displayId` + direct-injection flag), then sends `VD_PORTS_BOUND` to the car on the control connection — the car now connects video (`9638`), input (`9639`), and audio (`9640`, if negotiated) directly to the VD server.
7. Streams `MSG_STACK_EMPTY` (car back-pressured) from the VD server to the car over the control connection.
8. Routes `CAR_LOG` data frames from the car into `FileLog` (tag `CarLog`).
9. Sends the car-visible app list, filtered by the user's allowlist, with hash-suppressed icon PNGs.

**No screen capture. No MediaProjection. No notification forwarding.** All video comes from the VD server process running as shell UID. `NotificationService` and the onboarding "Notification Access" step were deleted in this fork.

## Components

### ClientApp

`Application` subclass. Creates the notification channel (`dilinkauto_service`), initializes `ShizukuManager` on `onCreate`. Hosts the phone-side `loadIconPng` helper that scales a launcher icon to a given size and PNG-encodes it for the wire payload. Encoded PNGs are memoized in a small `LruCache` (8MB byte budget, key `package|size|changeHash`, audit A-M12) so a re-send of an unchanged icon is a map lookup instead of a fresh raster + PNG compress — the car's own `AppIconCache` is what persists icons across sessions.

### MainActivity

Entry point. Auto-starts `ConnectionService` when the app is opened (e.g. via car USB ADB `am start`) if onboarding is complete, the service is `IDLE`, **and** the user has not explicitly stopped it. Routes between four screens (a single sealed `Screen` state, audit R3-SRP-09, replacing the previous onboarding/settings/allowlist boolean trio):

- **OnboardingScreen** (first launch): 6-step wizard — Welcome, All Files Access, Battery Optimization, Accessibility Service, Car Setup, Done. Each permission step explains what breaks without it and auto-advances on `ON_RESUME` once granted. The Car Setup step shows the live `installStatusFlow` and an install button. Any step can be skipped. There is no Notification Access step.
- **MainScreen** (subsequent launches): status card, start/stop button, Install on Car card, Share Logs button, Samsung warning card for Galaxy devices that need the Shizuku / wireless-debugging workaround.
- **SettingsScreen**: permissions status, Debug log toggle, about card.
- **AllowlistScreen**: phone-side picker for which launcher apps reach the car (see below).

`shareLogs()` calls `FileLog.zipLogs()` and shares the zip via `FileProvider`. Top-level screen routing, step state and permission polling state live in separate holders (`OnboardingState`, audit R3-SRP-05) rather than in the composables.

**`userStopped` (audit A-L21)** is persisted in the Activity's own prefs file, not kept in memory, on purpose: the path it guards is `PhoneDisplayRestorer` launching `MainActivity` with `FLAG_TURN_SCREEN_ON` after a teardown — a *fresh* Activity instance, so an in-memory flag would already read back as its default. It is what stops a restore-launched recreate from resurrecting a service the user just stopped. The notification's stop action goes straight to `ConnectionService`, which cannot write an Activity-owned flag.

### ConnectionService

Foreground service (`foregroundServiceType="connectedDevice"`) that orchestrates the phone-car session. Auto-started by `MainActivity`; runs the listen loop on `Dispatchers.Main` with a `PARTIAL_WAKE_LOCK` (4h auto-release).

- **Control connection (port `9637`)**: NIO TCP accept on `0.0.0.0`. Handles `HANDSHAKE_REQUEST`, heartbeat, and the DATA channel (`APP_LIST`, `APP_UNINSTALLED`, `APP_INFO_DATA`, `CAR_LOG`, `LOG_TOGGLE`). `LAUNCH_APP`, `GO_BACK`, `GO_HOME`, `APP_UNINSTALL`, `APP_INFO` now travel directly car → VD server on port `9639` and are no longer handled here.
- **Lifecycle channel (port `19647`)**: delegated to `VirtualDisplayClient` (see below). The ServerSocket is opened **before** `HANDSHAKE_RESPONSE` is sent so the VD server's reverse-connect never lands on a closed socket — a regression that previously caused VD startup failures.
- **`deployAssets()`**: extracts `vd-server.jar` to `/sdcard/DiLinkAuto/` and `app-server.apk` to `filesDir` through `AssetDeployer` (see below). CRC-checked; skips the write when the asset already matches the on-disk file. Runs once from `onCreate` and sets `assetsReady`, which `ensureAssetsReady()` polls (up to 5s) before a car install so the install path never races the extraction. Both use the `VdDeploy` absolute-path constants — joining `getExternalStorageDirectory()` with a leading-slash child used to produce a `/storage/emulated/0/sdcard/DiLinkAuto` shadow directory.
- **`handleHandshake()`**: rejects an unsupported `protocolVersion` at the boundary, computes VD dims via `VdDimensions`, tears down any previous VD (rotation case) under `vdLock`, opens the lifecycle ServerSocket (or reuses it across a mid-stream re-handshake), re-verifies the on-disk jar via `ensureVdServerJarCurrent()`, evaluates audio capability and preference (`AppPrefs.AUDIO_ENABLED`) to populate `FEATURE_AUDIO` in `supportedFeatures`, then sends `HANDSHAKE_RESPONSE`, deploys the VD server via `ShizukuVdDeployer` and waits for `MSG_DISPLAY_READY`. On `MSG_DISPLAY_READY` it sends `VD_PORTS_BOUND`, flips state to `STREAMING`, and calls `AppListBuilder.sendAppList`. It runs on `handshakeJob` on `Dispatchers.IO` (audit A-02) — it does disk I/O, a socket bind and package enumeration, all of which used to stall every other frame on the reader coroutine, and a throw used to kill the reader with no disconnect. The whole `vdClient` read-modify-write plus the lifecycle-client creation are serialized by `vdLock` (A-03): two overlapping handshakes (rotation + reconnect) could both pass the null check and both bind 19647, leaking a ServerSocket. The Shizuku decision is snapshotted **once** into `useShizuku` and threaded through both the response's `connectionMethod` and the deploy path (A-L18) — reading `checkPermission()` for the response and `isAvailable` later for the deploy let a binder death between the two say "SHIZUKU" while deploying nothing. The response also carries the scaled `vdWidth`/`vdHeight` so a Shizuku-less deployer (the desktop ADB path) still gets the anti-crop IME fix. A failed VD launch tears the session down instead of logging success (A-M7, see below).
- **Mid-stream re-handshake**: when the car rotates, it reuses the control TCP connection. `handleHandshake` tears down the old VD (`stopVdServer` + `disconnect`) and deploys a fresh one at the new orientation, leaving the lifecycle ServerSocket open across the swap.
- **VD exit wait before relaunch**: the phone no longer runs its own wait loop. It builds a `VdDeploy.DeployPlan`, supplies a `VdDeployExecutor` (`shellSync` → `ShizukuManager.execAndWait`, `launch` → `execBackground`, `probe` → `ShizukuManager.probeVdServer`) and calls the shared `vdRunDeploySequence` in **protocol-core**, so the phone, the car's USB/TCP ADB paths and the desktop cannot drift on the convergence rule. The sequence is graceful stop (`VdDeploy.gracefulStopCommand` 写停止哨兵文件) → `vdAwaitExit` (12s graceful budget — measured sentinel→exit latency on MI 9 is 6.1~6.3s, 150ms poll, two consecutive `GONE` confirmations, `UNKNOWN` accepted immediately so a dead transport never stalls deployment) → on timeout `VdDeploy.killCommandForce` (-9) and re-wait → launch. `forcedKill` is logged when the graceful path timed out. 为什么不是 SIGTERM：ART 的 `app_process` 收 SIGTERM 直接终止、不跑 shutdown hook（MI 9 实测），哨兵文件是唯一能让引擎走正常退出路径、把 `cleanup()` 完整跑完的手段。Two live engines race for the same VirtualDisplay / DTA / 9638-9639 ports; the loser skips `cleanup()` entirely.
- **Honest deploy status (audit A-M7)**: `VdDeployExecutor.launch` returns the `Boolean` that `ShizukuManager.execBackground` produces. When it is `false` the sequence reports `launched = false`, the service logs the failure, calls `cleanupSession()` and returns — it no longer prints "VD server started" for an engine that never launched. Before this, `execBackground` returned `Unit` and the operator only learned of the failure from the 60s accept timeout.
- **Cleanup idempotency guard**: two composed guards. **Session generations (audit A-01)**: every accept mints `sessionId = sessionSeq.incrementAndGet()`, publishes it as `activeSessionId`, and calls `resetCleanupGuard(sessionId)`; the listen-loop `finally` then calls `cleanupSession(sessionId)` with the id it captured, and a teardown whose id is no longer active logs `cleanupSession: stale session #n (active #m) — skipping` and returns. Previously a cancelled loop's late `finally` consumed the guard belonging to the **next** generation, so the fresh lifecycle ServerSocket was never closed and the stale VD client kept streaming — the leaked-VD failure mode. The `cleanupGuard` `AtomicBoolean` then caps the run at one teardown per generation; `cleanupSession()` was callable from 7 places (two network callbacks, the listen-loop `finally`, the handshake-failure path, `stopEverything`, and `onDestroy`) with no guard — observed 1 disconnect → 9 "Force-waking physical display" lines. `resetCleanupGuard()` is called when a new session is established (accept / mid-stream re-handshake) and from `stopEverything()`, which must always tear down even if a network callback already did.
- **`teardownScope` (audit A-04)**: the blocking half of `cleanupSession()` runs on `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — a process-lifetime scope — never on `serviceScope` (which `onDestroy()` cancels before the restore can run) and never on the Main caller (`stopVdServer()` joins a write worker for up to 1s: an ANR-shaped stall per call). Order preserved: graceful `CMD_STOP` → channel close → control disconnect → panel/IME restore.
- **Smart network callback**: `NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)` — only reacts to WiFi changes, ignores mobile data fluctuations. `onLost` is debounced 3s (4G hotspot resets can recover immediately); `onAvailable` restarts the listen loop only when `WAITING`. Proactive disconnect on `CONNECTED`/`STREAMING` so the heartbeat timeout does not have to expire.
- **`ACTION_ALLOWLIST_UPDATED`**: re-sends the app list so the car grid updates live while a session is active.
- **`ACTION_INSTALL_CAR`**: manual install path. `installCarApp` validates an explicit IPv4 literal (rejecting a malformed one), drops a double-tap or re-delivered action through an `installInFlight` `AtomicBoolean` (audit A-M9 — two concurrent flows used to push the same APK to the same remote path), waits on `ensureAssetsReady()`, then hands the whole flow to `CarInstallCoordinator` (see below). The service keeps owning `installStatusFlow`; the coordinator reports through a callback.
- **Package-removed receiver**: on `ACTION_PACKAGE_REMOVED` (with `EXTRA_REPLACING` filtered out), sends `APP_UNINSTALLED` and re-sends the full app list.
- **`setLogEnabled(context, enabled)`**: companion entry point called from `SettingsScreen`. Persists to `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`, sets `FileLog.enabled`, and propagates to the car over the live control connection via `DataMsg.LOG_TOGGLE` (1-byte payload). Since issue #72 the same frame is **also re-sent after every handshake response** (`syncLogToggle`) — the car resets `CarLogWriter` to `BuildConfig.DEBUG` on each `startConnection`, so without this a reconnect silently dropped the car-side log for the rest of the session.
- **`cleanupSession()`**: cancels `handshakeJob`, snapshots and clears `vdClient` under `vdLock`, clears `InputInjectionService`'s VD binding, disconnects the control connection, resets icon hashes, returns state to `WAITING`, and hands the cached IME to `PhoneDisplayRestorer`. The state snapshot/clear is synchronous so a concurrent handshake sees the old client gone; everything that can block runs on `teardownScope`.
- **`CAR_LOG` cap (audit A-05)**: the DATA dispatch is asynchronous and a single peer-controlled log line could be tens of MB, which entered the unbounded `FileLog` queue as a String. Lines are now capped at 8KB — truncated, not dropped, so the rest of the log stays usable — and `FileLog`'s queue is bounded at 2048 entries.
- **mDNS registration** runs in a background `launch` with a 5s `withTimeoutOrNull` so `NsdManager` cannot hang the listen loop when there is no network.
- **`FileLog.rotate()`** on `onCreate` — archives the previous session log.

State flow: `IDLE → WAITING → CONNECTED → STREAMING`, exposed via `serviceState: StateFlow<State>`.

### Extracted Collaborators (SRP/DRY Refactoring, Refs #87, #92)

To preserve single responsibility and maintainability, `ConnectionService` delegates domain-specific logic to extracted collaborators:
- **`ShizukuVdDeployer`**: Manages the Shizuku-based execution of `vdRunDeploySequence`, including graceful stopping, liveness probing, and background `app_process` launching.
- **`ShizukuNav`**: Coordinates system navigation actions (back, home, recents) through Shizuku shell when available.
- **`ClientNetworkMonitor`**: Observes network connectivity transitions, decoupling Wi-Fi state listener logic from core session lifecycle. `onAvailable` is not treated as "the link is usable": a Wi-Fi re-association can report the new network several seconds before traffic flows again, so `ConnectionService` only rebuilds when [`NetworkRebuildPolicy`] sees the control channel silent for ≥6 s (two missed 3 s heartbeats) — the dead-socket case that would otherwise wait for the 10 s keep-alive watchdog (issue #200). **This policy does not fire for the issue #200 field incident**: `onAvailable` arrived with the control channel silent for only 1.96 s (the 5983 ms figure is *video* silence, measured after recovery), and `msSinceLastFrameReceived()` returns -1 until a frame is actually read. The field benefit of #200 comes from the car-side `LINK GAP` pre-arming, not from this rebuild path — do not read a green build here as "the 6 s freeze is fixed".
- **`CarAppInstallHost`**: Hosts the car-app APK installation flow, managing asynchronous install states without bloating the connection service.
- **`AllowlistRepository`**: Encapsulates persistence and retrieval of permitted launcher applications.
- **`InputInjectionService` (@Deprecated)**: Previously held virtual-display bindings for accessibility gesture injection. With direct touch injection on port 9639 handled by `vd-server`, its invocation path has been completely severed.

### VirtualDisplayClient

Lifecycle-only. Accepts the VD server's reverse connection on `localhost:19647` (the VD server connects **to** the phone, never the other way around). Takes no `videoConnection`/`controlConnection` params — video and touch are no longer relayed through the phone.

- `startListening(port = SERVER_PORT)`: synchronous `ServerSocketChannel` bind on `127.0.0.1:19647`. Called **before** `HANDSHAKE_RESPONSE` so the socket is open when the VD server connects back. Bound to **loopback, not `0.0.0.0`** (audit A-M10): the channel is localhost-only by design, and the first message read after accept carries the `displayId` that touch is injected into — a LAN peer winning the accept race on the wildcard listener could have redirected that.
- `acceptConnection(port, timeoutMs = 60000)`: non-blocking accept loop that **refuses non-loopback peers** and keeps waiting for the real engine (A-M10). First byte must be `MSG_DISPLAY_READY` (carries `displayId: Int` + `directInjection: Byte` flag). On success, sets `isConnected`, fires `onDisplayReady` (which `ConnectionService` uses to send `VD_PORTS_BOUND`), and starts the command relay. The ServerSocket is closed in a `finally`, so a failed accept leaves nothing bound for the next handshake to inherit.
- **Command relay** (`Dispatchers.IO`): reads `MSG_STACK_EMPTY` and forwards it via the `onStackEmpty` callback → `ConnectionService` sends `ControlMsg.VD_STACK_EMPTY` to the car.
- `stopVdServer()`: writes `CMD_STOP` (`0xFF`) to the VD server for graceful shutdown. **Returns `Boolean`** — `true` when the byte was handed to the socket, `false` when the lifecycle channel is already gone. A `false` return is expected whenever the channel is already closed — the engine's `readLifecycleCommands()` treats the resulting EOF/IOException exactly like `CMD_STOP` (sets `running=false` → `finally cleanup()`), so the teardown still happens; the caller just must not assume it was graceful. The write itself happens on a short-lived daemon worker joined with `STOP_WRITE_JOIN_MS` (1s): Android throws `NetworkOnMainThreadException` on a socket write from Main, and `cleanupSession()` runs there, so the graceful `CMD_STOP` had never actually been sent on a real device — the log's `NetworkOnMainThreadException` was it. The write is kept *synchronous to the caller* because `disconnect()` (which releases the 19647 `ServerSocketChannel` for the re-handshake's re-bind) must run immediately after it; dispatching both to an async scope would introduce a port-occupation race.
- `disconnect()`: closes reader, server socket, and channel; resets `displayId = -1`.
- On disconnect the VD server's own cleanup runs only if it received `CMD_STOP`. If the lifecycle channel breaks first, `PhoneDisplayRestorer` is the safety net (see below).

### AllowlistScreen

Phone-side picker for which launcher apps the car is allowed to show. The selection lives in `SharedPreferences` (`dilinkauto_allowlist` / `allowed_packages`), read by `AppListBuilder.sendAppList` to filter the wire payload before it reaches the car. Toggling a row writes the prefs, marks `allowlist_configured = true`, and fires `ConnectionService.ACTION_ALLOWLIST_UPDATED` so the running service re-sends the list immediately — the car grid updates live without a reconnect. It uses plain `startService`, not `startForegroundService` (audit A-L21: the service is already foregrounded, and upgrading would re-arm the 5s `startForeground` contract for an action that posts no notification), and is skipped entirely when the service is `IDLE` — starting it just to refresh a list would resurrect a stopped service in a non-foreground state. UI: search box, Select All / Deselect All, per-row Switch, and a counter. Pre-seeded with common map apps on first run (see `AllowlistSeeder`).

### AppListBuilder

Builds and sends the car-visible app list on behalf of `ConnectionService`.

- Owns only the allowlist filter and the send; list assembly lives in `AppInfoProvider` (query the enabled launcher apps, derive each package's change hash) and icon suppression in `IconHashGate` (audit R3-SRP-18).
- Filters launcher apps by the allowlist (`ALLOWLIST_PACKAGES_KEY`) before going on the wire, shrinking the wire payload and the car's icon-decode work. A **null** selection (never configured) passes everything through.
- `filterByAllowlist` first calls `AllowlistSeeder.seedIfNeeded` when `ALLOWLIST_CONFIGURED_KEY` is false, so a stale empty prefs file is seeded exactly once per install.
- `allowedPackages()` exposes the same effective set to the deploy path: the engine (`shell UID` `app_process`) cannot read this app's prefs, so `ConnectionService.startVdServerViaShizuku` hands it down through the `ALLOWED` argv slot and `ensureContent` only restores apps from it (issue #21). A **null** selection (never configured) hands nothing down and the engine keeps its unfiltered behaviour.
- Icon data is sent once per package per session — `IconHashGate.lastSent` (a `ConcurrentHashMap`, keyed on `lastUpdateTime`; made concurrent in audit A-M11 because `reset()` runs on Main while `iconFor()` runs on the list-building IO thread) suppresses unchanged icons. `resetIconHashes()` forces a full re-send on disconnect or car-app reinstall.
- Categorization is not done here — see `AppCategorizer` below.

### AppCategorizer

`object` classifying an installed package into `AppCategory.NAVIGATION / MUSIC / COMMUNICATION / OTHER` for the car grid. Extracted from `AppListBuilder` (audit S10) so the rules are unit-testable without a `Context` or `PackageManager`. Rules are keyword-based on the package name and **order matters**: a package matching a navigation keyword never reaches the music or communication branches.

**Known limitation:** the keyword set is narrow, so common apps fall into `OTHER` — `com.google.android.youtube` matches nothing. This is a UX gap, not a crash, and it is pinned by `AppCategorizerTest`. A label- or category-aware classifier is the fix.

### AllowlistSeeder

First-run seeding of the car's app allowlist (extracted from `AppListBuilder`, audit SRP-7). `seedIfNeeded(pm, prefs)` intersects `DEFAULT_MAP_PACKAGES` with the launcher apps actually installed (`LauncherApps.queryInstalledPackageNames`) and persists the intersection, marking the allowlist configured. Defaults that are not installed are no-ops, so a user with none of them gets an empty **but configured** allowlist rather than an unconfigured one that re-seeds on every launch. Runs once per install — afterwards the user curates the list, so re-seeding would overwrite their choices.

### CarAppInstaller

Installs the embedded `app-server.apk` onto the car via `dadb` (ADB-over-WiFi, port `5555`). Owns: ADB key-pair generation and `Dadb.create()` with a hard 15-second `Future.get` timeout (the blocking socket I/O cannot be interrupted by coroutine cancellation) — both delegated to `AdbKeyUtil`, whose `ensureAdbKeyPair` self-heals a half-missing key pair (audit A-L24) — `dumpsys package` version read, `pm install -r`, and `am start --activity-clear-task -n com.dilinkauto.server/.MainActivity` (`AppTargets.CAR_MAIN_ACTIVITY`). Status strings flow back to the caller via an `onStatus` callback so `installStatusFlow` stays in `ConnectionService`. It no longer drives the install flow — that is `CarInstallCoordinator` (below). `CarAppInstaller` is the **production `CarInstaller<Dadb>`**.

`parseInstalledVersion(output)` is a pure companion function returning `"0"` — the not-installed sentinel — when `dumpsys` has no `versionName=` line, so the parse is unit-testable without a `Dadb` session. See the known-issue note on that sentinel under `Versioning`.

### CarInstallCoordinator

Drives one car-app install: locate the car, connect over ADB, skip if already current, otherwise push and install. Extracted from `ConnectionService.installCarApp` (audit SRP-2) — that method was ~70 lines of sequencing that belonged to no single connection concern: it neither opens nor tears down the streaming connection, it just runs alongside it and reports progress.

Status is reported through a callback rather than a `StateFlow` so this class has no opinion about how the UI observes it — the service keeps owning `installStatusFlow`. Sequence:

1. Bail out with a "car APK not found" status if `apkFile` does not exist.
2. Emit "searching" (or "connecting to `<ip>`" when an explicit IP was given).
3. `resolveCarIp(explicitIp)` — injected, defaults to a `CarIpLocator.probePortSync` check on the explicit IP followed by `CarIpLocator.findCarAdb`.
4. `installer.connect(carIp)`; `null` means the car is waiting on the ADB authorization prompt, so the status is **kept** on screen (`keepStatus`) instead of being cleared after the usual 5s.
5. `readInstalledVersion` → `compareVersions(my, installed) <= 0` → "already up-to-date" and stop (the session is still closed in a `finally`).
6. `pushAndInstall` → on `"Success"` run `onReinstalled` (the service clears its icon hashes) and report the installed version; otherwise report the trimmed failure output.
7. A thrown exception is reported and the session closed; the 5s auto-clear runs unless `keepStatus`.

All status strings resolve through `context.getString`, null-safe: under the plain-JVM test setup `getString` returns null, so the fallback keeps the state machine unit-testable while production always returns real text.

### CarInstaller (test seam)

`internal interface CarInstaller<S>` — the ADB install seam behind `CarInstallCoordinator`. Generic over the "session" type because the coordinator only passes the handle through `connect → readVersion → pushAndInstall → close` and never calls its methods itself. That is what lets the whole install state machine be driven in a JVM test with a fake session and no real `dadb.Dadb`. The production implementation is `CarAppInstaller` with `S = Dadb`.

### AssetDeployer

Unpacks a bundled asset to disk, skipping the write when the on-disk copy already matches. Extracted from `ConnectionService` (audit DRY-4), where `extractAsset` and `ensureVdServerJarCurrent` each independently implemented the same read-asset → CRC32 → compare → write sequence, so a fix to one silently skipped the other.

- `extract(assetName, target)` → `Result.Current(crc)` when the on-disk CRC already matches (no write, no Shizuku needed), `Result.Written(crc, bytes)` after a fresh write, or `Result.Failed(reason)` prefixed `read:` / `write:`. `mkdirs()` precedes the existence check because `/sdcard/DiLinkAuto` does not exist on a fresh install; an unreadable target falls through and is overwritten rather than failing.
- `ensureCurrent(assetName, target)` → the CRC of the jar now in place: verified current, freshly written, or the last **usable** on-disk copy when the refresh failed (that copy still runs), or `-1` when there is no usable file and the caller must decide whether to abort. Audit A-L15: both failure branches used to return `-1` unconditionally, which made the "usable value" contract unreachable and every caller's fallback branch dead code.
- Writes go temp-file + rename so a crash mid-write cannot leave a truncated jar that passes an existence check, with a direct-write fallback when the rename fails (some FUSE-backed volumes). The `finally` deletes the `.tmp` sibling unconditionally (audit A-L16): the fallback write can throw too, and without the delete every failed refresh stranded another `<target>.tmp` on shared storage.
- Called from two places: `deployAssets()` in `onCreate` (both assets) and `ensureVdServerJarCurrent()` at every handshake, because the deployed jar outlives the process while `deployAssets` runs once — without the re-check `app_process` can silently load a stale engine that still runs and still logs, just running old code.

### AssetSource (test seam)

`internal interface AssetSource { fun read(assetName: String): ByteArray }` — the byte-source seam for `AssetDeployer`. It exists because the production implementation wraps the Android `AssetManager`, which is `final` and therefore cannot be subclassed as a test double. `AssetManagerAssetSource(assets)` is the production form; tests inject a fake so `extract` / `ensureCurrent` — the on-disk `vd-server.jar` CRC-freshness gate — become unit-testable across every branch.

### CarIpLocator

Locates the car's ADB-over-WiFi service (port `5555`) for the "Install on Car" flow. Strategies in order of latency: the active control connection's remote IP, subnet enumeration, ARP table (`/proc/net/arp`), `ip neigh` neighbor cache (always reaped in `finally`), parallel `/24` subnet scan (32 concurrent probes, 150ms per-probe timeout), and the WiFi gateway. Plain `object` — no `Service` dependency — so it is unit-testable in isolation. `probePortSync` is the synchronous 500ms variant used by the manual install path.

### VdDimensions

Pure viewport math for the phone-side VirtualDisplay created in response to a `HandshakeRequest`. Lives in **protocol-core** (moved from app-client on 2026-10-09) so the rules can be read and tested as plain JVM code — the caller passes the phone's real physical pixel size, no Android types involved.

- VD width/height start from the car viewport, even-aligned (H.264 needs even dimensions).
- **Anti-crop scale**: if the car viewport is narrower than the phone's physical width (orientation-resolved: a portrait phone's long edge acts as the width for a landscape car), the VD is scaled up to match. Many Chinese ROMs (Meizu, Xiaomi) hardcode IME width to the physical display width, and the IME width does not follow VD density; a narrower VD chops the keyboard horizontally no matter what DPI is negotiated. Scaling preserves the car's aspect ratio while satisfying the OS width requirement.
- **Real physical metrics only**: the phone size must come from `WindowManager.maximumWindowMetrics` (API 30+) / `Display.getRealMetrics` (API 29) — `resources.displayMetrics` reports compat-scaled values in screen-compat mode (Meizu 20 Inf: reports 2992 for a physically 3192px screen), which left the IME 200px (~6.7%) wider than the VD at every DPI setting.
- **DPI**: a car-side `dpiOverride` (coerced to `[120, 480]` via `VdDeployArgs.coerceDpiOverride`) bypasses the portrait-app-safe cap; otherwise `VideoConfig.calculateOptimalDpi` auto-calibrates for a ~380dp logical width in landscape.
- Returns `(vdWidth, vdHeight, dpi)` for the VD deploy args and the `VirtualDisplay` creation.

### InstallStatus

Enum classifying the free-form status string produced by the install path. Centralizes the `status.contains(...)` parsing so `OnboardingScreen`, `CarInstallCard` (`MainScreen`) and the install-stage checklist (`CarSetupInstallSection`) agree on what is in-progress, terminal, or auth-needed. States: `IDLE, SEARCHING, CONNECTING, CHECKING, PUSHING, INSTALLING, LAUNCHING, AUTH_NEEDED, DONE, ERROR`. Exposes `stageKeywords` (Searching / Connecting / Checking / Push / Install / Launching, each paired with a string-resource id so the UI can resolve it with `stringResource`) for the stage-progress UIs, plus `parse(status)`, `stageIndex(status)`, `isInProgress`, and `isTerminal`.

**Known limitation (C-6):** `parse` classifies by matching **English substrings** of a string that `context.getString` produces. Under any non-English `values-*` locale every status falls through to `IDLE`, so the user sees no progress and no error at all, and the strings the phone itself emits in English — `"not reachable"`, `"Invalid IP: …"`, `"Install already in progress"` — classify as `IDLE` rather than `ERROR`. The fix is a structured status enum (the `InstallStatus` state plus an error id) threaded from `CarInstallCoordinator` to the UI instead of parsing localized text. `InstallStatusTest` pins the current behaviour so the gap is visible rather than silent.

### PhoneDisplayRestorer

Restores the phone's physical display and IME after the VD server tears down. The VD server powers off the physical panel directly via `DisplayControl.setDisplayPowerMode(0)` — a deeper off than `PowerManager` can recover from. Its cleanup runs only if it received `CMD_STOP`; when the lifecycle channel breaks first (or the process hangs in a native futex), `PhoneDisplayRestorer` is the safety net. Four layers, tried in order, each independent:

1. **Shizuku**: `VdDeploy.gracefulStopCommand` (停止哨兵文件) → 轮询等引擎退出（12s；MI 9 实测哨兵→进程消失 6.1~6.3s）→ 超时才 `killCommandForce`（-9，pgrep 定位 + 按 pid kill）→ `cmd display power-reset 0` → IME restore via `ImeRestore.imeRestoreCommandLine`. 哨兵让引擎走**正常退出路径**把 `cleanup()` 完整跑完（releases VD, restores IME/letterbox/screen settings, re-powers panel）——**不能用 SIGTERM**：ART 的 app_process 收到即终止、不跑 shutdown hook（2026-10-10 MI 9 实测）。(`power-reset`, not `power-on`: the latter subcommand does not exist — `cmd display help` on Android 15 lists only `power-off` and `power-reset`.)
2. `PowerManager.wakeUp()` via reflection (system-level wake).
3. Launch `MainActivity` with `FLAG_TURN_SCREEN_ON` (WindowManager triggers display on).
4. `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` wake lock.

**Owns its own `CoroutineScope`** — deliberately NOT the Service's scope:

- **Problem**: The previous version ran on the Service's `serviceScope`, which `ConnectionService.onDestroy()` cancels. When the user hit "stop" (or the system reclaimed the Service) the restore coroutine was cancelled *between* the `pkill` and the `cmd display power-reset`, leaving the physical panel off with nothing left to turn it back on. Observed in logs: 3× "Force-waking physical display" followed by 3× "Shizuku display restore failed: Job was cancelled".
- **Fix**: `restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — a process-lifetime scope that no Service lifecycle can cancel. `withContext(NonCancellable)` wraps the restore so once we start killing the engine we must run the power-reset too. `inFlight` `AtomicBoolean` collapses concurrent restore requests into one.

### AppVersion

Reads an installed app's version label as a string, with a `versionCode` fallback for pre-semver peers. `label(context, preferCode, packageName)` returns `versionName ?: versionCode.toString()`; `nameOrEmpty` is the empty-string variant used by the About card. Extracted from duplicated sites in `ConnectionService` (car-install version check) and the about card.

### Versioning

Version parsing and comparison for the car-install version check (skip reinstall when the car already has the embedded version). Pure functions so the car-install path and tests can compare versions without pulling in any state machine. Two schemes are supported:

**Date-time (current, issue #224):** `20261117-2315` — `YYYYMMDD-HHMM`, matched by `^(\d{8})-(\d{4})$` and compared as the two numeric segments `[YYYYMMDD, HHMM]`. Since the first segment is eight digits, any date version outranks any semantic version. A `-dev` suffix still works on a date base (`20261117-2315-dev-1` → base `20261117-2315`, `isDev=true`), and dev sorts below the matching release — daily iteration just uses a new timestamp instead. Rationale: before this, the semantic parser coerced `20261117-2315` to a single non-numeric segment → 0, so **any two date versions compared equal** and `CarInstallCoordinator` silently skipped every car-app install while reporting "already up to date".

**Semantic (legacy):** `0.17.0` (release), `0.17.0-dev` (dev, num 0), `0.17.0-dev-02` (dev, num 2). Release > dev of the same base; dev builds order by `devNum`. Non-numeric components coerce to 0 for robustness against malformed wire input, and a missing component counts as 0. Anything that is not the recognized `-dev[-n]` form (e.g. `-SNAPSHOT`) parses as a release with the whole string as its base.

**Known hazard (C-5):** `CarAppInstaller.readInstalledVersion` returns `"0"` as the not-installed sentinel, and `compareVersions` coerces non-numeric components to 0 — so a peer whose real `versionName` is `0`, `0.0` or `0.0.0` compares **equal** to the sentinel and the install is skipped as "already up-to-date" on what should have been the first install. `-SNAPSHOT` also compares equal to its corresponding release. `VersioningTest` characterizes both cases; the fix is a distinct not-installed sentinel (or a separate "not installed" signal from `dumpsys`) rather than a version that collides with a real one.

### ShizukuManager

Manages the Shizuku lifecycle and provides shell-level command execution via `IShizukuService.newProcess` (UID 2000). `init(context)` registers binder-received / binder-dead / permission-result listeners. `checkPermission()` caches `isAvailable`.

- `execAndWait(command)` runs `sh -c` with a 30s deadline (drains stdout + stderr concurrently so a >64KB stderr write cannot deadlock the pipe) and returns an `ExecResult(stdout, stderr)` — or `null` when Shizuku is unavailable, the binder is dead, or the call fails. The two streams are kept **separate** (audit A-M6): the previous single merged string made the VD-server probe decide ALIVE/GONE from text that could equally have come from stderr, so a probe that merely failed loudly read as "alive". The stderr drain is joined via a sentinel on a bounded queue so the caller can never observe a half-appended buffer, and the FDs are closed only after that.
- `execBackground(command)` is fire-and-forget (used for `app_process` so the server outlives the shell stream) and **returns `Boolean`** — `false` when Shizuku is unavailable, the binder is dead, or `newProcess` threw. Callers must treat `false` as "the engine never launched" (audit A-M7, see *Honest deploy status* above). The command is passed to `sh -c` verbatim; the caller decides backgrounding (`setsid … &`), and stripping a trailing `&` and re-adding one used to produce `… & &`, a shell syntax error.
- `probeVdServer()` runs the shared `VdDeploy.probeCommand` (`pgrep -f`-based `[P]ipelineServer` existence check — no `ps`/`pidof` needed; `pkill -0` is unusable, see `VdDeploy`) and returns `VdProbeResult.ALIVE / GONE / UNKNOWN`, deciding from **stdout only**. This is the Shizuku counterpart of the car/desktop exit-code probe; it deliberately does **not** implement its own wait loop anymore — the convergence rules (two consecutive GONE) live in protocol-core's `vdAwaitExit`. `UNKNOWN` when Shizuku is unavailable or the probe fails, which the deploy sequence treats as "accept the exit" so a dead transport never stalls deployment.

`execAndWait` is used for `shellSync` and `execBackground` + `probeVdServer` for `launch`/`probe` by the `VdDeployExecutor` in `ConnectionService.startVdServerViaShizuku`. The former `waitForVdServerExit(timeoutMs)` polling helper and the `copyToFile` shell-streaming helper are gone.

### FileLog

File-based logger that bypasses Android logcat filtering (HyperOS filters `Log.i/d` for non-system apps).

- Writes to `/sdcard/DiLinkAuto/client.log`. Single writer thread drains the shared `AsyncLogQueue` (`LOG_QUEUE_CAPACITY = 2048`, audit A-05); `SimpleDateFormat` is only touched from the writer thread.
- `loadEnabled(prefs)`: default ON for debug/pre-release (`BuildConfig.DEBUG`), OFF for release. Once the user explicitly toggles, that choice persists via `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`. The rule lives in one place — `isEnabledIn(prefs)` (over the pure `resolveLogEnabled`) — and `SettingsScreen` reads it for the switch's initial value, so the UI can no longer show ON while the logger is OFF (issue #72).
- `rotate()`: archives the current log as `client-YYYYMMDD-HHmmss.log`, starts fresh, prunes to 30 archived + 1 current.
- `zipLogs()`: produces `dilinkauto-logs.zip` from all `.log` files in `/sdcard/DiLinkAuto/` and additionally includes `/data/local/tmp/vd-server.log` when it exists — the VD server's own log is part of every Share Logs submission.
- Also calls `android.util.Log.*` for standard logcat output.

The toggle is in **Settings → Debug**. Toggling it calls `ConnectionService.setLogEnabled`, which propagates the choice to the car over the live control connection via `DataMsg.LOG_TOGGLE` (1-byte payload) so the car's `carLogEnabled` follows the phone's setting.

### PermissionChecker

Runtime permission checks for the phone UI's onboarding and settings screens. Extracted from `OnboardingScreen` and `SettingsScreen`, which had each check written inline three times (initial read, periodic re-check, and onboarding's `pollPermission` switch). `hasAllFilesAccess()`, `hasBatteryExemption(context, pkg)`, `hasAccessibility(context, pkg)` — all plain `Context` receivers so they can be called from any `@Composable` or coroutine scope.

## Testing

app-client is covered by plain JUnit4 + `kotlinx-coroutines-test` JVM tests (`app-client/src/test/java/com/dilinkauto/client/service/`), run with `./gradlew :app-client:testDebugUnitTest`. No Robolectric/Mockito/MockK — the Android dependencies are either injected through a seam, or made harmless by `testOptions { unitTests.isReturnDefaultValues = true }` plus hand-written fakes (`ContextWrapper(null)`, canned lambdas, a fake `AssetSource` / `CarInstaller`).

The first-pass suite (`VersioningTest`, `AppCategorizerTest`, `InstallStatusTest`) is **38 `@Test` methods** (19 + 8 + 11), as recorded in [./IMPLEMENTATION_REPORT_TESTING.md](./IMPLEMENTATION_REPORT_TESTING.md); the per-file breakdown below is the literal method count.

Later, behaviour-preserving seams added more coverage on top of that:

| Test file | Covers |
|---|---|
| `VersioningTest` (19) | `parseVersion` / `compareVersions` — release vs dev ordering, dev numbering, malformed and non-numeric components, ordering axioms, and the zero-sentinel / `-SNAPSHOT` hazards |
| `AppCategorizerTest` (8) | category ordering and the keyword rules, including apps that fall through to `OTHER` |
| `InstallStatusTest` (11) | `InstallStatus.parse` / `stageIndex` classification, `isInProgress` / `isTerminal`, and the localized-text hazard |
| `CarInstallCoordinatorTest` (8) | the whole install state machine through the `CarInstaller` seam — skip when current, push on older, hold the auth-needed message, session always closed, exception path |
| `CarAppInstallerParseVersionTest` (6) | `parseInstalledVersion` against real `dumpsys package` shapes and the `"0"` sentinel |
| `AssetDeployerExtractTest` (12) | `extract` / `ensureCurrent` across every branch via a fake `AssetSource` — write, CRC skip, rewrite, mkdirs, read/write failure, A-L15 fallback, and the `.tmp` sibling never surviving any outcome |
| `AssetCrcPolicyTest` (8) | CRC freshness policy, `usableExistingCrc` edge cases, and the temp-file + rename + fallback write |
| `IconHashGateTest` (5) | concurrent `iconFor` / `reset` under an 8-thread storm (audit A-M11) |
| `AdbKeyUtilSelfHealTest` (5) | half-missing key-pair regeneration (audit A-L24) |
| `CarIpLocatorTest` (7) | car-ADB discovery strategies |
| `ConnectionServiceInitGuardTest` (4) | init-guard invariants |
| `AdversarialM2Test` (2) | hostile-input characteristics |

See [./IMPLEMENTATION_REPORT_TESTING.md](./IMPLEMENTATION_REPORT_TESTING.md) for the full-project report (all six modules, the locked cross-module invariants, and the audit findings that were deliberately left in place as characterization tests). The app-client findings recorded there that this document references by id: **C-5** (Versioning zero-sentinel), **C-6** (InstallStatus English-substring classification), and the `AppCategorizer` gap.

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| `MANAGE_EXTERNAL_STORAGE` | All Files Access for sdcard deployment of `vd-server.jar` to `/sdcard/DiLinkAuto/` |
| Accessibility Service (`InputInjectionService`) | Holds the virtual-display binding (`displayId` + VD dims) for the car connection. Touch injection itself happens in the VD server process via `InputManager` reflection; the AccessibilityService path is unused for injection. No event monitoring. |
| Shizuku API (`moe.shizuku.manager.permission.API_V23`) | Elevated shell access for ADB-free VD server deployment (`app_process` as shell UID) |
| `QUERY_ALL_PACKAGES` | App launcher grid (deprecated by Play policy; fine for sideloaded car head units) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | `ConnectionService` foreground type |
| `WAKE_LOCK` | 4h `PARTIAL_WAKE_LOCK` so streaming survives screen-off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery exemption so streaming stays alive when the screen is off |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | TCP sockets, smart network callback, mDNS |

`NotificationService` and the corresponding `BIND_NOTIFICATION_LISTENER_SERVICE` permission are **deleted** from the manifest and onboarding in this fork.

## Dependencies

- Jetpack Compose + Material 3 (Compose BOM 2023.10.01)
- `material-icons-extended` (still present in `build.gradle.kts`; the perf audit recommended dropping it but the dep has not been removed)
- `kotlinx-coroutines`
- `dev.mobile:dadb:1.2.10` (WiFi ADB for car-app install)
- `dev.rikka.shizuku:api / aidl / provider:13.1.5`
- Protocol module (`:protocol`) — shared with the car app and VD server (`:protocol` also pulls in the pure-JVM `:protocol-core`)
- `app-server.apk` and `vd-server.jar` embedded in `app-client/src/main/assets/` by the `embedServerApk` and `buildVdServer` tasks (only the phone APK is installed manually; the car APK and VD JAR are pushed/embedded automatically). `buildVdServer` dexes `:vd-server` + `:protocol` + `:protocol-core` + the Kotlin stdlib and coroutines into a single JAR for `app_process`; `embedServerApk` refuses to ship a stale APK when the server module has not been built.
- Test: `junit:junit:4.13.2`, `kotlinx-coroutines-test:1.7.3`, and `testOptions { unitTests.isReturnDefaultValues = true }`

Build: Kotlin 1.9.22, Android Gradle Plugin 8.2.2, `compileSdk = 34`, `minSdk = 29` (app-client), JDK 17, current version `20261117-2315` (versionCode `26111723`, derived from the date in `app.versionName`). Release builds require `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_PASSWORD` environment variables — without them a release build fails at configuration time rather than silently producing an unsigned APK. See [./setup.md](./setup.md) for build commands, [./release.md](./release.md) for signing and release rules, and [./architecture.md](./architecture.md) for the cross-module layout.
