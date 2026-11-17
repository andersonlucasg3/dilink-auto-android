# Progress Tracker

Current state: see [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest).

## Milestones

The milestones below are the upstream repo's history, preserved as historical context. They describe what landed in `andersonlucasg3/dilink-auto-android` up to `v0.18.0-dev` (2026-05-09). Everything after that point is this fork's own work — see [Fork Changes (post v0.18.0-dev)](#fork-changes-post-v0180-dev) below.

### v0.18.0-dev (2026-05-09)

- **PipelineServer — single-threaded VD streaming**: Replaced VirtualDisplayServer (1287 lines, 9 threads, 2 queues) with PipelineServer (~620 lines, 3 threads, 0 queues). Single pipeline thread: frame clock → GL render → encoder drain → TCP write. Eliminates all inter-thread queues and `park()`/`unpark()` synchronization.
- **Drift-free frame clock**: Uses `System.nanoTime()` + `LockSupport.parkNanos()` for microsecond-precision timings. No `Object.wait()` — always schedules from ideal timeline, single catch-up reset when behind.
- **Adaptive bitrate**: Starts at 5Mbps CBR, measures TCP write time per frame. Downgrades 25% on congestion (>15ms write), upgrades 1Mbps after 5s clean. Range 2-8Mbps. Eliminates write queue and backpressure frame drops.
- **Car-native VD resolution**: VD created at car viewport dimensions (e.g., 1806×990) instead of phone DPI (3282×1800). Eliminates GPU downscale — SurfaceScaler removed. VD surface → SurfaceTexture → GL passthrough → encoder. 70% fewer pixels processed.
- **Encoder: Main Profile, I-frame 1s, latency 0, no B-frames**: Baseline → Main profile for ~20% better compression. `KEY_OPERATING_RATE` and `KEY_MAX_B_FRAMES=0` for predictable latency.
- **Decoder: 2-frame queue, keyframe priority**: Minimal 2-frame `ArrayBlockingQueue`. Keyframes always accepted (evict P-frames). No catchup logic — frames arrive on time or get dropped. `drainOutput()` before `feedBuffer()` to free decoder buffers first.
- **Connection stability fixes**: `connectToPhone` won't kill active session when `handshakeDone`. Gateway retry and mDNS loops stop immediately on handshake send. TCP ADB reconnects when phone IP changes. Auto-fallback to TCP ADB when USB unavailable. Reconnect loop stops after 3 consecutive no-ADB failures.
- **Shizuku mode**: Phone deploys VD server via Shizuku when available. Car waits for `VD_PORTS_BOUND` instead of deploying via ADB.
- **Crash diagnostics**: `CarCrashHandler` saves crash stack + device info to file, sends to phone on next launch via TCP log channel.
- **Device info logging**: Phone and car log memory, CPU, display, Android version at session start.
- **Log toggle**: Settings → Debug → Diagnostic logs switch. When off, zero writes to disk. Propagated to car via `LOG_TOGGLE` data message.
- **Lifecycle channel**: Phone binds `0.0.0.0:19647`, car passes phone IP (not `127.0.0.1`) for shell UID access on restrictive ROMs.
- **Version comparison fix**: When car sends no `appVersionName`, compare `versionCode` on both sides instead of mixing semver with integer.
- **VD server log in Share Logs**: `zipLogs()` now includes `/sdcard/DiLinkAuto/vd-server.log` when available.
- **TcpAdbConnection — persistent single-connection ADB**: Replaced Dadb library (which opened a new TCP connection per command, causing ECONNREFUSED on Xiaomi/HyperOS) with a custom `TcpAdbConnection` in the protocol module. Maintains a single TCP socket for all shell commands. Handles CNXN/AUTH/SIGNATURE/RSAPUBLICKEY with correct ANDROID_PUBKEY format and PEM key storage. Shared between car and phone apps via protocol module.
- **EGL/GL texture context fix**: Fixed PipelineServer crash where GL texture was created in a temporary EGL context (then destroyed) and the pipeline thread bound texture 0 instead of the actual texture ID. Moved all EGL/GL initialization to the pipeline thread with proper CountDownLatch synchronization.
- **VD server deployment fix**: `app_process` was killed when the ADB shell process exited (non-interactive shells kill background jobs). Fixed by keeping the ADB shell stream open via `shellBackground` method, allowing the VD server process to survive.
- **Phone screen restore fix**: Reordered PipelineServer cleanup to restore physical display BEFORE destroying the persistent shell process (was calling `input keyevent 224` after shell was already closed).
- **Log toggle defaults**: Diagnostic logs default ON for debug/pre-release builds (`BuildConfig.DEBUG`) and OFF for release builds. Once user explicitly sets the toggle, their choice persists regardless of build type.

### v0.17.0 (2026-05-02)

- **Decoder restart eliminated**: `MediaCodec.setOutputSurface()` replaces `stop()`+`start()` when TextureView surface changes. MirrorContent kept alive in Compose tree with `View.INVISIBLE` toggle — zero frame loss during HOME↔APP↔NOTIFICATIONS navigation. Eliminated ~3s of keyframe-drop artifacts per screen transition.
- **Fixed 30fps encoding**: Reduced from 60fps to 30fps fixed rate. Phone no longer overheats during streaming. CPU/GPU load halved. WiFi traffic reduced ~40%.
- **Adaptive framerate removed**: Initial 15fps static-mode caused near-zero frame rate during app switching. Reverted to fixed 30fps — deterministic, smooth transitions between apps.
- **Documentation**: All v0.17.0 changes documented with full 8-language translations (en, pt-BR, ru, be, fr, kk, uk, uz). Floating support card added to GitHub Pages. Language selector locale for release dates.

### v0.17.0-dev-02 (2026-05-01)

- **Phone overheating fix**: Eliminated CPU spin patterns in the streaming pipeline causing phone overheating during streaming. Replaced `delay(1)` busy-waits with proper blocking/selector-based mechanisms.
- **AppIconCache moved to car**: Car-side icon cache persists source PNGs (192x192) to disk. `prepareAll()` decodes+resizes all icons on background thread before grid appears; `getPrepared()` is O(1) ConcurrentHashMap lookup with zero I/O during scroll. Eliminated per-tile decode and crash on fast scroll.
- **AppTile simplified**: Removed per-tile StateFlow collect, lazy DropdownMenu, and click ripple effects. Lightweight tiles with clickable instead of combinedClickable for main tap.
- **App grid dedup**: Fixed LazyGrid crash by deduplicating items by packageName.

### v0.17.0-dev-01 (2026-04-30)

- **Notification per-item dismiss and Clear All**: Car notification screen now has per-item dismiss buttons with slide-out animations and a "Clear All" header button. New protocol messages: `NOTIFICATION_CLEAR` (0x04) and `NOTIFICATION_CLEAR_ALL` (0x05) on data channel. Per-item icons from phone's `iconPng` payload.
- **App context actions**: Long-press on app tiles (launcher) and nav bar recent apps shows dropdown menu with Uninstall and App Info. Uninstall propagation via `APP_UNINSTALL` (0x1B) / `APP_UNINSTALLED` (0x06). App Info displays car-side dialog with `APP_INFO_DATA` (0x07) metadata from phone. Context menu actions route through VD server for shell-level access.
- **Back button fix**: GO_BACK now closes activities one-by-one before returning to the home menu, using proper stack tracking via `dumpsys activity activities` on the VD server.
- **Samsung DeX / Desktop Mode DPI** (reverted): Initial implementation using `UiModeManager.currentModeType` detection and 213dpi was reverted in dev-02. Replaced by VD-level flag removal approach.

### v0.16.0 (2026-04-29)

- **Shizuku**: App now appears in Shizuku authorized apps list (added ShizukuProvider ContentProvider). Settings card opens Shizuku app directly for permission management.
- **Shizuku exec fix**: Fixed EBADF from ParcelFileDescriptors in binder transactions by dup'ing FDs before reading. `pm install` via Shizuku for silent self-update.
- **Shizuku mode on car**: Car connection no longer stuck on "Waiting for WiFi" when Shizuku is active — gateway IP retry loop no longer breaks after first attempt.
- **Version check switched to versionName**: Car app updates now compare versionName strings (semver-aware) instead of versionCode integers, enabling pre-release car updates.
- **Security hardening**: Removed unused `RECORD_AUDIO` and `SYSTEM_ALERT_WINDOW` permissions. Accessibility service no longer listens for `typeAllMask` events (only uses `dispatchGesture`).
- **App grid performance**: Fixed crash during fast scroll on car display. `GridCells.Adaptive` → `GridCells.Fixed` with calculated columns. Per-tile lazy bitmap decode with `inSampleSize=2` + `RGB_565`.
- **Network stability**: Phone-side `NetworkCallback` now filtered to `TRANSPORT_WIFI` only, ignoring 3G/4G mobile data fluctuations.

### v0.15.0 (2026-04-28)

- **Phone service auto-start**: `ConnectionService` auto-starts when the phone app is opened (e.g. via car USB ADB), removing the need to manually press Start.
- **Car no longer clears phone task**: Removed `--activity-clear-task` from car's USB ADB phone launch.
- **Share Logs button**: "Share Logs" button on the main screen zips all `*.log` files from `/sdcard/DiLinkAuto/` and shares via Android share sheet. `FileLog.zipLogs()` creates a `dilinkauto-logs.zip`.
- **Encoder configuration**: Adjusted to 8Mbps CBR Main profile for broader device compatibility. Added backpressure (drops non-keyframes when write queue exceeds 6 frames).
- **VideoDecoder catchup**: Four graduated speedup zones (normal, gentle 1.5x, medium 2x, aggressive 3x) for smoother latency recovery.
- **French translation**: Added French (fr) to the existing 7 languages (now 8 total).
- **Update check on app open**: Self-update check runs immediately when the app opens, with update notification and re-check button.
- **Distribution channel selector**: Settings card to choose between stable releases and dev prereleases for self-update.
- **CarLaunchScreen redesign**: Two-column layout optimized for wide car displays.
- **Phone app UI refactor**: Reorganized main screen, fixed install flow bugs.
- **Onboarding improvements**: Car setup prerequisites, enhanced install progress UI, improved How to Connect card.
- **Car UI two-mode separation**: Launch screen (full-screen, connection-focused) and streaming mode (nav bar + content). Smooth transition when app list arrives.
- **Video artifact fixes**: Smart decoder drops + graduated catchup + encoder backpressure eliminate visual artifacts.
- **Touch input fixes**: Correct coordinate mapping at fixed 480dpi VD server DPI, incremental touch dispatch on MOVE, tap gesture and manual IP fixes.
- **Screen restore and network stability**: Display restore after USB disconnect, network callback improvements.
- **Internationalization**: All new UI strings translated to 8 languages (en, pt-BR, ru, be, fr, kk, uk, uz).
- **CI/CD automation**: 6 dedicated workflows — validation (`build.yml`, `build-develop.yml`), pre-release on `-dev` tags (`build-pre-release.yml`), release on `vX.Y.Z` tags (`build-release.yml`), main→develop back-sync (`sync-main-to-develop.yml`), and autonomous issue-agent (`issue-agent.yml`).

### v0.14.0

- **Shared version source**: Version code/name now in gradle.properties — single edit for both apps.
- **MAX_PAYLOAD_SIZE 2MB → 128MB**: App list with 136+ PNG icons exceeded 2MB causing ProtocolException and connection drops.
- **Display restore fix**: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` restores display after USB disconnect, even when VD server dies without cleanup.
- **POCO F5 compatibility**: `FLAG_KEEP_SCREEN_ON` prevents screen lock during streaming. Touch input confirmed working on POCO F5 with Xiaomi 17 Pro Max.
- **Car-side touch logging**: MirrorScreen touch events and sendTouchEvent success logged for debugging.
- **Developer credit in About**: "Developed with ❤" with GitHub link in all 7 languages.

### v0.13.1 — First Release (2026-04-26)

- **Onboarding flow**: Guided first-launch permission setup (All Files, Battery, Accessibility, Notifications). Auto-detects grants, polling fallback for dialog-style settings.
- **Self-update (UpdateManager)**: Checks GitHub Releases API, downloads APK with progress, installs via system package installer. 6-hour cooldown.
- **Main view reorganization**: Main screen focused on daily use (connection guide, status, start/stop, updates). Settings screen with permissions, car install, about, and donation links.
- **USB + WiFi install on car**: Parallel subnet scanner probes all 254 IPs for car ADB. Combined with ARP/neighbor/gateway discovery. USB host attempted but car USB-A is host-only.
- **VD server now Kotlin Gradle module**: Depends on :protocol and kotlinx-coroutines. Shares NioReader, FrameCodec.writeAll.
- **Performance**: Eliminated intermediate ByteArray allocation per frame in encoder. NioReader initial capacity 256KB. isKeyFrame cached in FrameData.
- **Encoder**: CBR 8Mbps, Main profile, configurable FPS (default 30, car requests 60), PRIORITY 0 (real-time). I_FRAME_INTERVAL=1s. `repeat-previous-frame-after`=500ms for static content.
- **Donations**: GitHub Sponsors and Pix (Brazil) badges in README and app settings.
- **Adaptive vector icon**: Car silhouette with wireless signals, applied to both phone and car apps.
- **Internationalization**: String resources in English, Portuguese (pt-BR), Russian (ru), Belarusian (be), French (fr), Kazakh (kk), Ukrainian (uk), and Uzbek (uz).
- **Release signing**: Fixed keystore with strong password. CI builds signed release APKs via GitHub Secrets.

### v0.13.0 — USB ADB Auth Fix (2026-04-25)

Root cause found and fixed: `Signature.getInstance("SHA1withRSA")` double-hashes the ADB AUTH_TOKEN. ADB's 20-byte token is a pre-hashed value — AOSP's `RSA_sign(NID_sha1)` treats it as already hashed. Now uses `NONEwithRSA` with manually prepended SHA-1 DigestInfo ASN.1 prefix (prehashed signing). "Always allow" now persists correctly — AUTH_SIGNATURE accepted on reconnect without dialog.

### v0.13.0 — Display Power + Key Encoding (2026-04-25)

- **Display power via SurfaceControl (Android 14+)**: Loads `DisplayControl` from `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader()` + `android_servers` native library. Falls back to `cmd display power-off/on` if reflection fails.
- **Screen restore on disconnect**: Phone's `VirtualDisplayClient.disconnect()` runs `cmd display power-on 0` + `KEYCODE_WAKEUP` as safety net when VD server process is killed before cleanup.
- **ADB key encoding rewrite**: Rewrote `encodePublicKey()` matching AOSP reference exactly — fixed constants, explicit `bigIntToLEPadded()`, struct header logging.
- **Decoder catchup**: When queue exceeds `100ms * TARGET_FPS / 1000` frames (6 at 60fps), skips every other non-keyframe. Image still moves at 2x speed, gradually catches up without jumping.
- **Car log buffer**: 200 → 10,000 messages. USB ADB auth logs now survive until control connection flushes them.

### v0.12.5 — Connection Stability (2026-04-24)

- **Smart network callback**: `onLost` now checks if the lost network is the one carrying the connection. Ignores unrelated drops (mobile data cycling). Previously, any network loss killed the streaming session.
- **USB ADB auth diagnostics**: Full auth flow logging routed through carLogSend → phone FileLog. Revealed that AUTH_SIGNATURE is rejected every time (phone's adbd doesn't recognize stored key). Under investigation.
- **AUTH_RSAPUBLICKEY key preview logging**: Logs first/last bytes of the public key sent to the phone for comparison with standard ADB format.

### v0.12.0–v0.12.4 — Bug Fixes & Polish (2026-04-24)

- **Touch input fixed**: `handleInputFrame` dispatched on `Dispatchers.IO` (was Main, caused `NetworkOnMainThreadException` on localhost socket write)
- **VD server NIO command reader**: Fixed infinite loop — `break` inside switch only broke out of switch, not the parse loop. Now uses `break parseLoop;` labeled break.
- **App launch dedup**: Removed `--activity-clear-task` from `am start`. Existing apps resume instead of restarting.
- **Bitrate**: Set to 8Mbps CBR (adjusted from 12Mbps in later releases for device compatibility).
- **FPS configurable**: Added `targetFps` field to HandshakeRequest. Car requests 60fps. VD server accepts FPS as command-line arg, uses it for encoder `KEY_FRAME_RATE` and `FRAME_INTERVAL_MS`.
- **Nav bar**: 72dp → 76dp, icons 32dp → 40dp, row height 52dp → 60dp, text 12sp → 14sp.
- **Launcher app icons**: 40dp → 64dp, grid cells 140dp → 160dp, text bodyMedium → bodyLarge.
- **Search bar keyboard**: `windowSoftInputMode="adjustNothing"` + `imePadding()` on TextField. Keyboard doesn't push the activity, only the search bar moves.
- **Notifications**: Dedup by ID (progress updates replace existing), progress bar support (determinate + indeterminate), tap-to-launch owner app on VD + switch to mirror view.
- **Recent apps**: `pruneUnavailable()` removes apps no longer on phone when app list updates.
- **USB ADB key storage**: Priority order: `/sdcard/DiLinkAuto/` → `getExternalFilesDir` → `getFilesDir`. Migration searches all locations.
- **Update flow**: Phone sends `UPDATING_CAR` message. Car shows "Updating car app..." status and doesn't reconnect.
- **Update flow crash fix**: Car skips video/input connection when `updatingFromPhone` flag is set.
- **VideoDecoder/UsbAdbConnection logSink**: Car-side logs routed through protocol to phone's FileLog.

### v0.11.0–v0.11.3 — Non-Blocking Pipeline + Encoder Fix (2026-04-24)

- **VideoConfig**: Shared `TARGET_FPS` and `FRAME_INTERVAL_MS` constants. All video-path waits/polls capped at frame interval.
- **SurfaceScaler periodic re-draw**: Always calls `glDrawArrays + eglSwapBuffers` every frame interval, even when no new frame from VD. Only calls `updateTexImage` when a new frame is available. Feeds encoder on static content.
- **VD server NIO**: Replaced blocking `DataOutputStream/DataInputStream` with NIO write queue (`ConcurrentLinkedQueue<ByteBuffer>`) + Selector-based command reader. No blocking I/O anywhere in the pipeline.
- **Encoder poll**: `dequeueOutputBuffer` timeout reduced from 100ms to `FRAME_INTERVAL_MS` (16ms at 60fps).
- **VideoDecoder queue poll**: 100ms → `FRAME_INTERVAL_MS`.
- **NioReader select timeout**: 100ms → `FRAME_INTERVAL_MS` (configurable via constructor param).
- **Connection writer park**: 50ms → `FRAME_INTERVAL_MS`.
- **VirtualDisplayClient accept loop**: 100ms → `FRAME_INTERVAL_MS`.
- **VideoDecoder early start**: Starts on offscreen SurfaceTexture when first CONFIG frame arrives (before MirrorScreen). MirrorScreen restarts decoder with real TextureView surface.
- **VideoDecoder queue**: 3 → 30 frames. Frames queued even before `start()` is called.
- **FileLog**: File-based logger (`/sdcard/DiLinkAuto/client.log`) bypasses HyperOS logcat filtering. Rotation: archives as `client-YYYYMMDD-HHmmss.log`, keeps 10 max.

### v0.10.0 — 3-Connection Architecture (2026-04-24)

Split single multiplexed TCP connection into 3 dedicated connections to eliminate cross-channel interference causing video stalls:
- **Control connection** (port 9637): handshake, heartbeat, app commands, DATA channel
- **Video connection** (port 9638): H.264 CONFIG + FRAME only (phone → car)
- **Input connection** (port 9639): touch events only (car → phone)

Each connection has its own `Connection` instance with independent SocketChannel, NioReader, and write queue. Heartbeat/watchdog on control only.

### v0.9.2 — Diagnostic Build (2026-04-23)

Comprehensive logging for investigating video frame stall after ~420 frames:
- **Video relay loop**: logs before/after readByte, payload size, unknown msgTypes
- **NioReader**: logs when channel.read() returns 0 (every 100th occurrence with buffer state)
- **Connection writer**: logs every 60 video frames (count, size, queue depth, stalls), logs write stalls
- **Writer stall fix**: `Thread.yield()` → `delay(1)` in writeBuffersToChannel — releases IO thread back to coroutine pool instead of busy-waiting (investigation finding: Thread.yield starved the video relay coroutine)
- **Frame listeners**: non-video frame handlers dispatched async (`scope.launch`) so heavy processing (app list decode) doesn't block the reader from draining TCP

### v0.9.0-v0.9.1 — Write Stall Investigation (2026-04-23)

Investigating root cause of video stall. Added TCP buffer size logging, write stall diagnostics.
- Confirmed TCP send buffer freezes at 108,916 bytes remaining during app list send
- Confirmed video frames themselves have zero write stalls (queue=0 during video)
- Confirmed USB ADB key is stable (LOADED fp=c4e88a05) — repeated auth is HyperOS behavior

### v0.8.4-v0.8.8 — Bug Fixes + Log Routing (2026-04-23)

- **Car log routing**: All car-side `Log.*` calls routed through `carLogSend()` which sends via DATA channel `CAR_LOG` to phone. Phone logs with tag `CarLog` in logcat. Buffer up to 200 messages before connection is established.
- **VD server launch reverted** to `shellNoWait` + `exec app_process` (v0.6.2 approach). The `setsid`/`nohup` detachment broke localhost connectivity. VD server dies on USB disconnect but recovers on re-plug.
- **VD ServerSocket**: `startListening()` opens synchronously, `waitForVDServer()` skips if already waiting
- **USB ADB key persistence**: `getExternalFilesDir` with writability check + migration from `getFilesDir` + fingerprint logging
- **ClosedSelectorException**: `selector.isOpen` checks in writer and NioReader
- **Infinite recursion fix**: bulk Log→carLogSend replacement accidentally hit carLogSend itself

### v0.8.3 — Final Polish + VD Wait Fix + USB Key Diagnostic (2026-04-23)

Final milestone + bug fixes:
- **VD wait guard**: `waitForVDServer` skips if already waiting — prevents closing/reopening ServerSocket when car sends multiple handshakes during reconnect (root cause of VD server unable to connect, confirmed via phone logcat showing 4x `startListening` in 45s)
- **USB key diagnostic**: Car writes key info (`LOADED`/`GENERATED` + fingerprint + path) to `/data/local/tmp/car-adb-key.log` on the phone after USB ADB connect. Enables diagnosing whether auth repeats because key changes or phone doesn't persist "Always allow."
- **M3**: Batched multi-touch — new `TOUCH_MOVE_BATCH` (0x04) message type carries all pointers in one frame. MOVE events batched on car side, unbatched on phone side. Reduces syscalls from N*60/sec to 60/sec for N-finger gestures.
- **M10**: Eject state persisted to SharedPreferences — survives car app kill/restart. Cleared on USB re-plug or ACTION_START.
- **L4**: NioReader uses heap ByteBuffer instead of direct — deterministic GC cleanup.

### v0.8.2 — Polish + VD ServerSocket + USB Key Persistence (2026-04-23)

6 polish fixes + 2 critical fixes:
- **Hotfix**: VirtualDisplayClient split into `startListening()` (synchronous bind) + `acceptConnection()` (async wait). ServerSocket opens BEFORE handshake response — fixes VD server unable to connect on localhost:19637.
- **USB key persistence**: Key storage uses `getExternalFilesDir` with writability check + migration from `getFilesDir`. Fingerprint logging on each connect to diagnose whether key changes between connections.
- **M12**: `checkStackEmpty` uses simpler `grep -E` instead of fragile `sed` section parsing
- **L2**: VD server localhost socket buffers set to 256KB
- **L3**: Decoder uses `System.nanoTime()/1000` for timestamps (was fixed 33ms increment)
- **L5**: `UsbAdbConnection.readFile()` uses read loop + try-with-resources
- **L6**: SurfaceScaler HandlerThread properly quit on stop
- **L7**: App icons increased from 48x48 to 96x96px

### v0.8.1 — Touch + Decoder Performance + Hotfixes (2026-04-23)

4 performance optimizations + 2 crash fixes:
- **M2**: `checkStackEmpty()` runs on background thread — command reader no longer blocked for 300ms+ after Back press
- **M4**: Pre-allocated `PointerProperties[10]` + `PointerCoords[10]` pools in VD server — eliminates per-touch GC pressure
- **M5**: Decoder frame queue reduced from 6 to 3 (200ms → 100ms latency bound)
- **M6**: `cmd display power-off 0` runs on fire-and-forget thread — removed jitter from touch injection path
- **Crash fix**: `ClosedSelectorException` in Connection writer + NioReader — added `selector.isOpen` checks and catch block. Race: `disconnect()` closes selectors while reader/writer coroutines still executing.
- **Bug fix**: `usbConnecting` reset in `startConnection()` now also guarded by `usbAdb == null` (second location of USB auth race, first was fixed in v0.7.3)

### v0.8.0 — I/O Pipeline Performance (2026-04-23)

3 I/O performance optimizations + hotfix:
- **H2**: Gathering writes — `channel.write(ByteBuffer[])` coalesces header+payload into single syscall/TCP segment
- **H3**: Video relay allocates exact-sized `ByteArray(size)` directly — removes intermediate `relayBuf` + `copyOf`
- **M1**: VD server wraps DataOutputStream in `BufferedOutputStream(65536)` — coalesces small localhost writes
- **Hotfix**: `waitForVDServer()` called BEFORE sending handshake response — ensures ServerSocket on :19637 is open before car deploys VD server (v0.7.4 regression: sequencing put VD wait after response, causing VD server to fail connecting)

### v0.7.4 — Write Queue + Flow Sequencing (2026-04-23)

Write architecture change + flow improvements:
- **Write queue**: Replaced `synchronized(outputLock)` with lock-free `ConcurrentLinkedQueue` + dedicated writer coroutine. Writer uses `delay(1)` when TCP send buffer full (releases IO thread to pool). No more blocking other coroutines during writes.
- **H10**: Handshake → auto-update → VD deploy sequenced. Auto-update pauses initialization, disconnects, waits for car reconnect.
- **H11**: Progressive car status messages: "Preparing..." → "Starting..." → "Waiting for video stream..."
- **H12**: Car shows "Check phone for authorization dialog" during USB ADB connect
- **Bug fix**: VD server launches home activity on VD after creation — encoder gets content immediately
- **Bug fix**: `usbConnecting` only resets when `usbAdb == null` — prevents duplicate USB-ADB auth dialogs

### v0.7.3 — Network Resilience + HyperOS Freeze Fix (2026-04-23)

Network resilience + critical HyperOS fix discovered via logcat evidence:
- **Battery exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prevents HyperOS "greeze" from freezing the client app while screen is off. Root cause of "frames only during touch" bug: VD server (shell process) produced 960+ frames but the client app's NioReader was frozen by OS power management. Prompt shown on first launch.
- **C4/M11**: Car WiFi track now retries gateway IP every 3s (was one-shot). Added `ConnectivityManager.NetworkCallback` on car that re-triggers WiFi track when WiFi becomes available. Handles hotspot enabled after USB plug.
- **H9**: Phone proactively disconnects on network loss in CONNECTED/STREAMING state (was: wait 10s for heartbeat timeout). `onLost` → `cleanupSession()` → listen loop restarts. `onAvailable` only resets when WAITING (no disruption to active connections).
- **Bug fix**: VD server launches home activity on VD after creation (`am start --display <id> HOME`) — ensures encoder has content immediately.
- **Bug fix**: `usbConnecting` only resets when `usbAdb == null` — prevents duplicate USB-ADB auth dialogs.

### v0.7.2 — Car-Side Stability + Selector Fix (2026-04-23)

5 stability fixes + critical NioReader fix:
- **H1**: Replaced `delay(1)` polling with **Selector** in NioReader — `selector.select(100)` wakes instantly via epoll when data arrives. Fixes video not streaming (frames only flowed during touch on Android 10 car due to `delay(1)` taking 10-16ms). Added `wakeup()`/`close()` for clean shutdown from disconnect.
- **H7**: `@Volatile` on `wifiReady`, `usbReady`, `vdServerStarted`, `usbConnecting` — prevents stuck CONNECTING state
- **H8**: `VideoDecoder.stop()` joins feed thread (2s timeout) before `codec.stop()` — prevents native crash
- **M7**: Removed double `cleanup()` in VD server — prevents IllegalStateException on double release
- **M8**: `cleanupSession()` resets `_serviceState` to WAITING — prevents stale UI during reconnect delay
- **M9**: `onCreate()` clears static `activeConnection` and `_serviceState` — prevents stale state on service restart

### v0.7.1 — Critical Bug Fixes (2026-04-23)

6 critical/high fixes from comprehensive review:
- **C1**: `writeAll()` 5s write timeout — prevents system freeze on full send buffer
- **C3**: Auto-update attempt flag — breaks infinite update/restart loop
- **C5**: WakeLock 4h auto-release — prevents battery drain on abnormal exit
- **C6**: `@Volatile` on VirtualDisplayClient channel/reader + timeout-protected writes
- **H5**: `Connection.connect()` try/catch — closes SocketChannel on cancellation
- **H6**: Disconnect listener wrapped in try/catch — prevents exception propagation

### v0.7.0 — Full NIO + Service Fix (2026-04-23)

All socket operations converted to non-blocking NIO. mDNS registration no longer blocks listen loop. Version code read at runtime via PackageManager.

**Changes:**
- **NioReader**: New non-blocking buffered reader for SocketChannel (delay(1) polling, coroutine-cooperative)
- **Connection.kt**: SocketChannel stays non-blocking throughout — no more configureBlocking(true) after connect/accept
- **FrameCodec.kt**: Added `readFrame(NioReader)` and `writeFrameToChannel(SocketChannel, Frame)` NIO methods
- **VirtualDisplayClient.kt**: NIO reads (NioReader) + ByteBuffer writes, channel stays non-blocking
- **VirtualDisplayServer.java**: NIO SocketChannel for connect (non-blocking finishConnect with retry)
- **ConnectionService.kt**: probePort() converted to NIO; mDNS registration launched in background with 5s timeout (fixes service not starting without WiFi)
- **Version code**: Removed `APP_VERSION_CODE` constant — both apps read versionCode at runtime via `PackageManager.getPackageInfo()`

### v0.6.2 — Parallel Connection Model + Auto-Update (2026-04-23)

Major architecture rewrite: parallel WiFi + USB tracks, NIO non-blocking sockets, phone-driven auto-update, multi-touch via IInputManager.

**Working:**
- **Parallel connection state machine**: WiFi discovery + USB ADB run simultaneously, VD deploys when both ready
- **Auto-update**: Phone detects outdated car app on handshake, pushes update via WiFi ADB (dadb)
- **Phone deploys VD JAR**: Extracted to `/sdcard/DiLinkAuto/vd-server.jar` on launch (CRC32 checked)
- **Car APK embedded in phone**: Build system compiles car APK into phone's assets
- **NIO non-blocking sockets**: All accept/connect use `ServerSocketChannel`/`SocketChannel` — instant cancellation, no EADDRINUSE
- **Multi-touch input**: Direct MotionEvent injection via `ServiceManager → IInputManager` (supports tap, swipe, pinch)
- **Screen power management**: `cmd display power-off 0` during streaming, proximity/lift wake disabled, throttled re-power-off after touch injection
- **State machine recovery**: `connectionScope` cancels all coroutines on disconnect, exponential backoff reconnect
- **User disconnect**: Eject button stops reconnection (stays IDLE)
- **App search**: Search field at bottom of app grid, apps sorted alphabetically
- **Notification panel**: Bell icon in nav bar with badge count
- **72dp nav bar**: Larger icons (32dp) and text (12sp) for car displays
- **Handshake version check**: `appVersionCode` field in HandshakeRequest, `vdServerJarPath` in HandshakeResponse
- **Network change handling**: Phone resets listen loop on network interface changes (hotspot toggle)
- **H.264 encoding**: 8Mbps CBR, High profile, low-latency mode
- **Handshake timeout**: 10s timeout with proper cancellation (no stale timeouts)

**Architecture changes from v0.5.0:**
- Removed hotspot SSID polling/WiFi auto-connect from car (simplified)
- Moved UsbAdbConnection + AdbProtocol to protocol module (shared by both apps)
- VD server connects TO phone (reverse connection) instead of phone connecting to VD server
- VD server exits on phone disconnect (one-shot, car re-deploys if needed)
- Phone extracts VD JAR to shared storage, car reads path from handshake

### v0.5.0 — USB ADB + Automated Setup (2026-04-22)

Major architecture change: the **car** deploys the VD server to the phone via USB ADB. Wireless Debugging eliminated.

### v0.4.0 — GPU-Scaled VirtualDisplay (2026-04-22)

Apps render at phone's native DPI (480dpi), GPU downscales to car viewport. SurfaceScaler EGL/GLES pipeline.

### v0.3.0 — Persistent Navigation Bar (2026-04-21)

Car UI with always-visible left nav bar, TextureView, real app icons.

### v0.2.0–v0.2.3 — Virtual Display Foundation (2026-04-21)

VD creation, self-ADB, resilient server, multi-app support.

### v0.1.0–v0.1.1 — Initial Implementation (2026-04-21)

Project created. Screen mirroring on emulators.

---

## Fork Changes (post v0.18.0-dev)

The upstream repo went quiet after `v0.18.0-dev` (2026-05-09). This fork (`ID-VerNe/dilink-auto-android`) then landed a directed set of changes targeting Chinese ROM phones (Xiaomi HyperOS, Meizu, etc.) paired with BYD DiLink car head units. The reference car is a BYD Qin PLUS DM-i 2023 Champion 55KM Leading trim — DiLink 4.0 low-spec (Snapdragon 439, 8x Cortex-A53, Adreno 505, 4GB RAM, 16GB eMMC, 1280x800, 2.4GHz-only WiFi, Android 9 / API 28, H.264 hardware decode capped at 1080p). All work is on `main` (git-flow develop model dropped). Sections below are newest-first.

### v1.0.3 (2026-11-13)

- **Complete Audio Mirroring Closed-Loop (issues #67, #70, #72, #73, #79, #84)**:
  - **Port 9640 & Framing (S1)**: `AUDIO_PORT = 9640` in `Ports.kt`. Dedicated TCP connection independent of video to avoid 1MB video IDRs head-of-line blocking 341B audio frames. Binary framing via `AudioPayload` with out-of-band `CONFIG` (`csd-0`, AAC-LC 48kHz stereo, 128kbps) and `FRAME` (pts timestamp + raw AAC-LC access units).
  - **Capture, Encoding & Pacing (S2)**: `AudioRecord` with `REMOTE_SUBMIX` captures system audio rendered to the VirtualDisplay. `AudioPipeline` + `AudioEncoderSelector` encode to AAC-LC. Constant 46.875 fps pacing (1024 samples/frame at 48kHz = 21.333ms per frame) with synthetic silence frame injection during audio pauses (`AudioSpec.kt`, `AudioPacing.kt`), preventing jitter buffer underruns and delay accumulation.
  - **Port Binding & Receiver Playback (S3)**: `PortAcceptor` on `vd-server` binds port 9640 (accepting only pinned `CAR_HOST`). `CarSessionCoordinator` connects to 9640 and hands the stream to `AudioPlayer`. Internal 150ms jitter buffer (configurable 60~200ms) with `AudioTrack` free-running playback.
  - **Bidirectional Feature Negotiation (S4)**: Dual-sided `FEATURE_AUDIO` negotiation via `isAudioNegotiated(request, response)`. Client provides a persisted audio toggle; car UI dynamically enables or dims the audio switch based on phone capability.
  - **E2E Scenario & Silence Metrics (S23)**: S23 end-to-end audio mirroring test scenario landed in `e2e-scenarios.md`. Periodic logging in `AudioPlayer` tracks silence frame ratios (`silenceFrames`/`totalFrames`), closing the contract against false-green silent passes (issue #84).
- **Two Rounds of SRP/DRY Architectural Decoupling (Refs #87, #92)**:
  - `:protocol-core` & `:protocol`: Extracted `NioWriter` (SocketChannel spin-blocking writes with timeout), `BackoffPolicy` (parameterized exponential backoff with jitter), `DecodeLivenessTracker` (unified decode vitality tracking and baseline management), `ConnectionFactory`, `ConnectionKeepAlive`, `UsbAdbKeyStore`, `Ipv4Validator` (strict/loose), and `AppIconLimits` (max icon dim 512, max bytes 1MB). Cleaned up dead sync/push methods in `UsbAdbConnection` and unified UTF-8 writing in `Messages.kt`.
  - `:vd-server`: Extracted `AsyncSocketSender` (unified non-blocking socket writer), `AudioEncoderSelector`, `ProcessWatchdog` (watchdog loop separated from stop-sentinel watcher), `VideoEncoderSelector`, `PortAcceptor` (multi-port listener with `CAR_HOST` enforcement), `ActivityDumpParser` (bounds-checked dumpsys window/display parsing), and `VdDisplayHealer`. Migrated `LifecycleWriter` to `AsyncSocketSender`.
  - `:app-client`: Decoupled `ConnectionService`, extracting `ShizukuVdDeployer`, `ShizukuNav`, `ClientNetworkMonitor`, `CarAppInstallHost`, `AllowlistRepository`, and `SessionState`. Formally marked `InputInjectionService` as `@Deprecated` and severed its call path.
  - `:app-server`: Substantially slimmed `CarConnectionService` down to 407 lines, extracting `CarSessionCoordinator` (manages 4-channel lifecycle 9637/9638/9639/9640 and re-handshake), `CarAdbTracker` (USB/TCP ADB dual tracks), and `CarAppListReceiver` (`AppIconLimits` security gate).
  - `:app-desktop`: Aligned with `AppIconLimits`, `DecodeLivenessTracker`, and `BackoffPolicy`.
- **Stability and Diagnostics Fixes**:
  - **IDR Request Throttling (#75)**: Unified IDR request throttling thresholds across car server and VD server, eliminating the 500-999ms dead zone during keyframe recovery.
  - **Dumpsys Display Section Parsing (#77)**: Added upper bounds and task-header checking in display dumpsys parsing, eliminating task duplication and cross-display activity leakage.

### Full-module test coverage — 18 files, 139 cases (commit 8157c86, 2026-10-10)

The repo carried no meaningful unit-test suite (`:protocol` had no unit-test source set at all). One pass filled the gap across all six Gradle modules, and the testability-seam pass below added the rest. Full details — coverage matrix, the cross-module invariants now pinned, and the hazards left to product decisions — live in [IMPLEMENTATION_REPORT_TESTING.md](IMPLEMENTATION_REPORT_TESTING.md).

| Module | New test files | Pinned production objects | Cases |
|---|---|---|---|
| `:protocol` | `AdbProtocolTest`, `AdbCryptoTest` | ADB frame header / magic / checksum / round-trip / constants; `bigIntToLEPadded`, `SHA1_DIGEST_INFO`, `fingerprint`, `signAuthToken`, `buildAuthReply` | 25 |
| `:protocol-core` | `VideoConfigTest`, `VdDeployArgsFormatTest`, `ImeRestoreGuardTest`, `H264NalParserRefIdcTest`, `AsyncLogQueueTest`, `VdDeployConstantsTest`, `WifiGatewayIpTest` | every `calculateOptimalDpi` branch; `VdDeployArgs.format` layout / scaling / bitrate edges; `shouldRestoreIme`; H264 `nal_ref_idc` recovery; `AsyncLogQueue` overflow / FIFO / suspend / close; kill / stop / probe commands locked verbatim; gateway IP byte order | 34 |
| `:vd-server` | `AdaptiveBitrateEdgeTest`, `DisplayPowerControllerRestoreTest`, `TouchInjectorFallbackTest`, `CarCommandRouterDispatchTest` | adaptive-bitrate threshold edges / truncation / sync latch; the S-M10 sentinel fallback in `restoreIme`; touch normalization scaling + shell fallback; shell-quoted `am` / `pm` / `input` routing + `SET_DISPLAY_POWER` | 28 |
| `:app-client` | `VersioningTest`, `AppCategorizerTest`, `InstallStatusTest` | version parse / compare (sentinel + SNAPSHOT edges); ordered app-category keywords; install-status string classification + stage index (localization hazard included) | 38 |
| `:app-server` | `WifiGatewayProbeTest`, `CarCrashReportTest` | gateway lookup fallback; crash-report format contract (header / timestamp / thread / stack / Process State / Caused by / Suppressed) | 8 |
| `:app-desktop` | `HandshakeFactoryTest`, `DesktopConfigTest` | handshake field mapping + `evenAlign` (floor 2, negative fallback); config defaults must come from protocol constants; `copy` renames only | 6 |
| **Total** | **18 files** | | **139** |

- **The test stack is the project's existing convention**: JUnit4 + kotlinx-coroutines-test. No Robolectric / Mockito / MockK. Android modules lean on `isReturnDefaultValues = true` plus hand-written fakes (`ContextWrapper(null)`, a capturing `OutputStream`, canned lambdas). `:protocol` gained `testOptions { unitTests.isReturnDefaultValues = true }` + `junit:junit:4.13.2` — it previously had no test source set at all.
- **Offline verification, all six modules**: `:protocol:test`, `:protocol-core:test`, `:vd-server:test`, `:app-client:testDebugUnitTest`, `:app-server:testDebugUnitTest`, `:app-desktop:test` — every one `BUILD SUCCESSFUL`.
- **The seam follow-up added 44 more test methods** across 7 files (`TcpAdbConnectionTest`, `ConnectionHeartbeatTest`, `CarConnectionServicePolicyTest`, `AssetDeployerExtractTest`, `CarInstallCoordinatorTest`, `CarAppInstallerParseVersionTest`, `FeedGateVideoStreamPipeIntegrationTest`, plus `AdbCryptoTest` additions), for **183 new tests in total** (next section). Counts are literal `@Test` annotations.
- `:protocol-core` also gains `ScreenMode` + `CaptureModePolicy` (the VD → screencast auto-fallback ladder) with tests, for the upcoming dual-mode receiver — see [FEATURE_DUAL_MODE_RECEIVER.md](FEATURE_DUAL_MODE_RECEIVER.md).
- Four defects the audit surfaced are **pinned by characterization tests rather than fixed** — changing them is a product decision, see [Known Issues](#known-issues).

### Testability refactors — behaviour-preserving seams (2026-10-10)

Each item extracts a seam without changing production behaviour, then pins logic that had never been covered. Defaults still point at the previous real values, so existing callers are unaffected.

- **`1b6092c` — ADB seams + a cross-module pipe invariant.** `AdbCrypto.encodePublicKey` switched `android.util.Base64` → `java.util.Base64` (byte-identical, including the single trailing `=`; the 524-byte ANDROID_PUBKEY struct is now assertable on the JVM). `TcpAdbConnection` extracts companion `negotiateMaxPayload(cnxn)` / `readMessageFrom(InputStream)`, so connect negotiation and CRC / bad-magic handling are testable without a socket. Also `FeedGate` ↔ `VideoStreamPipe`: a full pipe's drop-oldest policy can silently evict the CONFIG that `FeedGate` just delivered, and rebuilding the pipe has to replay `initialConfig`.
- **`fa00527` — injectable heartbeat / watchdog.** `Connection.start()` takes `heartbeatIntervalMs` / `heartbeatTimeoutMs` (default 3s / 10s), threaded into the heartbeat and watchdog loops — the liveness contract (an emitted HEARTBEAT is auto-ACKed, the watchdog drops a peer that goes silent, a live peer is never dropped) is now covered in milliseconds.
- **`bbd6fe0` — `AssetSource` seam.** `AssetDeployer` depends on an `AssetSource { fun read(name): ByteArray }` (an `AssetManager` is `final` and cannot be subclassed); production wraps it via `AssetManagerAssetSource(assets)`. Covers extract / `ensureCurrent` full branches, the A-L15 fallback to the usable on-disk jar CRC (not `-1`), and `.tmp` never surviving any outcome.
- **`5d576e8` — `CarInstaller` seam.** New `CarInstaller<S>` interface (connect / readInstalledVersion / pushAndInstall / close), generic over the session handle; `CarAppInstaller` implements `CarInstaller<Dadb>` and owns `close(dadb)`. `CarInstallCoordinator<S>` depends on the interface with injectable `resolveCarIp` / `myVersionName`, plus a null-safe `statusString()` resolver (production `getString` is non-null, so behaviour is identical — only the mockable-jar null is guarded). The whole install state machine is now testable through a fake installer.
- **`6d25390` — reconnect backoff and icon budget as pure functions.** `CarConnectionService` extracts companion `reconnectBackoffMs(failures)` (500ms → doubling → 8s cap with a shift clamp) and `iconBudgetAccepts(total, next)` against the real 64MB `MAX_APP_LIST_ICON_BYTES`; both call sites delegate.

### Security hardening — full-project audit (2026-10-09)

The shared protocol layer and all three endpoints treated every socket peer as trusted, and several decoders could kill the process with a 6-byte frame. The security hardening fixes landed as one batch:

- **`a7d2b72` — the wire protocol against hostile peers** (`P-*`, `S-02`). Every fixed-size `ByteBuffer.getX()` in `HandshakeRequest` / `HandshakeResponse` / `AppListMessage` / `MediaMetadata` / `AppInfoDataMessage` is now `require()`-guarded, and `Connection`'s reader catches `Throwable` (logs the reason, disconnects) instead of dying — there is no `CoroutineExceptionHandler` anywhere in this repo, and `app-server`'s default handler ends in `killProcess`. `MAX_PAYLOAD_SIZE` 128MB → 16MB, with a hard `NioReader` ceiling and `maybeShrink` back at frame boundaries. ADB `parseHeader` rejects negative / oversized `data_len`, and both the USB and TCP read loops finally verify `data_crc`. Mid-frame `NioReader` stalls fail after 10s (an idle wait at a frame boundary is deliberately *not* timed out). `LaunchAppMessage` / `AppUninstalledMessage` validate package-name shape at the decoder boundary (`requirePackageName` / `requireComponentName` exported for the vd-server call sites). Also: truncated icons now fail the decode instead of caching a garbage `iconHash` under a real key, unknown `AppCategory` / `MediaAction` ids throw instead of coercing to a live default, `requireSupportedProtocolVersion()` is finally enforced, `sendMutex` pins enqueue order under backpressure (`limitedParallelism(1)` serializes dispatch, not suspension), a 10s connect deadline, a 500ms graceful-EOF flush before the writer is cancelled, and shell-quoted + shape-checked IME ids.
- **`8601fb1` — vd-server channel authentication and process leaks** (`S-01`, `S-06..S-10`). A new trailing `CAR_HOST` argv (sanitized in `VdDeployArgs`) is pinned by `acceptCarChannel`, which refuses any other peer — 9638 / 9639 were bound on `0.0.0.0` with no authentication at all, running as shell UID. `bindAndAccept` closes both listening sockets in one `finally`, the pipeline thread parks in bounded `parkNanos` slices re-checking `running`, is a daemon, and is signalled on every failure path (an unbounded park with nobody to unpark it wedged the process forever). `cleanup()` releases the encoder and *then* `encoderSurface` (the MediaCodec input surface was never released). New `PointerCap` caps active pointers (oldest evicted, never the current frame's), truncates `pts`, range-validates `pointerId` before pool indexing, and replaces the silent `catch(_:Exception){}` that let a peer sending 11 DOWNs jam touch for the rest of the session. fps is coerced through `VideoConfig.MIN/MAX`, `PipeLog` writes are synchronized on the stream, and the shared `SESSION_TIMEOUT_SENTINEL` maps to 60000 instead of being written back.
- **`1f6836a` — Windows receiver deploy input and adb leaks** (`D-*`). The receiver turned unauthenticated handshake-response fields into an adb deploy command, so a MITM could choose which code ran on the phone via `CLASSPATH`: `AdbDeploy.checkAdbPort` accepts only `Ports.ADB_PORT`, `sanitizeJarPath` whitelists the vd-server.jar location (must equal `VdDeploy.JAR_PATH` or start with `/sdcard/DiLinkAuto/`, `..` rejected), and both gates reject by failing the session with a readable reason. `clampVdDimension` (`DimAlign.evenMin2` + `coerceIn(2, 4096)`) is the single shared implementation — `DimAlign.even` alone maps negatives to ~2^31. `adbDeployer.close()` runs on the session-end path under the same generation guard, so a phone DISCONNECT / EOF reaps the held `adb.exe` (the device-side engine's liveness anchor). Settings mutations funnel through one lifecycle executor (restart and setKeepAwake used to race on a plain var, losing DPI updates), `config.json` writes are atomic (`tmp` + `Files.move`), `deploy()` is a true suspend function serialized by a `deployMutex`, a decode-thread join timeout is counted / logged / escalated, consecutive decoder rebuilds back off 500ms → 8s behind a one-shot escalation latch, PNG icons are header-checked before decoding, timed-out processes are reaped, and `adb.exe` must resolve to an absolute path.
- **`5eae3e7` — shell-quoted deploy command line.** The generated device-side command line quotes the jar path and the log path (a peer-supplied `vdServerJarPath` could otherwise carry shell metacharacters); embedded single quotes are escaped so a path can never break out.
- **`bc2634c` — session generations, loopback lifecycle, honest deploy status** (`A-*`, `S-01`). `cleanupSession` is generation-scoped: each accept mints a session id and a teardown only runs when its id is still active, so a cancelled loop's late `finally` can no longer consume the guard belonging to the next session — that race left the fresh 19647 `ServerSocket` open and the stale VD client streaming, i.e. the leaked-VD failure mode. The lifecycle listener binds `127.0.0.1` and refuses non-loopback peers, so displayId injection is no longer reachable from the LAN; the Shizuku deploy passes `CAR_HOST` so the engine pins its 9638 / 9639 accepts. The blocking part of teardown (CMD_STOP join, disconnect, panel / IME restore) moves to a process-lifetime `teardownScope` — it used to block the main thread up to 1s per call, and `serviceScope` was cancelled by `onDestroy` before the restore could run. `handleHandshake` runs on the handshake job instead of stalling every other frame on the reader coroutine. `CAR_LOG` lines are capped at 8KB (truncated, not dropped) and `FileLog`'s queue is bounded at 2048 — a peer could push a 16MB frame into an unbounded queue and OOM the phone. `installCarApp` is single-flight and validates an explicit IPv4, `CarIpLocator` is serialized behind a `Mutex` over a published subnet snapshot, the Shizuku decision is snapshotted once, and `usesCleartextTraffic="false"` is added as defence in depth.
- **`35d7821` — black-screen self-heal fix** (`S-03..S-M11`). The recovery path called `videoDecoder.stop()` while `CarShell` keeps the streaming layout mounted in CONNECTING, so nothing ever restarted the codec and a triggered recovery left the screen black for the rest of the session; it now resets the black-screen detector state and lets the still-running codec consume the fresh IDR. The WiFi retry loop and the mDNS discovery flow are launched on `trackScope()` (a child of `connectionScope`) so `connectionScope.cancel()` actually stops them, with a `reHandshakeInFlight` guard stopping a callback that lands inside the ~1s re-handshake window from closing the very control connection carrying it. `handleDisconnect` is gated by a `teardownGuard` (it used to re-enter itself into 2-4 concurrent reconnect coroutines). The self-heal gate no longer requires `vdServerStarted` — never set in Shizuku mode, so the primary path got no recovery at all — and instead uses a per-session rebuild budget of 2. `discoveryJob` is cancelled before every relaunch (~450 leaked NsdManager listeners per hour on a flapping link). MediaCodec create / configure / start moves off the main thread onto a `decoderExecutor`. Peer-supplied viewport dims are clamped to 2..4096 before `MediaFormat` (non-positive values crashed the decoder; absurd values suppressed the rotation re-handshake forever). `APP_LIST` icons get a per-icon byte cap and an aggregate budget — a single `APP_LIST` used to be able to OOM a 4GB head unit.

### Windows receiver — `:app-desktop` module (commit 22d3b7a, 2026-10-08)

A desktop receiver that plays the car's role over the same wire protocol, so one phone can be mirrored to either endpoint. The new `:protocol-core` (pure Kotlin/JVM) holds the platform-independent protocol primitives, with `PlatformLog` and `ThreadPriority` as replaceable hooks installed by the three process entry points; `:protocol` depends on it via `api`. The receiver itself is a Compose Desktop application: control connection (9637) → handshake → `VD_PORTS_BOUND` → direct video (9638) and input (9639) to the VD server, JavaCV `FFmpegFrameGrabber` decoding into a reused Swing `Canvas` inside a `SwingPanel`, mouse events mapped through the letterbox content rect into normalized `TouchEvent`s, an app grid over `AppListMessage`, `config.json` in `%APPDATA%\DiLinkAuto\`, and a `jpackage --type app-image` exe with an embedded JRE. Sessions carry a generation so a DPI / hardware-decode change swaps the whole session and rebuilds the video panel with `key(generation)`. The phone's physical panel can be toggled mid-session via the new `SET_DISPLAY_POWER` (0x32) control message on the input port — app-client is unchanged. D3D11VA hardware decode falls back to software on a watchdog, and the sustained-black-screen self-heal is inherited from the shared `BlackScreenDetector`.

- **Verified on device (Shizuku path)**: handshake over 9637, `VD_PORTS_BOUND` sequencing, 24-25fps over 400+ continuous frames, a real-time mirror (the dumped frame hash follows whatever is opened on the phone), and correct teardown (9638 / 9639 closed, 9637 back to listening, no `app_process` left over). Two real bugs surfaced and were fixed here: the Phase-0 d8 input list never gained `protocol-core.jar`, so `app_process` died on `NoClassDefFoundError` with a completely empty `vd-server.log` and the desktop stuck on `WAITING_VD`; and the lock-screen black-frame root cause turned out to be `FLAG_ALWAYS_UNLOCKED` never actually being set (the code tested `0x40`, which is `SUPPORTS_TOUCH`) — flags are now `0x6c49 → 0x7c49`.
- **Audited and fixed**: 15 `WIN-*` findings from the desktop audit were all closed in `aaf5cd5` / `489944f` / `1f6836a`. The highest was WIN-02 — the local `adb shell` process is the device-side engine's liveness anchor, so closing the deployer right after `deploy()` killed the engine before it bound 9638 / 9639.
- **Still open**: the `adb.exe` deploy path (taken when the phone has no Shizuku) has **never been verified on a real device** — the todo in `.plan/windows-client-plan.md`. The remaining Phase-5 extras (multi-monitor / fullscreen, hardware decode and its fallback, keep-awake, the manual phone-screen switch) also still need an on-device pass; the phone-screen switch in particular needs a reinstall so the APK carries the 5e / 5f `protocol-core`.

### v0.18.0-dev-13 — VD leak fix, cleanup idempotency, black screen self-heal

A leaked VirtualDisplay per reconnect cycle turned the car screen black after a few cycles. Root cause: `pkill -9` skipped the JVM shutdown hook, so `PipelineServer.cleanup()` never ran — leaking the VD, keeping the physical panel powered off, and losing the `screen_off_timeout` setting (snapshot captured its own `2147483647` sentinel). Fixed across 12 files:

1. **Two-stage VD stop** (`VdDeploy.stopCommand`). SIGTERM → wait 1s → SIGKILL, as a single shell line. A coroutine cancellation cannot land between the two signals. *(Superseded 2026-10-10: ART never runs the shutdown hook on SIGTERM, so this was equivalent to a force kill — replaced by the stop-request sentinel protocol, see the Fix Tracker row below.)*
2. **Bracket process pattern** (`VdDeploy.PROCESS_PATTERN`). `[P]ipelineServer` prevents `pkill -f` from matching the wrapper shell's own cmdline. *(Boundary measured 2026-10-10: the bracket trick is honoured by `pgrep -f`; `pkill -f` still killed the invoking wrapper shell when run inline — kills now locate with `pgrep` and signal by PID, see the Fix Tracker.)*
3. **Liveness probes** (`VdDeploy.probeCommand` / `probeExitCodeCommand`). `pkill -0` existence check — no signal delivered. *(Superseded 2026-10-10: toybox rejects `pkill -0` with `bad -L '0'`, so the probe always reported GONE — both probes now use `pgrep -f`.)*
4. **VD exit wait** (`ShizukuManager.waitForVdServerExit()` / `VdServerDeployer.waitForVdServerExit()`). Polls until the old engine exits before launching the replacement. Two live engines race for the same VirtualDisplay / DTA / 9638-9639 binds and the loser skips `cleanup()` entirely.
5. **Cleanup idempotency** (`ConnectionService.cleanupGuard`). `AtomicBoolean` guard prevents 7 `cleanupSession()` call sites from firing repeatedly (observed: 1 disconnect → 9 "Force-waking physical display" lines). `resetCleanupGuard()` before each new session.
6. **Process-lifetime PhoneDisplayRestorer scope**. Owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — deliberately not the Service scope, which `onDestroy()` cancels. `NonCancellable` context ensures `cmd display power-reset` cannot be skipped. `inFlight` `AtomicBoolean` collapses concurrent restore requests into one.
7. **`stopVdServer()` returns Boolean**. Was void — callers had no way to know if CMD_STOP succeeded or fell back to shell kill.
8. **`PipelineServer.cleanup()` reorder**. Global window/rotation state reset FIRST (device-wide, not per-display), then move foreground app, then restore panel + IME, then release threads/GL/encoder/VD. Previous order had `virtualDisplay.release()` dead last with nothing after it.
9. **`saveCurrentIme()` before `VirtualDisplayCreator.create()`**. The snapshot used to run after `create()` had already written `screen_off_timeout=2147483647`, so the restore path treated the sentinel as "already the sentinel, nothing to do" and the user's real timeout was lost forever.
10. **`VirtualDisplayCreator` split**. `create()` no longer calls `configureDisplayEnvironment()`. New `configureEnvironment()` must be called after the snapshot.
11. **`DisplayPowerController.restoreSetting()`**. Validates before writing: non-blank, not `null`/`undefined`, numeric > 0. A failed snapshot yields a marker; writing it back would make things worse.
12. **`VideoDecoder.onSustainedBlackScreen`**. Fires only after `BLACK_SCREEN_SUSTAIN_MS` (5000ms) of continuous tiny keyframes. A 3-frame burst during app/VD warm-up is normal and must not trigger a re-handshake — that is the reconnect storm that leaked VDs in the first place. `resetBlackScreenState()` re-arms per session.
13. **`CarConnectionService.rehandshakeForBlackScreen()`**. Rebuilds the phone-side VD when the stream is persistently black. `blackScreenRecoveryInFlight` latch ensures at most one rebuild per session.
14. **`rehandshakeOnExistingControl()` extracted**. Shared by rotation and black-screen paths — tears down video/input on the existing control connection without re-establishing TCP.
15. **`scripts/verify-blackscreen-fix.sh`**. Automated log verification: VD leak check (start count vs cleanup count), cleanup idempotency (disconnect count vs wake count), black-screen self-heal (sustained detection vs rebuild trigger), stop path audit.
16. **Reconnect/close cleanup regression automation** (`scripts/e2e-reconnect-cleanup.sh` + `scripts/verify-reconnect-cleanup.sh`, 2026-10-10). The manual on-device verification loop for the stop-path fixes is now one command: build gate (asserts 0 test failures) → APK `assets/vd-server.jar` dex symbol check (`ShellSyncPoint` / `__DILINK_SYNC__` / `awaitIdle` / `pgrep -f` / `stop-request`) → install + relaunch → isolated `DILINK_DESKTOP_HOME` desktop session → UI-driven 「应用并重连」 and window close → live device assertions (timeout sentinel in session, restore to baseline after cleanup, engine gone) → offline log assertions (probe ALIVE→GONE transitions, 「引擎已优雅退出」 with no 「强制 kill」, elapsed < 12s budget, `__DILINK_SYNC__` drain echo, `Cleanup complete`, second handshake). `debug-logs/uitest/win_auto.py` gained a `close` (WM_CLOSE) subcommand as a pointer-click fallback. First live run hardened both sides of the UI coupling: (a) **all polling loops now use real elapsed time** (`SECONDS` deadline) instead of `iteration-count × sleep` — this box forks/execs at ~1s per call (AV real-time scanning), which turned a nominal 150s budget into 626s; (b) `win_auto.py focus` now uses `AttachThreadInput` (the real fix for the Windows foreground lock, verified `attached=True ok=True`) because the old ALT-tap + `SetForegroundWindow` was refused and the injected clicks were being swallowed; the E2E also activates the window by clicking the title bar and verifies each click landed (new 「优雅停止设备侧引擎」 in `desktop.log`) with one automatic retry. **For the UI steps to work, the desktop must be left alone while the script runs.** Final run after hardening: **32 pass / 0 fail in 2m48s** (graceful exits 6462/6499ms < 12s budget; artifacts `debug-logs/e2e/20261010-210104/`). This run doubles as real-device closure evidence for desktop WIN-01/02/04 — `win_auto.py` / `poll_engine.py` were force-added to the repo so a fresh clone can run the E2E.

### Architecture — direct VD streaming (no phone relay)

VD Server binds `9638` (video) and `9639` (input) directly on `0.0.0.0`; the car talks to the VD without the phone app as middleman. Was 4 socket ops + 2 process context switches per frame; now 2 socket ops + 0 context switches. The phone is a pure orchestrator: handshake, VD lifecycle (`VD_PORTS_BOUND` control message), car log routing. `VirtualDisplayClient` simplified to lifecycle-only. The lifecycle channel is on `localhost:19647` (the upstream docs said `19637` — that was wrong; `Discovery.LIFECYCLE_PORT = 19647`). Approximately -356 lines across 7 files.

### DiLink 4.0 low-spec performance (Snapdragon 439, nine fixes)

1. **Encode dims capped to 1920x1080** (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`). Decoupled from VD dims via the `EW EH` args; the VD stays scaled-up to preserve the IME-crop fix, the encoder uses car-reported dims clamped to 1080p. Without this, >1080p phones trigger software decode on the 439 → single-digit fps.
2. **Bitrate 8 Mbps → 4 Mbps CBR** (`PipelineServer.BITRATE = 4_000_000`). Adaptive fallback tightened: floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps (was 2 Mbps / 5s / 1 Mbps).
3. **Framerate 30 → 24 fps** (`VideoConfig.TARGET_FPS = 24`). Per-frame budget 33ms → 42ms, ~20% lower WiFi/GPU/allocation load.
4. **MirrorScreen TextureView → SurfaceView** (`MirrorScreen.kt`). Bypasses the Adreno 505 per-frame GL composite (~2-5ms/frame at 1280x800). The `outputSurfaceValid` gate prevents rendering to a destroyed surface across navigation hide/show; `switchSurface` re-attaches the decoder with zero keyframe loss.
5. **Thread priorities.** `THREAD_PRIORITY_URGENT_DISPLAY` on the encode pipeline (`PipelineServer.kt`), the decode feed thread (`VideoDecoder.kt`), and the socket-drain reader (`Connection.kt`); `THREAD_PRIORITY_BACKGROUND` on the LifeWriter. A53 has no big cores.
6. **`carLogEnabled` defaults to `BuildConfig.DEBUG`** (`CarConnectionService.onCreate` → `logWriter.setEnabled(BuildConfig.DEBUG)`); `ConcurrentLinkedQueue.size()` (O(n)) replaced by `AtomicInteger` (`CarLogWriter.bufferCount`).
7. **`AppIconCache.clear()` on disconnect** frees ~5-9MB heap + eMMC PNGs; `prepareAll` parallelized with `Semaphore(4)`, intermediate bitmaps recycled.
8. **`Debug.getPss()` removed from startup** (deprecated binder call, 100-500ms on A53).
9. **`@Immutable AppTileData` wrapper** (`HomeScreen.kt`) excludes the unstable `ByteArray` icon from `AppTile` inputs so the icon grid skips recomposition on `appList` reassignment.

