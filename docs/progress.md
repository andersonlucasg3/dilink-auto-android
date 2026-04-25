# Progress Tracker

Current version: **v0.13.1** (USB ADB auth fixed, display power fixed, decoder catchup)
Last updated: 2026-04-25

## Milestones

### v0.13.1 — USB ADB Auth Fix (2026-04-25)

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
- **Bitrate**: Increased from 8Mbps to 12Mbps CBR for better image quality.
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
| M6 | Perf | `execFast("cmd display power-off 0")` on touch thread — move to timer | **v0.8.1** |
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

---

## Known Issues

| Issue | Impact | Status |
|-------|--------|--------|
| USB ADB auth dialog on replug | Phone asked "Allow USB debugging?" each time | **FIXED v0.13.1** — was double-hashing AUTH_TOKEN with SHA1withRSA. Now uses NONEwithRSA + prehashed SHA-1 DigestInfo. "Always allow" persists. |
| VD server dies on USB disconnect | Stream stops if USB unplugged | Accepted — `setsid`/`nohup` detachment broke localhost connectivity. Car re-deploys on reconnect. |
| Touch injection wakes physical display | Screen turns on briefly during interaction | Mitigated with throttled re-power-off (1s, on background thread) |
| Portrait apps letterboxed on landscape VD | Petal Maps home screen narrow | Android limitation |
| Hotspot must be enabled manually | User enables before plugging in | Android 16 limitation |

---

## Architecture (Current — v0.13.1)

```
Phone (Xiaomi 17 Pro Max, HyperOS 3, Android 16)
├── DiLink Auto Client App
│   ├── ConnectionService (3-port accept: 9637/9638/9639)
│   │   ├── Control (9637): handshake, heartbeat, commands, data, car logs
│   │   ├── Video (9638): H.264 relay from VD server to car
│   │   ├── Input (9639): touch events from car, dispatched on Dispatchers.IO
│   │   ├── VD JAR deploy to /sdcard/DiLinkAuto/ (CRC32 checked)
│   │   ├── Car auto-update: sends UPDATING_CAR, then dadb push+install
│   │   ├── Smart network callback (ignores unrelated network drops)
│   │   ├── Battery exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
│   │   └── FileLog: /sdcard/DiLinkAuto/client.log (rotation, 10 max)
│   ├── VirtualDisplayClient (videoConnection + controlConnection)
│   │   ├── startListening() — synchronous ServerSocket on localhost:19637
│   │   ├── acceptConnection() — NIO non-blocking accept
│   │   ├── NioReader (Selector-based, FRAME_INTERVAL_MS timeout)
│   │   └── Video relay via videoConnection, stack empty via controlConnection
│   └── NotificationService (captures phone notifications with progress)
│
├── VD Server (app_process, shell UID 2000)
│   ├── NIO write queue (ConcurrentLinkedQueue) + Selector-based command reader
│   ├── IInputManager injection (ServiceManager → injectInputEvent)
│   ├── Multi-touch: pre-allocated PointerProperties/Coords pools (10 slots)
│   ├── VirtualDisplay (TRUSTED + OWN_DISPLAY_GROUP + OWN_FOCUS)
│   ├── SurfaceScaler (EGL/GLES GPU downscale, periodic re-draw every frameIntervalMs)
│   ├── H.264 encoder (12Mbps CBR, High profile, configurable FPS)
│   ├── Screen power-off (background thread, proximity/lift disabled)
│   └── Reverse NIO connection to phone on localhost:19637
│
Car (BYD DiLink 3.0, Android 10)
├── DiLink Auto Server App
│   ├── CarConnectionService — 3 connections + parallel USB track
│   │   ├── controlConnection (9637): heartbeat, commands, data
│   │   ├── videoConnection (9638): video frames → VideoDecoder
│   │   ├── inputConnection (9639): touch events from MirrorScreen
│   │   ├── Track B (USB): UsbAdbConnection with logSink → carLogSend
│   │   ├── UPDATING_CAR handling: shows status, skips reconnect
│   │   ├── Early decoder start: offscreen surface on first CONFIG
│   │   ├── carLogSend() + logSink callbacks → phone FileLog
│   │   └── Eject state persisted to SharedPreferences
│   ├── VideoDecoder (queue=30, early start, logSink, 60fps target)
│   ├── PersistentNavBar (76dp, 40dp icons, 14sp text, recent apps pruned)
│   ├── LauncherScreen (64dp icons, 160dp grid, imePadding search)
│   ├── NotificationScreen (progress bars, tap-to-launch, dedup by ID)
│   └── MirrorScreen (TextureView + touch forwarding, decoder restart)
```

## Connection Flow

```
1. Phone app starts → deploys VD JAR, rotates FileLog, requests battery exemption
2. Phone plugged into car USB → car detects USB_DEVICE_ATTACHED
3. Track A (WiFi): car discovers phone via gateway IP (3s retry) or mDNS
4. Track B (USB): car connects USB ADB (logSink for diagnostics), launches phone app
5. Control (9637): TCP connect → handshake (viewport + DPI + version + targetFps)
6. Phone: checks version → if mismatch, sends UPDATING_CAR → auto-updates via dadb
7. Video (9638) + Input (9639): car connects in parallel after handshake
8. Phone: accepts both, opens VD ServerSocket on localhost:19637
9. USB: car starts VD server (shellNoWait + exec app_process, FPS as arg)
10. VD server: VD + SurfaceScaler (periodic re-draw) + encoder → NIO connect localhost:19637
11. Car: starts VideoDecoder on offscreen surface on first CONFIG frame
12. MirrorScreen shows → decoder restarts with real TextureView surface
13. Video: VD → SurfaceScaler → encoder → NIO write queue → localhost → phone NioReader → videoConnection → WiFi TCP → car NioReader → VideoDecoder → TextureView
14. Touch: car TextureView → inputConnection → WiFi TCP → phone (Dispatchers.IO) → VD server NIO Selector → IInputManager injection
15. Car logs: carLogSend() + logSink callbacks → DATA CAR_LOG → phone FileLog
```