### API 28 car compatibility

- **`MediaCodecInfo.isHardwareAccelerated()` (API 29+)** threw `NoSuchMethodError` on the BYD API-28 head unit, killing the process. Fix: removed the speculative hardware-decoder picker; `VideoDecoder.start()` calls `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` directly. `REGULAR_CODECS` lists hardware first and selects `OMX.qcom.video.decoder.avc` on the BYD.
- **`am display move-stack` (API 29+)** gated on `SDK_INT >= 29` in `PipelineServer.moveTopApp`. On older levels the foreground app is left in place rather than a silent shell failure masking as success.
- **`cmd display power-reset` / `power-off` (API 29+)** is the shell fallback in `DisplayPowerController` (no `power-on` subcommand exists). On API 26-28 a `DisplayControl` reflection failure means the physical panel is not restored — now logged via `logErr`, was silent.
- **`minSdk` raised to 26** for `protocol` and `app-server` (was 24). Re-arms the NewApi lint gate. `app-client` and `vd-server` stay at 29.

### UI / interaction

- **Nav bar redesigned to three buttons (Eject / Home / Back).** Notifications button, recent-apps rail, clock, network info all removed. Dead code backing them deleted across four modules: car-side `NotificationScreen` and `RecentAppsState` deleted; phone-side `NotificationService` (manifest entry, onboarding step, strings) deleted; `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` message types removed from protocol, `ConnectionService`, `VirtualDisplayClient`, and `PipelineServer`.
- **App pinning.** Long-press an app tile → Pin to Top / Unpin (SharedPreferences `dilinkauto_pinned` / `pinned_apps`). Pinned apps sort to the top of the grid.
- **App allowlist.** New `AllowlistScreen` (phone-side) lets the user pick which launcher apps reach the car; unselected apps are filtered in `AppListBuilder.sendAppList` before the wire payload is built. Pre-seeded with common map apps (Baidu, AMap, Google Maps, Waze, Sogou, Tencent, Mapabc) on first run. `ACTION_ALLOWLIST_UPDATED` triggers a live re-send so the car grid updates without a reconnect.
- **Portrait app scaling.** Dynamic DPI via `VideoConfig.calculateOptimalDpi` — caps DPI so portrait-only apps (Amap, WeChat) get >=360dp logical width in a landscape VD. IME auto-restored on session disconnect (was leaving the phone with the linkpc IME). Multi-pointer touch injection index fixed.
- **Car-side startup DPI override.** `HandshakeRequest.dpiOverride` field (0 = auto, [120, 480] = user value); car-side `startup_dpi` SharedPreferences; wired into both initial WiFi connect and mid-stream rotation re-handshake via `HandshakeFactory.buildHandshakeRequest`. Numeric input in `CarLaunchScreen`'s `ConnectionStatusCard`.
- **Colors tokenized** (`Color.Gray` / `0xFF888888` / `0xFFCCCCCC` / `0xFF757575` / `0xFFBBBBBB` → `onSurfaceVariant`; merged duplicate error reds `0xFFFF5252` + `0xFFEF5350` → `error`). Labels floored to 14sp (`labelSmall` = 14sp). "WiFi Direct Mode" → "WiFi ADB Mode" in `strings.xml` and all locale variants. Car-screen `adb tcpip 5555` developer workflow text removed (distraction hazard) and replaced with a phone-app pointer.
- **Rotation black-screen fix.** `onCarViewportChanged` sets state to CONNECTING and tears down the VD for redeploy, but the streaming-layout gate used `isConnected` (false during the ~2s redeploy) → `CarShell` flipped to `CarLaunchScreen` → SurfaceView removed → new surface couldn't restart the decoder. Gate now also accepts `state == CONNECTING && appList.isNotEmpty()` so the video-wait overlay covers the redeploy gap instead.

### Session stability

- **Active sessions are not killed by reconnect attempts.** `connectToPhone` checks `handshakeDone && controlConnection?.isConnected == true` and skips if a session is already live. WiFi gateway retry and mDNS loops stop on handshake send. Reconnect loop stops after 3 consecutive ADB failures (`noAdbCount >= 3`).
- **TCP ADB reconnects on phone IP change.** Dev mode tracks `lastAdbHost` and reconnects when the phone's IP changes.
- **Auto-fallback to TCP ADB when USB unavailable.** `VdServerDeployer.deploy` falls back to TCP ADB using the phone-host IP if known.
- **Reconnect limit 3.** `noAdbCount >= 3` stops the reconnect loop; user is told to plug the phone into car USB.

### PipelineServer stabilization (commit efced8b)

- **EGL/GL texture context fix.** GL texture was created in a temporary EGL context (then destroyed) and the pipeline thread bound texture 0 (black screen). All EGL/GL init moved to the pipeline thread with `CountDownLatch` synchronization.
- **Single-connection ADB.** `RemoteAdbController` simplified to use `TcpAdbConnection` (single socket, reused for all shell commands); Dadb dependency removed from the car → phone path (Dadb is still used by the phone → car `CarAppInstaller`).
- **VD deployment fix.** `app_process` was killed when the ADB shell process exited (non-interactive shells kill background jobs). Fixed via `shellBackground` — keeps the ADB stream open so the VD server process survives.
- **Phone screen restore order.** Reordered `PipelineServer` cleanup to restore the physical display BEFORE destroying the persistent shell process (was calling `input keyevent 224` after shell was already closed).
- **Log toggle.** Settings → Debug → Diagnostic logs switch. Off = zero disk writes. Propagated to car via `LOG_TOGGLE` data message, and re-sent after every handshake so a reconnect no longer resets the car side to OFF (issue #72). The switch shows the effective state (release builds correctly start OFF). Defaults: ON for debug/pre-release (`BuildConfig.DEBUG`), OFF for release; user choice persists via `AppPrefs.LOG_ENABLED` + `LOG_ENABLED_USER_SET`.
- **Port 19637 → 19647.** The lifecycle channel port was wrong in the upstream docs. `Discovery.LIFECYCLE_PORT = 19647`.

### Direct VD streaming refactor (commit 1c28d71 / fd89f0b)

VD Server binds `9638` (video) and `9639` (input) directly; the car talks to the VD without the phone app as middleman. Phone is a pure orchestrator: handshake, VD lifecycle (`VD_PORTS_BOUND`), car log routing. `VirtualDisplayClient` simplified to lifecycle-only. The `VD_PORTS_BOUND` control message tells the car when the VD server has bound 9638/9639 so the car can connect video/input directly without a connect race.

### PipelineServer — single-threaded streaming (commit 1c28d71)

Single pipeline thread: frame clock → GL render → encoder drain → TCP write. 0 queues between stages. Natural flow control — a TCP stall blocks the next `eglSwapBuffers`, slowing the encoder. Uses `System.nanoTime()` + `LockSupport.parkNanos()` for drift-free 24fps timing. Adaptive bitrate 2-8 Mbps (later capped to 4 Mbps for the Snapdragon 439 — see DiLink 4.0 perf above). Total: 3 threads (Pipeline at `THREAD_PRIORITY_URGENT_DISPLAY`, TouchReader, Lifecycle/LifeWriter at `THREAD_PRIORITY_BACKGROUND`) vs 9 in the previous version.

### Car-native VD resolution, encoder, decoder

- **Car-native VD resolution.** VD created at car viewport dimensions (e.g., 1280x800) instead of phone DPI. Eliminates GPU downscale — SurfaceScaler removed. VD surface → SurfaceTexture → GL passthrough → encoder.
- **Encoder: Main Profile, I-frame 1s, latency 0, no B-frames.** `KEY_OPERATING_RATE` and `KEY_MAX_B_FRAMES=0` for predictable latency. CBR 4 Mbps.
- **Decoder: 4-frame queue, keyframe priority.** `ArrayBlockingQueue(4)`. Keyframes always accepted (evict P-frames). No catchup logic — frames arrive on time or get dropped. `drainOutput()` before `feedBuffer()` to free decoder buffers first. Post-flush IDR resync skips P-frames until a keyframe arrives.

### Car app auto-update

Phone compares `appVersionName` (semver, via `Versioning.compareVersions`) with the car's reported version; if the car sends no `appVersionName` (pre-0.17.0 peers), falls back to `versionCode` on both sides. On mismatch, sends `UPDATING_CAR` and pushes the embedded `app-server.apk` via `CarAppInstaller` (dadb over WiFi, 15s connect timeout). Car shows "Updating car app..." status and does not reconnect.

### VD server log in Share Logs

`FileLog.zipLogs()` includes `/data/local/tmp/vd-server.log` (written by `PipeLog`) when available, alongside the rotated `client.log` files.

### TcpAdbConnection — persistent single-connection ADB

`TcpAdbConnection` (protocol module) maintains a single TCP socket for all shell commands. Handles CNXN/AUTH/SIGNATURE/RSAPUBLICKEY with correct ANDROID_PUBKEY format and PEM key storage. Replaces the Dadb library for the car → phone path (Dadb opened a new TCP connection per command, causing `ECONNREFUSED` on Xiaomi/HyperOS). Shared between car and phone apps via the protocol module.

### i18n

Simplified Chinese (`values-zh-rCN` / `values-zh`) added to both `app-client` and `app-server`. Plus the 8 upstream languages (en, pt-BR, ru, be, fr, kk, uk, uz).

### DPI input correctness and re-handshake race (issue #1, commit 8e78671)

- **Car-side DPI coerce range now matches the phone-side [120, 480]** with 0 = Auto. Previously the car used `coerceIn(0, 480)` while the phone used `coerceIn(120, 480)`, so values 1-119 were silently lifted to 120 by the phone while the input field kept showing the typed value.
- **DPI input field bound directly to `startupDpi`** (single source of truth) instead of a separate `dpiText` state keyed on `startupDpi`. The old `remember(startupDpi)` didn't recompose when the coerced value equaled the current state, so the field drifted from the persisted value.
- **ASCII digit filter.** `Char.isDigit()` (accepts Unicode Nd: Arabic-Indic, Devanagari) replaced with `it in '0'..'9'`. Non-ASCII digits passed the old filter but were rejected by `toIntOrNull()`, causing silent Auto fallback while the field showed the non-ASCII char.
- **`onCarViewportChanged` cancels `connectionScope` and `connectJob` before the re-handshake teardown**, mirroring `startConnection`'s pattern. Previously the WiFi gateway retry loop saw `state=CONNECTING` + `handshakeDone=false` + `wifiReady=false` (set by the teardown) and called `connectToPhone`, whose `handshakeDone` guard was now false, disconnecting the live control connection mid-re-handshake.

### Clear icon hash on disconnect and sync connection teardown (commit 1c144ea)

- **`AppListBuilder.resetIconHashes()`** (clears `lastSentIconHash`) called from `ConnectionService.cleanupSession` so icons are resent to the car on reconnection — fixes the default-icon fallback after a reconnect.
- **Connection teardown and state resets execute synchronously** in `CarConnectionService` before the IO coroutine is launched, preventing race conditions during mid-stream rotation.
- `AppIconCache.clear()` on disconnect frees ~5-9MB heap + eMMC PNGs.

### Code health — DRY/SRP pass (commits 628d80e + 56b18ae)

- **Cross-module shared constants.** `AppPrefs`, `AppTargets`, `VdDeploy`, `VdDeployArgs`, `WifiGatewayIp` (ports 5555/9637/9638/9639/19647, prefs keys, `am start` components, `pkill`/`app_process` command builders, gateway IP formatter, DPI range 120..480) now live in the protocol module.
- **Collaborators extracted.** `app-client`: `ApkInstaller`, `AppListBuilder`, `AppVersion`, `InstallStatus`, `PhoneDisplayRestorer`, `VdDimensions`, `Versioning`. `app-server`: `CarLogWriter`, `CarTouchSender`, `HandshakeFactory`, `VdServerDeployer`, `CarShell`, `ConnectionStatusScreen`, `NowPlayingBar`. `vd-server`: `GlPipeline`, `TouchInjector`, `DisplayPowerController`, `PipeLog`, `VirtualDisplayCreator`.
- **DRY consolidation (commit 56b18ae).** `AdbCrypto` unifies Tcp/Usb auth signing, pubkey encoding, and fingerprint (was duplicated ~150 lines with a silent SHA-1 vs SHA-256 divergence and a `\0` vs ` ` suffix divergence — the TcpAdb suffix was corrected to `" DiLinkAuto@car\0"` per the ADB spec). `VdDeploy.DeployPlan` assembles the vd-server launch command once. `DeviceInfo.buildDeviceInfoBlock` shared by `ConnectionService.logDeviceInfo` and `CarCrashHandler.buildDeviceInfo`. `ImeRestore` shared across `ConnectionService`, `PhoneDisplayRestorer`, `DisplayPowerController`. `AdbKeyUtil` shared between `CarAppInstaller` and `ApkInstaller`. `NetUtil.localIpv4Addresses` shared between `NetworkInfo` and `CarIpLocator`. `AppCategorizer` extracts package→`AppCategory` rules from `AppListBuilder`. `H264NalParser` extracts the IDR (NAL type 5) scan from `VideoDecoder`. `CarIpLocator` merges three near-identical port probes into one `probePortBlocking`. `FrameCodec` extracts `encodeHeaderInto` + `validatePayloadSize`. `NioReader` extracts `growIfNeeded`. `GlPipeline` names `WRITE_TIMEOUT_NS` / `WRITE_BACKOFF_NS`. `CarTouchSender` extracts `checkConn(label)`. `PersistentNavBar` extracts `rememberNavBarSize` + `NavActionButtons`. `Connection` / `PipelineServer` name `SOCKET_BUF_BYTES` (was inline 262144).
- **`CarConnectionService` 1237 → 1137 lines** (commit 628d80e); further to 1133 after the DRY pass (commit 56b18ae). VD deploy/retry/TCP-fallback moved to `VdServerDeployer`.

---

## Fix Tracker

Comprehensive review performed 2026-04-23 covering performance, stability, and flow-continuity.

### Phase 1 — Critical (fix before next release)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| C1 | Stability/Perf | `writeAll()` spins indefinitely on full send buffer — no timeout, holds `outputLock`, blocks all senders. System freeze risk | **v0.7.1** |
| C2 | Flow | VD server dies on USB disconnect (`shellNoWait` ties process to ADB stream). Full reconnect 5-15s | REVERTED — `setsid`/`nohup` broke localhost. Using `shellNoWait`+`exec` (v0.6.2 approach). Reconnects on re-plug. |
| C3 | Flow | Auto-update has no loop-break — if `pm install` silently fails, infinite restart cycle | **v0.7.1** |
| C4 | Flow | Car WiFi track runs once and gives up — hotspot enabled after USB plug → stuck forever | **v0.7.3** |
| C5 | Stability | WakeLock acquired without timeout — battery drain if service killed without `onDestroy()` | **v0.7.1** |
| C6 | Stability | `VirtualDisplayClient.touch()` non-blocking write spin + `channel` field not volatile — data race | **v0.7.1** |

### Phase 2 — High (latency & stability)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| H1 | Perf | NIO `delay(1)` polling adds 1-4ms latency floor per read + 1000 wake-ups/sec idle. Use Selector or `runInterruptible` | **v0.7.2** |
| H2 | Perf | Two syscalls per frame write (6-byte header + payload). Use `GatheringByteChannel.write(ByteBuffer[])` | **v0.8.0** |
| H3 | Perf | Per-frame `ByteArray.copyOf()` in video relay (~30 allocs/sec of 10-100KB). Pass offset+length | **v0.8.0** |
| H4 | Perf | `synchronized(outputLock)` serializes video+touch+heartbeat. Keyframe write blocks touch ~200ms | **v0.7.4** |
| H5 | Stability | `Connection.connect()` leaks SocketChannel on cancellation — no try/finally | **v0.7.1** |
| H6 | Stability | `disconnectListener` invoked synchronously in CAS — potential deadlock | **v0.7.1** |
| H7 | Stability | Car state flags (`wifiReady`, `usbReady`) not volatile — can get stuck in CONNECTING | **v0.7.2** |
| H8 | Stability | `VideoDecoder.stop()` doesn't join feed thread before `codec.stop()` — native crash risk | **v0.7.2** |
| H9 | Flow | Phone network callback ignores CONNECTED/STREAMING — hotspot toggle causes 10s frozen frame | **v0.7.3** |
| H10 | Flow | Handshake + auto-update + VD deploy all race — deployAssets may not be done, concurrent ADB ops | **v0.7.4** |
| H11 | Flow | No user feedback during 5-12s VD server startup — car shows static spinner | **v0.7.4** |
| H12 | Flow | First-time USB ADB auth dialog on phone with no guidance on car screen — 30s timeout | **v0.7.4** |

### Phase 3 — Medium (noticeable issues)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| M1 | Perf | VD server flushes after every frame on localhost — unnecessary syscall | **v0.8.0** |
| M2 | Perf | `checkStackEmpty()` blocks command reader 500ms — touch blackout after Back | **v0.8.1** |
| M3 | Perf | Multi-touch sends N separate frames per MOVE — should batch all pointers | **v0.8.3** |
| M4 | Perf | MotionEvent PointerProperties/Coords allocated per injection — pool these | **v0.8.1** |
| M5 | Perf | Decoder frame queue 6 deep (200ms) — reduce to 2-3 for lower latency | **v0.8.1** |
| M6 | Perf | ~~`execFast("cmd display power-off 0")` on touch thread — move to timer~~ — `execFast` removed (Phase B6), display power now managed via `execShell` on a dedicated thread | **v0.8.1** |
| M7 | Stability | Double `cleanup()` in VD server — handleClient finally + run both call it | **v0.7.2** |
| M8 | Stability | `cleanupSession()` doesn't reset `_serviceState` — stale UI during delay | **v0.7.2** |
| M9 | Stability | Static MutableStateFlow in companion survives service restarts — stale activeConnection | **v0.7.2** |
| M10 | Flow | User disconnect (eject) not persisted — car reconnects after process kill | **v0.8.3** |
| M11 | Flow | Car mDNS + gateway IP probe one-shot — need periodic retry | **v0.7.3** |
| M12 | Flow | `dumpsys activity` parsing in checkStackEmpty fragile across Android versions | **v0.8.2** |

### Phase 4 — Low (polish)

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| L1 | Perf | `TouchEvent.encode()` allocates 25-byte ByteArray per event — can't pool with async write queue | WONTFIX |
| L2 | Perf | VD server localhost socket missing send/receive buffer size config | **v0.8.2** |
| L3 | Perf | Video decoder uses fixed 33,333us timestamp — should use wall clock | **v0.8.2** |
| L4 | Stability | NioReader direct ByteBuffer not freed deterministically | **v0.8.3** |
| L5 | Stability | `UsbAdbConnection.readFile()` doesn't guarantee full read | **v0.8.2** |
| L6 | Stability | SurfaceScaler HandlerThread never quit | **v0.8.2** |
| L7 | Flow | App icons 48x48px — blurry on car displays, need 96-128px | **v0.8.2** |

### Phase 5 — Security hardening (full-project audit, 2026-10-09)

The 2026-10-09 full-project security audit re-reviewed the codebase treating every socket peer as hostile. Its `P-` / `S-` / `A-` / `D-` ids are a **separate numbering scheme** from the 2026-04-23 `C1..L7` above. All findings listed here are closed.

| ID | Category | Finding | Status |
|----|----------|---------|--------|
| P-01 | Security/Stability | Decoders used raw fixed-size `getX()` on truncated payloads; a throwing frame listener escaped the reader coroutine and killed the process (there is no `CoroutineExceptionHandler` in the repo, and `app-server`'s default handler ends in `killProcess`) | **a7d2b72** |
| P-02 | Security/DoS | `MAX_PAYLOAD_SIZE` 128MB — a 7-byte header forced ~256MB of allocation on the phone and the shell-UID vd-server; `NioReader` only ever grew | **a7d2b72** — cap 16MB, hard ceiling, `maybeShrink` at frame boundaries |
| P-03 | Security | ADB `parseHeader` accepted negative / oversized `data_len` (an `OutOfMemoryError` is an `Error`, so it escaped the catch), and `data_crc` was parsed but never verified | **a7d2b72** |
| P-04 | Stability | `NioReader` stalled forever mid-frame on a half-open peer | **a7d2b72** — 10s deadline at frame boundaries only; an idle touch link is deliberately not timed out |
| S-01 | Security | vd-server bound 9638 / 9639 on `0.0.0.0` with no authentication at all, and the phone's lifecycle listener was world-readable (displayId injection reachable from the LAN) | **8601fb1 + bc2634c + a7d2b72** — sanitized `CAR_HOST` argv pinning, loopback bind, non-loopback peers refused |
| S-02 | Security | Peer-supplied package names were interpolated into `am start` / `pm uninstall` shell lines running as shell UID | **a7d2b72 + 8601fb1** — shape validation at the decoder plus `shellQuote` at every call site. The `dispatch` try/catch is still missing, see [Known Issues](#known-issues) |
| S-03 | Flow | Black-screen self-heal called `videoDecoder.stop()`, so the codec was never restarted and a triggered recovery left the screen black for the rest of the session | **35d7821** |
| S-04 | Stability | `connectionScope.cancel()` did not stop the WiFi retry / mDNS loops, and a callback landing inside the ~1s re-handshake window closed the very control connection carrying it | **35d7821** — loops on `trackScope()` + `reHandshakeInFlight` guard |
| S-05 | Stability | `handleDisconnect` re-entered itself into 2-4 concurrent reconnect coroutines | **35d7821** — `teardownGuard` + listeners cleared on the intentional teardown path |
| S-06 | Stability | A `bindAndAccept` failure parked the pipeline thread with nobody to unpark it — the process wedged forever | **8601fb1** — sockets closed in one `finally`, bounded `parkNanos` re-checking `running`, daemon thread, `signalPipelineStop` on every failure path |
| S-09 | Stability | TouchInjector accepted unbounded pointers, indexed the pool with an unvalidated `pointerId`, and swallowed the failure silently — a peer sending 11 DOWNs jammed touch for the rest of the session | **8601fb1** — new `PointerCap` (evict oldest, truncate `pts`, range-validate, `logErr` instead of silence) |
| A-01 | Stability | `cleanupGuard` raced across session generations — a cancelled loop's late teardown consumed the next session's guard, leaking the 19647 socket and the VD | **bc2634c** — session generations (`AtomicInteger` + id check) |
| A-04 | Perf/Stability | `cleanupSession` blocked the main thread up to 1s per call and ran the restore on a scope `onDestroy()` cancels | **bc2634c** — process-lifetime `teardownScope` |
| A-05 | Security/DoS | Unbounded log queue — a peer could push a 16MB frame into `CAR_LOG` and OOM the phone | **bc2634c** — 8KB line truncation + bounded queue (2048) |
| D-01 | Security | The Windows receiver turned unauthenticated handshake-response fields into an adb deploy command — a MITM could choose which code ran on the phone via `CLASSPATH` | **1f6836a** — `checkAdbPort` (5555 only) + `sanitizeJarPath` whitelist + `clampVdDimension`; rejection fails the session with a readable reason |
| D-03 | Stability | A normal disconnect left the held `adb.exe` — the device engine's liveness anchor — running forever | **1f6836a** — `adbDeployer.close()` on the session-end path under the generation guard |
| — | Security | The generated device-side deploy command line interpolated a peer-supplied `vdServerJarPath` unquoted | **5eae3e7** |

---

## Known Issues

| Issue | Impact | Status |
|-------|--------|--------|
| USB ADB auth dialog on replug | Phone asked "Allow USB debugging?" each time | **FIXED v0.13.1** — was double-hashing AUTH_TOKEN with SHA1withRSA. Now uses NONEwithRSA + prehashed SHA-1 DigestInfo. "Always allow" persists. |
| VD leak on reconnect | Each reconnect leaked one VirtualDisplay; panel stayed off | **FIXED v0.18.0-dev-13**, **re-fixed 2026-10-10** — the dev-13 mechanisms rested on two assumptions that are false on real devices: (1) ART's `app_process` does **not** run the JVM shutdown hook on SIGTERM, so the "two-stage stop" was a plain force kill and `cleanup()` never ran; the stop is now a **stop-request sentinel file** the engine's watchdog consumes (normal exit path → full cleanup), with `killCommandForce` only as a 10s timeout fallback. (2) toybox rejects `pkill -0`, so both liveness probes silently reported GONE and the "VD exit wait" never waited; probes now use `pgrep -f`. Verified end-to-end on MI 9 (2026-10-10) |
| Cleanup non-idempotent | 1 disconnect triggered 9 duplicate restore calls | **FIXED v0.18.0-dev-13** — `cleanupGuard` `AtomicBoolean` |
| Screen timeout lost on teardown | `screen_off_timeout=2147483647` persisted after session | **FIXED v0.18.0-dev-13** — `saveCurrentIme()` now runs before `configureEnvironment()`; `restoreSetting()` validates values |
| Black screen after reconnect | Car stuck on black screen, no recovery | **FIXED v0.18.0-dev-13** — `onSustainedBlackScreen` callback triggers VD rebuild after 3s (`BlackScreenDetector.BLACK_SCREEN_SUSTAIN_MS`, protocol-core; was 5s in app-server before the e1674f8 extraction). The self-heal itself then had a defect: **FIXED `35d7821`** — the recovery path called `videoDecoder.stop()` while `CarShell` keeps the streaming layout mounted, so nothing ever restarted the codec and a triggered recovery left the screen black for the rest of the session. It now resets the detector state and lets the live codec consume the fresh IDR. |
| Portrait apps letterboxed on landscape VD | Petal Maps home screen narrow | Mitigated — `VideoConfig.calculateOptimalDpi` caps DPI so portrait apps get >=360dp; user can override via `startup_dpi`. |
| Hotspot must be enabled manually | User enables before plugging in | Android 16 limitation |
| Audio streaming | **FIXED / IMPLEMENTED in v1.0.3** | Full loop operational on port 9640 (AAC-LC 48kHz stereo, synthetic silence pacing at 46.875 fps, 150ms jitter buffer, bidirectional capability negotiation, silence metric audit). |
| Media controls / navigation widgets | Not implemented | `NowPlayingBar` exists in the source tree but is only composed when `MediaMetadata` is present, which the phone never sends. |
| `Versioning` zero-version sentinel (testing-audit C-5) | With nothing installed, `readInstalledVersion` returns `"0"`, which compares equal to `versionName` `"0"` / `"0.0"` / `"0.0.0"` — so the car app is judged "already up to date" and the **first install is skipped**. `-SNAPSHOT` also compares equal to its release | **OPEN** — product decision, pinned by a hazard test in `VersioningTest` (commit 8157c86). Fix: an explicit "not installed" state that can never compare equal, instead of a `"0"` sentinel |
| `InstallStatus` classifies by English substring (testing-audit C-6) | Status strings come from `context.getString`, so every non-English `values-*` locale falls into `IDLE` — no progress and no error UI at all. `"not reachable"` / `"Invalid IP"` are not classified as ERROR either | **OPEN** — product decision, pinned by a hazard test in `InstallStatusTest` (8157c86). Fix: classify on a structured state enum rather than localized copy |
| `CarCommandRouter.dispatch` decode unprotected (testing-audit S-02 residual) | `LAUNCH_APP` / `APP_UNINSTALL` call their decoders outside any try/catch (only `APP_INFO` has one), so a rejected package name escapes into the vd-server's frame-read thread instead of being logged and dropped | **OPEN** — the package-name shape check itself landed (`a7d2b72`, `8601fb1`); only the call-site guard is missing. Fix: wrap the two `decode` calls the way `APP_INFO` does. Pinned by `CarCommandRouterDispatchTest` |
| `AppCategorizer` common apps land in OTHER | e.g. `com.google.android.youtube` matches no keyword, so the car grid cannot group it | **OPEN** — UX gap, not a crash. Pinned by `AppCategorizerTest` (8157c86) |
| Occasional garbled frame | One corrupted frame from a decoder-restart race; the next keyframe restores the picture (~1s) | **KNOWN** — accepted; the car path deliberately keeps the codec alive across restarts (`switchSurface` re-attach, `35d7821`), which trades this for the black-screen bugs that re-fixing it reintroduced |
| `app-desktop` ADB deploy path unverified | When the phone has no Shizuku, the Windows receiver deploys the VD server via `adb.exe`; that path has never been exercised on a real device, and the audit missed its defects because of it | **OPEN** — todo in `.plan/windows-client-plan.md`. The rest of Phase 5 (multi-monitor / fullscreen, hardware decode and its fallback, keep-awake, the manual phone-screen switch) also awaits an on-device pass |

---

## Architecture (Current)

```
Phone (Chinese ROM — Xiaomi HyperOS / Meizu etc., Android 14+)
├── DiLink Auto Client App (app-client, minSdk 29)
│   ├── ConnectionService — pure orchestrator (FEATURE_AUDIO negotiation, Shizuku deploy delegation)
│   │   ├── Control (9637): accept → handshake → VD_PORTS_BOUND → car logs
│   │   ├── Collaborators: ShizukuVdDeployer, ShizukuNav, ClientNetworkMonitor, CarAppInstallHost, AllowlistRepository
│   │   ├── Car auto-update: UPDATING_CAR → CarAppInstaller (dadb) push+install
│   │   ├── App allowlist filter (AppListBuilder.sendAppList)
│   │   ├── Smart network callback (ClientNetworkMonitor, TRANSPORT_WIFI only)
│   │   └── FileLog: /sdcard/DiLinkAuto/client.log (rotation, 10 max)
│   ├── VirtualDisplayClient — lifecycle-only
│   │   ├── startListening() — synchronous ServerSocket on 127.0.0.1:19647 (loopback; non-loopback peers refused)
│   │   ├── acceptConnection() — NIO non-blocking accept
│   │   ├── Reads MSG_DISPLAY_READY (displayId + direct-injection flag)
│   │   ├── Sends VD_PORTS_BOUND to car on display ready
│   │   └── Sends CMD_STOP on teardown
│   ├── PhoneDisplayRestorer — Shizuku → wakeUp → FLAG_TURN_SCREEN_ON → wake lock
│   └── AllowlistScreen / ApkInstaller / AppVersion / Versioning / UpdateManager
│
├── VD Server (app_process, shell UID 2000, vd-server.jar)
│   ├── PipelineServer — process entry point + lifecycle owner
│   │   ├── Binds 9638 (video), 9639 (input), and 9640 (audio) on 0.0.0.0 via PortAcceptor
│   │   ├── Accepts car's video, input, and audio connections directly (CAR_HOST pinned)
│   │   ├── Reverse-connects lifecycle to phone localhost:19647
│   │   ├── VideoEncoderSelector: candidate-ordered pick (Codec2 before legacy OMX), CBR 4Mbps Main, I-frame 1s
│   │   ├── AudioPipeline: REMOTE_SUBMIX capture, AudioEncoderSelector AAC-LC, 46.875 fps synthetic silence pacing
│   │   ├── ProcessWatchdog: forces cleanup() if pipeline hangs in native MediaCodec, watches stop-sentinel
│   │   └── Cleanup: am display move-stack → restore panel → restore IME → kill shell
│   ├── GlPipeline — EGL14 + GLES20 on the pipeline thread
│   │   ├── parkNanos pace → updateTexImage → fullscreen quad → eglSwapBuffers
│   │   ├── Encoder drain → TCP write (no queues)
│   │   └── Adaptive bitrate: floor 1.5Mbps, 2s recovery, 0.5Mbps steps
│   ├── TouchInjector — InputManager reflection, multi-touch, TOUCH_MOVE_BATCH
│   ├── DisplayPowerController — DisplayControl reflection; cmd display power-reset/off fallback (API 29+)
│   ├── VirtualDisplayCreator — trust flag 0x6c49, 12L letterbox style
│   └── PipeLog — vd-server.log
│
Car (BYD DiLink 4.0, Snapdragon 439, Android 9 / API 28, 1280x800)
├── DiLink Auto Server App (app-server, minSdk 26)
│   ├── CarConnectionService — parallel WiFi + USB/TCP ADB tracks (slimmed to 407 lines)
│   │   ├── CarSessionCoordinator: manages 9637 (control), 9638 (video), 9639 (input), 9640 (audio)
│   │   ├── CarAdbTracker: USB host ADB and TCP ADB dual tracks, reconnect bounds (noAdbCount >= 3)
│   │   ├── CarAppListReceiver: app list parsing with AppIconLimits enforcement
│   │   ├── AudioPlayer: MediaCodec AAC-LC decode, 150ms jitter buffer, AudioTrack, silence frame metrics
│   │   ├── VdServerDeployer: shellBackground launchCommand
│   │   ├── HandshakeFactory.buildHandshakeRequest (initial + rotation re-handshake)
│   │   └── startup_dpi SharedPreferences (0 = auto, [120, 480] = override)
│   ├── VideoDecoder — createDecoderByType, 4-frame queue, keyframe priority, DecodeLivenessTracker
│   │   ├── outputSurfaceValid gate (SurfaceView destroy/re-create across navigation)
│   │   ├── switchSurface re-attaches decoder with zero keyframe loss
│   │   ├── post-flush IDR resync (skips P-frames until keyframe)
│   │   ├── Feed thread at THREAD_PRIORITY_URGENT_DISPLAY
│   │   └── debugFrameStats (decode time, queue depth) gated on LOG_TOGGLE
│   ├── AppIconCache — prepareAll (Semaphore(4)), getPrepared (O(1)), clear() on disconnect
│   ├── CarShell — streaming layout accepts CONNECTING && appList.isNotEmpty()
│   ├── MirrorScreen — SurfaceView (not TextureView)
│   ├── CarLaunchScreen — startup_dpi numeric input, dev-mode toggle, manual IP
│   ├── HomeScreen — @Immutable AppTileData, Pin-to-Top, long-press context menu
│   ├── PersistentNavBar — three buttons only: Eject / Home / Back
│   ├── CarLogWriter — AtomicInteger bufferCount, 10k cap, BuildConfig.DEBUG default
│   ├── CarTouchSender — TOUCH_MOVE_BATCH on input connection
│   ├── RemoteAdbController — TcpAdbConnection (single socket)
│   ├── CarCrashHandler — crash-pending.log → carLogSend on next connect
│   └── CarTheme — tokenized colors, labels floored to 14sp
│
Windows (x64, no JDK required — jpackage app-image bundles a JRE)
└── DiLink Auto Desktop Receiver (app-desktop, Kotlin/JVM + Compose Desktop)
    ├── DesktopConnectionService — same state machine as the car
    │   ├── controlConnection (9637) → HandshakeFactory (viewport + evenAlign + dpiOverride)
    │   ├── videoConnection (9638) / inputConnection (9639) direct to the VD server
    │   └── SET_DISPLAY_POWER (0x32) over the input port's Channel.CONTROL
    ├── VideoDecodePipeline — JavaCV FFmpegFrameGrabber over a custom InputStream,
    │   d3d11va hardware decode with a software-fallback watchdog + rebuild backoff
    ├── FeedGate / H264NalParser — CONFIG before IDR, skip P-frames after a rebuild
    ├── SwingVideoView / Letterbox — double-buffered letterbox paint + mouse → TouchEvent
    ├── AppIconStore / AppCatalog / AppGridView — APP_LIST icons + launch
    ├── DesktopApp — session generations (DPI / hardware-decode change replaces the session)
    ├── AdbDeployer / ProcessAdbRunner — adb.exe deploy when the phone has no Shizuku
    └── DesktopPaths / JsonConfig / DesktopLog — %APPDATA%\DiLinkAuto\, jpackage exe
```

## Connection Flow

```
1. Phone and car on the same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB/TCP ADB tracks

   Track A (WiFi control):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + appVersionName
                 + targetFps + dpiOverride + supportedFeatures (FEATURE_AUDIO)
   d. Phone responds with device info + vdServerJarPath + vdDpi + supportedFeatures
   e. Phone checks version — if mismatch, sends UPDATING_CAR, auto-updates
      car APK via dadb, disconnects to wait for car restart
   f. Phone opens lifecycle ServerSocket on 127.0.0.1:19647 (loopback only)
   g. If Shizuku available: phone deploys VD server directly.
      Else car deploys via ADB (USB or TCP).
   h. VD server starts: CLASSPATH=jar app_process / PipelineServer
                          W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]
   i. VD server reverse-connects to phone localhost:19647 (NIO)
   j. Phone reads MSG_DISPLAY_READY (displayId + direct-injection flag)
   k. Phone sends VD_PORTS_BOUND to car on the control connection
   l. Car connects video (9638), input (9639), and audio (9640, when negotiated) directly to VD server
   m. VD server accepts connections — session fully established

   Track B (USB ADB / TCP ADB):
   a. USB: scan USB devices for ADB interface → CNXN → AUTH → connected
      TCP: dev-mode phone-IP lookup, connect to phone:5555 via TcpAdbConnection
   b. shellBackground(launchCommand) — keeps ADB stream open so app_process survives
   c. Launch phone app via am start (AppTargets.PHONE_MAIN_ACTIVITY)

3. VD server creates VirtualDisplay (car viewport + auto-calibrated DPI)
4. VD server powers off the phone's physical panel via DisplayControl
5. VD server saves the original IME and sets the VD's IME policy
6. Pipeline thread: EGL/GL init → SurfaceTexture → encoder → bind 9638/9639/9640
7. Car starts VideoDecoder on the SurfaceView surface when MirrorScreen creates it
8. Video: VD → SurfaceTexture → GL → encoder → TCP 9638 → car VideoDecoder → SurfaceView
9. Audio: VD → AudioRecord (REMOTE_SUBMIX) → AAC-LC encoder (synthetic silence pacing) → TCP 9640 → car AudioPlayer (150ms buffer) → AudioTrack
10. Touch: car SurfaceView onTouch → TouchEvent encode → TCP 9639 → VD TouchInjector
          → InputManager.injectInputEvent
11. Car logs: carLogSend() + logSink callbacks → DATA CAR_LOG → phone FileLog
```

States: `IDLE → CONNECTING → CONNECTED → STREAMING`

A mid-stream car-panel rotation reuses the control TCP connection. `MainActivity.onConfigurationChanged` calls `CarConnectionService.onCarViewportChanged`, which cancels `connectionScope` + `connectJob`, tears down video/input + the old VD server, and re-sends a `HandshakeRequest` at the new dims. The phone deploys a fresh VD server and re-sends `VD_PORTS_BOUND`. The streaming-layout gate accepts `state == CONNECTING && appList.isNotEmpty()` so the video-wait overlay covers the ~2s redeploy gap instead of flashing `CarLaunchScreen` (which would destroy the SurfaceView and lose the decoder state).
