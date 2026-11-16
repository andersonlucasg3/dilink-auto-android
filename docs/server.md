# Car Server App (app-server)

## Overview

The car server app runs on the BYD DiLink infotainment system. It uses a **parallel connection model** with WiFi (4 dedicated connections: control/data, video, touch, audio) and USB tracks running simultaneously:

1. Track A (WiFi): gateway IP + mDNS discovery, control connect (9637), handshake with phone (including `FEATURE_AUDIO` negotiation)
2. After handshake: video (9638) + input (9639) + audio (9640, if negotiated) connections opened in parallel
3. Track B (USB): scan devices, USB ADB connect (with logSink diagnostics), launch phone app
4. `checkAndAdvance()` evaluates state when any prerequisite changes
5. Receives H.264 video and renders on car display via SurfaceView
6. Receives AAC-LC audio and plays via `AudioPlayer` (150ms jitter buffer) and `AudioTrack`
7. Captures touch events and sends to phone via input connection for VD input injection

States: IDLE → CONNECTING → CONNECTED → STREAMING

Car APK is embedded in the phone APK. The phone installs the car app via dadb on demand (onboarding "Car setup" step or the main-screen "Install on Car" button).

### Two-Mode UI

The car app separates the connection flow from the streaming experience into two distinct modes, matching the phone app's approach:

- **Launch mode** (`CarLaunchScreen`): Full-screen, connection-focused. Shows branding, step-by-step connection instructions, connection status, manual IP entry, and the startup DPI / bitrate / FPS inputs. No navigation bar — the entire screen is dedicated to getting connected. Shown when the car app starts and remains until app icons are received from the phone via the control connection.

- **Streaming mode** (`CarShell` with `PersistentNavBar`): The layout with left navigation bar (Eject / Home / Back) and content area (app grid, mirror view). Shown once `appList` is non-empty and the state is CONNECTED or STREAMING. During a mid-stream rotation re-handshake, `onCarViewportChanged` sets state to CONNECTING and tears down the VD, but the streaming-layout gate also accepts `state == CONNECTING && appList.isNotEmpty()` so the layout stays put and a "waiting for video" overlay covers the ~2s redeploy gap instead of flashing back to `CarLaunchScreen` (which would destroy the SurfaceView's surface and block decoder restart).

The transition trigger: when the phone sends `APP_LIST` via the control connection and the connection state reaches CONNECTED/STREAMING, the UI switches from launch mode to streaming mode.

## Components

### CarConnectionService

Foreground service managing the connection lifecycle with a parallel prerequisite state machine and 4 dedicated connections. Substantially slimmed down to 407 lines through architectural decoupling (Refs #87, #92), delegating state coordination, ADB tracking, app-list parsing, and audio decoding to dedicated collaborators.

**4-Connection Architecture:**
- `controlConnection`: handshake, feature negotiation, heartbeat, app commands, DATA channel (app list, media metadata, car logs)
- `videoConnection`: H.264 video frames only (phone → car, port 9638)
- `inputConnection`: touch events only (car → phone, port 9639)
- `audioConnection`: AAC-LC audio frames only (phone → car, port 9640, when `isAudioNegotiated`)
- Heartbeat/watchdog on control connection only; video, input, and audio have no heartbeat overhead
- Any critical connection dying cascades → full session teardown; audio failure degrades gracefully without killing video

**Parallel Track Architecture & Collaborators (Refs #87, #92):**
- Delegated to `CarSessionCoordinator`, `CarAdbTracker`, `CarAppListReceiver`, and `AudioPlayer`.
- Reconnect backoff utilizes `BackoffPolicy` from `:protocol-core` rather than ad-hoc calculations.
- Decoder health monitored via `DecodeLivenessTracker`.
- `connectionScope`: parent Job for all discovery coroutines, cancelled on disconnect
- Track A and Track B run simultaneously
- `checkAndAdvance()` evaluates overall state when any prerequisite changes
- User disconnect: stays IDLE, no auto-reconnect (persisted to SharedPreferences)
- Reconnect loop stops after 3 consecutive ADB failures (`noAdbCount >= 3`); TCP ADB auto-reconnects on phone IP change and auto-falls back to TCP ADB when USB unavailable
- `handleDisconnect()` runs at most once per session generation (`teardownGuard`): an intentional teardown clears the disconnect listeners, and any re-entry is refused, so one teardown can no longer fan out into 2–4 concurrent reconnect coroutines that each overwrite the previous one's `connectionScope`/`connectJob` (audit S-05)

**Track A — WiFi:**
- Discovery: gateway IP (hotspot/LAN, retries every 3s) → mDNS → manual IP
- Control connection: NIO non-blocking SocketChannel connect to phone TCP:9637
- Handshake (built via `HandshakeFactory.buildHandshakeRequest`): sends viewport dimensions + DPI + appVersionCode + appVersionName + targetFps + `dpiOverride` + `bitrate` → receives phone info + vdServerJarPath + connectionMethod + adjusted VD DPI (`handshakeVdDpi`)
- On handshake response: waits for `VD_PORTS_BOUND`, then opens video (9638) + input (9639) connections in parallel, sets `wifiReady = true` after all 3 established
- Video: receives H.264 frames via video connection, dispatches to `VideoDecoder`. CONFIG (SPS/PPS) and the most recent IDR are cached inside `VideoDecoder` so the codec can be (re)started at any time; the decoder is NOT started on an offscreen `SurfaceTexture(0)` — decoded frames with no consumer would waste hardware-decode bandwidth and a temporary gralloc allocation. The decoder starts when `MirrorScreen`'s real `SurfaceView` surface becomes available.
- Touch: delegated to `CarTouchSender` (single-thread executor) via `sendTouchEvent()` / `sendTouchBatch()`
- Heartbeat: 3s interval, 10s watchdog timeout (control connection only)
- Reconnect backoff: `CarConnectionService.reconnectBackoffMs(failures)` (companion pure function) — 500ms on the first failure, doubling per failure, capped at 8000ms with the shift clamped. The formula used to be inlined at the call site and never tested (6d25390 extracted both this and the icon budget so the tests exercise the real constants).

**Track B — USB:**
- Registers `BroadcastReceiver` for `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED` (plus the permission-prompt action). A device that attaches after the one-shot scan re-arms the USB track (audit S-M7).
- `MainActivity` forwards USB intents to the service (`onUsbDeviceFromActivity`), caching the device when the service is not yet bound and delivering exactly once on bind.
- Scan USB devices for ADB interface
- USB ADB connect via `UsbAdbConnection` (in protocol/ module), with `logSink` routing all ADB auth logs to `CarLogWriter`
- A mid-session USB detach ends the live session through the same teardown path a real disconnect uses (audit S-M7): the session is half-dead — UI still says STREAMING but every later rotation re-handshake would fail — so it returns to IDLE and reconnects cleanly.
- Launches phone app: `am start -n com.dilinkauto.client/.MainActivity`
- Dev mode: TCP ADB path uses `RemoteAdbController` instead of `UsbAdbConnection`, auto-reconnects on phone IP change

**State Flows:**
- `_state`, `_phoneName`, `_appList`, `_mediaMetadata`, `_playbackState`: primary state exposed to UI
- `_videoReady`: true when first non-config video frame arrives
- `_statusMessage`: human-readable status for UI display
- `_vdStackEmpty` (SharedFlow): emitted when phone reports VD has no activities (triggers navigation to home)

**VD Server Deploy:**
- Delegated to `VdServerDeployer.deploy()` (or `deployDirect()` for the dev-mode TCP-ADB path). The deployer owns the retry counter and the TCP-ADB fallback policy (retries twice on transient "no ADB", then falls back to TCP ADB using the phone-host IP if known), assembles the plan with `VdDeploy.buildDeployPlan`, and runs the kill → wait-for-exit → launch sequence through `vdRunDeploySequence` — the orchestration in protocol-core that the phone's Shizuku path and the desktop adb.exe path also call, so the "wait for a real exit" rule cannot drift between deploy sites again.
- Launch runs with `background = false` (`shellBackground` on the TCP transport keeps the ADB stream open). A `&`-backgrounded launch exits the shell immediately, which closes the car-side `TcpAdbConnection` stream and kills the engine it just started.
- Args: `W H DPI PORT EW EH FPS` — `targetFps` from `VideoConfig.TARGET_FPS` (20), `bitrate` from `carPrefs.startupBitrate` (`VideoConfig.DEFAULT_BITRATE`, 4 Mbps)

**Rotation Re-Handshake:**
- `onCarViewportChanged(widthPx, heightPx, dpi)` is called by `MainActivity.onConfigurationChanged` when the car panel rotates. If the viewport dims changed and the state is STREAMING or CONNECTED, the service cancels discovery loops and calls `rehandshakeOnExistingControl`, which tears down video/input (clearing their disconnect listeners so `handleDisconnect` is not invoked for the intentional teardown), clears `wifiReady`/`vdServerStarted`/`handshakeDone`, sets state to CONNECTING, and re-sends `HANDSHAKE_REQUEST` on the existing control connection with the new dims + current `startupDpi` — leaving the decoder running (see the Black Screen Re-Handshake section for why stopping it would black the screen). The phone tears down the old VD server, deploys a fresh one at the new dims, re-binds 9638/9639, and sends `VD_PORTS_BOUND` again — same flow as the initial connect, just without re-establishing the control TCP connection.
- Peer-reported viewport dims from the handshake response are clamped through `sanitizePeerDimension` (2..4096, falling back to the car's own viewport) before they reach `MediaFormat` or the rotation guard (audit S-M4).

**Black Screen Re-Handshake:**
- `videoDecoder.onSustainedBlackScreen` is wired in `onCreate` to call `rehandshakeForBlackScreen()`, which rebuilds the phone-side VD at the *current* dimensions (no viewport change — the engine is wedged, not the display). The gate is a live session (`STREAMING`/`CONNECTED` + `handshakeDone`) plus `!reHandshakeInFlight`, `!blackScreenRecoveryInFlight` and a per-session rebuild budget of `MAX_BLACK_SCREEN_REBUILDS` (2, reset by `startConnection()`). The old gate also required `vdServerStarted`, which Shizuku mode never sets and which the recovery itself clears — so the primary path got no self-heal at all, and a per-session counter is what actually bounds the storm that originally leaked VDs (audit S-M1).
- `rehandshakeOnExistingControl(ctrl, newVpW, newVpH, dpi)` — shared by the rotation and black-screen paths. It tears down video/input, re-arms the black-screen detector, clears `wifiReady`/`vdServerStarted`/`handshakeDone`, sets state to CONNECTING and sends `HANDSHAKE_REQUEST` on the existing control connection. It deliberately does **not** stop the decoder (audit S-03): `CarShell` keeps the streaming layout mounted while state is CONNECTING, the SurfaceView is never destroyed and `surfaceCreated` never fires again — so stopping the codec left the screen black for the rest of the session, the exact symptom this recovery exists to prevent. Discovery loops are cancelled for the window and `reHandshakeInFlight` keeps a callback that lands inside it from closing the very control connection carrying the re-handshake (audit S-04).

**Car Log Routing:**
- `CarLogWriter` routes all car-side logs through the DATA channel (`DataMsg.CAR_LOG`) to the phone. Callers do a non-blocking `send` (logcat + queue) that hands the entry to a bounded `AsyncLogQueue` (1024); a dedicated coroutine on `Dispatchers.IO` drains it, formats each line (`LogLine.bracketed`) and ships it, so no caller ever blocks on the network. When the control connection is down, lines are buffered in a bounded `ConcurrentLinkedQueue` (10k cap, `AtomicInteger` counter for O(1) cap check) and flushed ahead of the next live line. `beginSession()` drops the previous session's buffer at each `startConnection`, and `shutdown()` closes the queue and drops the buffer so a dead writer cannot pin them for the rest of the process (audit S-L4). `videoDecoder.logSink`, `CarCrashHandler.logSink`, and `adb.setLogSink()` all wire through `carLogSend` so every log path reaches the phone. All logs visible in phone's `/sdcard/DiLinkAuto/client.log`. Logging defaults to `BuildConfig.DEBUG` (off in release); the phone toggles it via `LOG_TOGGLE`.

### CarSessionCoordinator

Manages multi-channel network connections (`service/CarSessionCoordinator.kt`), separating connection orchestration from Android Service lifecycle:
- Manages four dedicated TCP channels: control (9637), video (9638), input (9639), and audio (9640).
- Performs initial handshake with `HandshakeFactory`, evaluating `FEATURE_AUDIO` via `isAudioNegotiated`.
- Orchestrates mid-stream rotation re-handshake and black-screen re-handshake on the existing control connection.
- Dispatches received video to `VideoDecoder`, audio to `AudioPlayer`, and app events to `CarAppListReceiver`.

### CarAdbTracker

Manages USB ADB and TCP ADB connection tracks (`service/CarAdbTracker.kt`):
- Handles USB broadcast intents, device enumeration, authentication, and background app launching.
- Maintains TCP ADB fallback logic and monitors consecutive ADB failures (`noAdbCount >= 3`).
- Tracks ADB state transitions independently of the Wi-Fi streaming state.

### CarAppListReceiver

Processes app lists and launcher metadata (`service/CarAppListReceiver.kt`):
- Decodes incoming app lists and validates icon sizes against `AppIconLimits` (`MAX_ICON_DIM`, `MAX_ICON_BYTES`) to prevent memory exhaustion on low-RAM head units.
- Manages icon cache population and updates the reactive app list state.

### AudioPlayer

Manages AAC-LC audio stream decoding and playback on the car (`audio/AudioPlayer.kt`):
- **Codec**: Configures `MediaCodec` for AAC-LC audio decoding from `AudioPayload.CONFIG` (`csd-0`).
- **Jitter Buffer**: Maintains a 150ms jitter buffer (configurable in range 60~200ms) to smooth out Wi-Fi transmission jitter without excessive audio lag.
- **Playback**: Outputs decoded PCM audio to `AudioTrack` configured for low-latency media playback.
- **Silence Metrics**: Logs periodic silence frame statistics (`silenceFrames`/`totalFrames`), closing the S23 testing contract against false-green silent passes (issue #84).

### HandshakeFactory

Builds the car's `HandshakeRequest` to the phone (`service/HandshakeFactory.kt`). Populates `deviceName` (`DiLink-${Build.MODEL}`), viewport dims, DPI, `appVersionCode`/`appVersionName`, `targetFps`, `dpiOverride`, and `bitrate`. The same builder is used at both call sites — initial WiFi connect and mid-stream rotation re-handshake — so only the viewport dims and DPI differ between the two sites; callers compute those from the current display state. Extracted from `CarConnectionService` to remove duplication between `connectToPhone` and `onCarViewportChanged`.

### VdServerDeployer

Owns deployment of the vd-server (`PipelineServer`) onto the phone from the car, via whichever ADB transport is currently available (USB or TCP), plus the retry/fallback policy (`service/VdServerDeployer.kt`). Two entry points:

- `deploy()` — runs the availability check, retry, and TCP-ADB fallback. Called from the state machine when both tracks are ready or after a USB-ADB connection completes post-handshake.
- `deployDirect(controller)` — for the dev-mode TCP-ADB path, which already has a fresh `RemoteAdbController` in hand and bypasses the availability probe.

Reads viewport dims + DPI from the host (which owns the display) via `CarViewport.size`, and reports status through the host's message sink. DPI comes from `handshakeVdDpi` (if the phone adjusted it) or `VideoConfig.calculateOptimalDpi` otherwise. Uses `VdDeploy.buildDeployPlan` for the launch command; the stop side is owned by `VdDeploy.gracefulStopCommand` inside the shared sequence (stop is not part of the plan). Extracted from `CarConnectionService` — which has since been comprehensively refactored down to 407 lines (Refs #87, #92) by extracting `CarSessionCoordinator`, `CarAdbTracker`, and `CarAppListReceiver`.

**Stopping the old engine**: Stop is a **cooperative sentinel protocol**, not a signal. Measured on MI 9 (2026-10-10): on ART, a bare `SIGTERM` terminates `app_process` immediately and **never runs the JVM shutdown hook**, so `PipelineServer.cleanup()` is skipped — the old `SIGTERM → SIGKILL` two-stage stop was silently equivalent to a force kill. Instead `vdRunDeploySequence` first writes `/sdcard/DiLinkAuto/stop-request` (`VdDeploy.gracefulStopCommand`, must return instantly — the desktop transport has a 5s deadline). The engine's watchdog polls that file, consumes it, and flips `running=false` + unparks every wait point, so `run()` unwinds through its natural exit path and the full `cleanup()` runs. `vdRunDeploySequence` then waits for exit: it polls the phone over ADB with `VdDeploy.probeExitCodeCommand` (exit 0 = alive, exit 1 = gone) for up to 12s (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, polling every 150ms; the measured sentinel→exit latency on MI 9 is **6.1~6.3s**, so the earlier 6s/10s budgets were both too tight) and needs **two consecutive GONE** probes before accepting the exit — a transient false on a transport hiccup must not green-light a launch that races a still-live engine. A probe that could not run at all (`VdProbeResult.UNKNOWN`, transport dead) is accepted immediately so the deploy never stalls. On graceful timeout a force kill (`VdDeploy.killCommandForce`) is issued and the wait retried once with the short 3s (`EXIT_WAIT_TIMEOUT_MS`) budget. Two live engines race for the same VirtualDisplay / DTA / 9638-9639 binds and the loser never runs `cleanup()` — one leaked VD per reconnect, which is what turns the car screen black after a few cycles. The engine additionally deletes a stale sentinel at startup so a request that was never consumed cannot kill the replacement instance. The orchestration itself lives in protocol-core (`VdDeploySequence.kt`) because the car, phone-Shizuku and desktop sites had already drifted into three different "wait for exit" rules.

### CarLogWriter

Car → phone log relay (`service/CarLogWriter.kt`). Callers do a non-blocking `send` (logcat + offer onto a bounded `AsyncLogQueue`, 1024) and a dedicated coroutine on `Dispatchers.IO` drains it, formats each line as a `DataMsg.CAR_LOG` frame (`LogLine.bracketed`) on the control connection. When the control connection is down, lines are buffered in a bounded `ConcurrentLinkedQueue` (10k cap, `AtomicInteger` counter) and flushed ahead of the next live line, so diagnostic context around disconnects/crashes is preserved. `beginSession()` drops the previous session's buffer on each `startConnection`, and `shutdown()` closes the queue and drops the buffer so the writer's entries are not pinned for the rest of the process (audit S-L4). `carLogEnabled` defaults to `BuildConfig.DEBUG`; the phone toggles via `LOG_TOGGLE`. Extracted from `CarConnectionService` to isolate the log machinery from the connection state machine.

### CarTouchSender

Encodes and sends touch events from the car to the phone's VD server on the input connection (port 9639, `service/CarTouchSender.kt`). A dedicated single-thread executor keeps touch encoding off the UI thread and serializes sends so the input channel's frame ordering stays stable. Drop/send counters gate log spam through one shared predicate: the first 5 sends and first 3 drops, then every 100th event. `resetCounters()` is called on disconnect so the next session's log starts fresh. Extracted from `CarConnectionService`.

### VideoDecoder

H.264 decoder using `MediaCodec` with Surface output (GPU-direct rendering). Integrates `DecodeLivenessTracker` for unified decode vitality tracking and baseline tracking, and uses `BackoffPolicy` for exponential backoff during decoder resets.

- Codec creation: `MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)` only. `REGULAR_CODECS` lists hardware decoders first, so this resolves to `OMX.qcom.video.decoder.avc` on the BYD without asking. The speculative `findHardwareAvcDecoder` + `createByCodecName` picker was removed because it called `MediaCodecInfo.isHardwareAccelerated()`, an API 29+ method that threw `NoSuchMethodError` on the API-28 BYD head unit and killed the process.
- `fps` is clamped once at `start()` by the pure `coerceFps(fps)` into `VideoConfig.MIN_FPS..MAX_FPS` (10..60). The value arrives from a persisted preference and is used twice as a divisor (`1000L / fps`) and as `KEY_OPERATING_RATE`, so a legacy or hand-edited `0` raised `ArithmeticException` inside the feed thread and killed decoding with no recovery (audit S-L2).
- Peer-supplied width/height reach `MediaFormat.createVideoFormat` and the rotation guard. Zero/negative/absurd values crash the codec or suppress rotation recovery forever, so the service clamps them through `sanitizePeerDimension` (2..4096, falling back to the car's own viewport) before they get here (audit S-M4).
- Codec lifecycle (create / configure / start / stop) runs on the service's single `decoderExecutor`, never on the main thread that delivers `surfaceCreated`/`surfaceDestroyed`; serial execution also keeps a queued stop from interleaving with a start (audit S-M3).
- `start()` / `stop()` are whole critical sections under `lifecycleLock`, and the feed thread owns the release of the codec instance it captured — releasing it from `stop()` while the thread is parked in a native `dequeueOutputBuffer` is the documented SIGSEGV pattern (audit S-07/S-08).
- Frame queue: `VideoDecoder.FRAME_QUEUE_CAPACITY` = 8 frames — sized to `FeedBurst.MAX_FRAMES_PER_ROUND`, drop-oldest on overflow (issue #163: the old 4-slot queue dropped a frame on every burst)
- `onFrameReceived()`: queues frames even before `start()` is called; CONFIG and keyframes are cached separately
- `start()`: feeds cached CONFIG first, then the cached IDR keyframe (so the codec has a reference frame immediately), then drains the queue
- Drop-oldest on queue full: prefers dropping P-frames, evicts queued P-frames for keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE = fps` for minimum decode delay
- CONFIG (SPS/PPS) cached and replayed on decoder restart; a fresh CONFIG also discards the stale cached keyframe
- Post-flush IDR resync: after 10+ consecutive `dequeueInputBuffer` drops the codec is flushed, CONFIG re-fed, and `seekingKeyFrame` set; the feed loop drains P-frames until the next IDR arrives before resuming normal feeding
- `isRunning` property for early-start coordination
- `switchSurface(newSurface)` uses `MediaCodec.setOutputSurface()` to re-attach a new surface without stop/start (eliminates the keyframe-drop storm that happens with stop()+start())
- `invalidateSurface()` gates `releaseOutputBuffer`'s render flag off so frames aren't pushed to a destroyed surface during navigation hide/show
- `outputSurfaceValid` gate prevents rendering to a destroyed surface across navigation hide/show
- `logSink` callback routes all decoder logs to phone via `carLogSend`
- Stall grading (issue #200): `StallEscalationPolicy` watches the feed loop's clock, not the render counter, so a silent link is visible **while** it happens. At 1.5 s it logs `LINK GAP … ctrlSilent=…`, calls `noteGap()` (pre-arming resync; the IDR request still goes through `SyncRequestGate`) and at 3 s it logs `STALLED …`. `ctrlSilent` is `Connection.msSinceLastFrameReceived()` on the control channel, which separates a dead link (control frames stop too) from an encoder stall (control frames keep flowing). The existing `STALL` line keeps its role as the post-recovery summary. While `ResyncPolicy` is awaiting an IDR, incoming P-frames are dropped at the receive boundary instead of filling the 8-slot queue — that backlog flush is what produced 88 drops and a second reference-chain break in the field (issue #200). Both `LINK GAP` and the escalated `STALLED` line go through `VideoDecoder.diagSink` → `CarLogWriter.sendUnconditional()`, so they survive a release build where `CarLogWriter.setEnabled(BuildConfig.DEBUG)` is false; grep them when re-verifying #200 on a real car.
- `debugFrameStats` flag (toggled by `LOG_TOGGLE`): per-30-frame log includes cumulative decode time and queue depth
- Feed thread runs at `Process.THREAD_PRIORITY_URGENT_DISPLAY` (8x A53 has no big cores; tell the scheduler this is latency-critical)

**Black screen self-heal:**

The judgement no longer lives in this file: `protocol-core`'s `BlackScreenDetector` owns it (e1674f8), so the car decoder and the Windows receiver apply one definition of "black stream" and the state machine is unit-testable without `MediaCodec`. The car injects `SystemClock.elapsedRealtime()` as its clock; the desktop side uses the default `nanoTime` divider.

- `onSustainedBlackScreen: (() -> Unit)?` — callback fired when the stream has been persistently black for `BLACK_SCREEN_SUSTAIN_MS` (3000ms, defined in `BlackScreenDetector`). A handful of tiny I-frames right after start is normal (the VD can briefly composite nothing before the first app frame lands), and re-handshaking on that would cause reconnect loops — the very thing that leaked VDs. Requiring a sustained window means we only escalate on a genuinely stuck stream.
- `tinyKeyframeStreak` / `alerted` — alert state inside the detector: after 3 consecutive tiny keyframes (<2048 bytes), `isAlerted()` flips and the car logs "Suspected BLACK SCREEN". The alert is log-only; it never triggers a recovery.
- `blackSinceMs` / `recoveryFired` — the detector's sustain window, tracked as a nullable start rather than a `0L` sentinel. `0L` both ate a black run that happens to start at clock 0 and, on a process-relative clock, made the very next tiny frame read as "already 3s ago" — a real bug in the inline version (the desktop logged "1 tiny keyframe, 10000ms" and burned both self-heal budgets on a healthy stream). `onSustainedBlackScreen` fires once per armed window; a normal-sized I-frame clears the streak, nulls the start and re-arms the escalation.
- `resetBlackScreenState()` — re-arms the detector at each `start()` and `stop()`, and explicitly before a re-handshake, which no longer stops the decoder.

### AppIconCache

Car-side icon cache that decodes and resizes icons once, then serves them instantly during scroll.

- `putSource(packageName, pngBytes)`: Stores high-resolution (192x192) source PNG received from phone via app list. Persists to disk as `{packageName}_src.png`.
- `prepareAll(apps, sizePx)`: Decodes and resizes all icons in parallel on background threads, bounded by `Semaphore(4)` (8 A53 cores; cap parallelism to bound native heap churn). Increments `preparedVersion` to trigger UI recomposition. Intermediates recycled.
- `getPrepared(packageName)`: Synchronous O(1) `ConcurrentHashMap` lookup — zero coroutines, zero I/O, zero decoding during scroll. Returns ready-to-render `ImageBitmap`.
- `get(packageName, sizePx)`: Full decode+resize path (used by NavBar for few icons). Returns `Bitmap` at requested pixel size. Does not pollute the grid-size `prepared` cache.
- `evict(packageName)`: Removes cached data for uninstalled apps.
- `clear()`: Clears all in-memory and on-disk icon caches. Called on disconnect — the phone resends icons on the next `APP_LIST`, so retaining them across sessions just wastes ~5-9MB of heap on a 4GB device and grows the eMMC cache unbounded.
- The APP_LIST path in `CarConnectionService` bounds what reaches this cache at all: a per-icon byte cap (`MAX_ICON_PNG_BYTES`, 1MB) plus an `inJustDecodeBounds` dimension cap (`MAX_ICON_DIMENSION`, 512px) so a decompression bomb never reaches `BitmapFactory`, and an aggregate `MAX_APP_LIST_ICON_BYTES` budget (64MB, checked by the pure `iconBudgetAccepts`) so one APP_LIST cannot pin hundreds of MB of source PNGs on a 4GB head unit (audit S-M5).

### ServerApp

Application class. Creates notification channel `dilinkauto_car_service` with `IMPORTANCE_LOW`. Initializes `AppIconCache` at `filesDir/icons`. Installs `CarCrashHandler` and logs device info on create.

### CarCrashHandler

Uncaught exception handler for the car app (`CarCrashHandler.kt`). On crash: saves a crash report with stack trace and device info to `filesDir/crash-pending.log`. The report is sent to the phone on the next successful connection via `carLogSend` (`CarConnectionService.onCreate` calls `consumePendingCrash()` and echoes it back through the log channel between `─── PREVIOUS CRASH REPORT ───` markers). `consumePendingCrash()` returns and archives the pending report as `crash-sent-<ts>.log` so it isn't re-sent on every connection; the archive is pruned to the newest 5 files, otherwise a crash-looping app fills the car's storage (audit S-L5). The TCP flush is bounded at `LOG_FLUSH_WAIT_MS` (300ms) rather than a fixed `Thread.sleep(1500)` inside `uncaughtException` — that wait runs on the thread that is about to die, so a long one delays the crash dialog the user sees and the next process start.

Report and device-info formatting live in `CarCrashReport` (audit R3-SRP-19), split out of the handler, which keeps only delivery: `CarCrashReport.build(thread, throwable)` emits a `=== DiLink Auto Car Crash Report <yyyy-MM-dd HH:mm:ss.SSS> ===` header, `thread=`, the stack trace and a `── Process State ──` section with `pid=`/`uid=`; `CarCrashReport.deviceInfo(context)` extends the shared `DeviceInfo.buildDeviceInfoBlock` with a Memory section and a `memThreshold` line. The phone consumes this text line by line, so the section labels are a contract — `CarCrashReportTest` (7 cases) locks the header, the US timestamp format, the thread name, the stack-frame marker, the cause chain, the `Suppressed:` section and the determinism of the body.

### RemoteAdbController

Direct ADB client using a persistent TCP ADB connection (`adb/RemoteAdbController.kt`). Uses `TcpAdbConnection` which keeps a single socket open for all commands. Provides `shell`, `shellBackground` (keeps ADB stream open — prevents `app_process` from being killed by a non-interactive shell exit), `shellNoWait`, and `disconnect`. Used by the dev-mode TCP-ADB path and by `VdServerDeployer.deployDirect`.

### CarShell

Top-level car shell: streaming layout (mirror + nav bar) or launch screen (`ui/screen/CarShell.kt`). Extracted from `MainActivity` so the Activity holds only its lifecycle concerns (service binding, USB intent forwarding, immersive mode, rotation re-handshake) and this file owns the Compose tree and screen-state routing.

- `showStreamingMode = appList.isNotEmpty() && (isConnected || state == CONNECTING)` — the CONNECTING branch keeps the streaming layout visible during a mid-stream rotation redeploy so the video-wait overlay covers the gap instead of flashing `CarLaunchScreen`.
- `CarContentArea`: shared content area for both landscape (Row) and portrait (Column) layouts — renders `MirrorContent`, the "waiting for video" overlay when the stream isn't ready, and `HomeContent` when current screen is HOME. The two layout branches differ only in nav-bar placement.
- App info dialog (`ui/screen/AppInfoDialog.kt`) shown when `appInfoData` is non-null; layout split out of this file (audit R3-SRP-19).
- `vdStackEmpty` collected → switches to HOME screen.

### MainActivity

Car-side launcher Activity (`MainActivity.kt`). Owns only process-level concerns:

- Service bind / unbind and forwarding the USB-attach intent (`ACTION_USB_DEVICE_ATTACHED`) to `CarConnectionService`.
- Immersive mode (sticky fullscreen via `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` on focus gain).
- Rotation re-handshake: `onConfigurationChanged` pushes the new viewport dims into `CarConnectionService.onCarViewportChanged` so the phone recreates the VD at the new orientation without tearing down the bound service. `configChanges` in the manifest (`orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden`) lets the Activity receive `onConfigurationChanged` instead of being recreated.
- No `screenOrientation` lock — the car screen may be a rotatable panel.

The Compose tree (streaming vs launch screen, nav bar, app-info dialog) lives in `CarShell`.

### CarLaunchScreen

Full-screen connection-focused composable shown before the phone connection is established — no nav bar, no app grid (`ui/screen/CarLaunchScreen.kt`).

- DiLink Auto branding (icon, title, tagline)
- `ConnectionStatusCard` with colored indicator dot (green=streaming, orange=connected/connecting, gray=idle) and live status text. Contains:
  - Dev mode toggle (TCP ADB instead of USB)
  - Three startup settings, each a preset chip row plus a manual field (`ui/screen/CarLaunchSettings.kt`, shared with the phone-side settings screens through `SettingsFormat`): `startup_dpi` (0 = Auto → `VideoConfig.calculateOptimalDpi`; [120, 480] override coerced by `VdDeployArgs.coerceDpiOverride` via `SettingsFormat.coerceDpi`), bitrate (presets 2 / 2.5 / 3 / 4 / 6 Mbps, `SettingsFormat.coerceBitrate`), and FPS (20 / 24 / 30 / 60, `SettingsFormat.coerceFps`, which lands inside `VideoConfig.MIN_FPS..MAX_FPS`).
  - DPI and bitrate are read at handshake construction time, so a change takes effect on the next connect or mid-stream rotation re-handshake. FPS goes through `CarConnectionService.setStartupFps`, which also applies it to the live `targetFps` immediately.
  - The manual field owns its raw text and commits only on **Done** or **focus loss** (audit UX-02). It used to echo the coerced value back on every keystroke, so typing "160" collapsed to 120 on the first digit and silently stored the wrong DPI; the seed is the stored number (`SettingsFormat.manualSeed`), never digits scraped from a display label that may read "2.5M", and input is filtered to 3 ASCII digits. The selected chip pairs its light-blue container with `onPrimary` (black, 10.48:1) instead of white (2.00:1) (audit UX-03), the hint drops its 0.7 alpha (UX-05), and the chip row is a `FlowRow` so a longer translation wraps instead of overflowing the card.
- "How to connect" instructions: 4 numbered steps
- Manual IP entry (`ManualConnectBox`)
- Replaced by streaming mode layout when `appList` becomes non-empty and state reaches CONNECTED/STREAMING

### ConnectionStatusScreen

Connection-status panel shown inside `HomeContent` when the link is not yet streaming (`ui/screen/ConnectionStatusScreen.kt`). Distinct from `CarLaunchScreen` (the full pre-streaming screen) — this composable shares `ManualConnectBox` with it. Persists the last-used IP and pre-fills with the WiFi gateway when no saved IP exists.

The gateway lookup itself is `adb/WifiGatewayProbe.kt` — one place that resolves `WIFI_SERVICE` and survives a device where it returns null (WiFi off, permission not granted, stubbed framework under test), returning null or a fallback. It used to be duplicated verbatim in the service's phone-IP fallback and the manual-connect box (DRY-7); it is `internal` because it is Android-framework code, not part of the protocol surface. Manual-connect persistence lives in `data/ManualConnectState`.

### NowPlayingBar

Bottom bar showing the current media track and transport controls (`ui/screen/NowPlayingBar.kt`). Only composed inside `HomeContent` when `MediaMetadata` is present. Large touch targets (48dp skip, 56dp play/pause) for car use.

### PersistentNavBar / PersistentBottomNavBar

Landscape (left rail, `PersistentNavBar`) and portrait (bottom bar, `PersistentBottomNavBar`) navigation bars — **only shown in streaming mode**. Both render the same trimmed action set: **Eject (disconnect) / Home / Back** — three buttons only. Notifications button, recent-apps rail, clock, and network info were removed. The action set (`NavActionButtons`) and the geometry (`rememberNavBarSize`) are each defined once and shared by both layouts, so they cannot drift as the panel rotates; the bottom bar renders the three in reverse (Eject / Home / Back) so that rotating the device keeps each button on the same physical side. 76dp bar (`CarViewport.NAV_BAR_TARGET_DP = 76f`), 40dp icons with a 48dp minimum touch width and the label exposed as `contentDescription` only — no text composable. The width is `CarViewport.navBarWidthPx`, which rounds the 76dp offset **up** so the remaining viewport (`screenWidthPx - navBarPx`) stays even for the H.264 encoder.

### HomeScreen / HomeContent / AppGrid / AppTile

The live composables formerly in `LauncherScreen.kt` now live in `HomeScreen.kt`.

- `HomeContent`: main content area when streaming mode is active. Shows `AppGrid` when streaming with apps, an explanatory empty state when streaming with an empty allowlist, or `ConnectionStatus` otherwise. Renders `NowPlayingBar` when media metadata is present.
- `AppGrid`: 64dp app icons in dynamically calculated fixed grid columns (3-12: `max(3, maxWidth / 100.dp)`), with stable `key` and `contentType` so tiles keep their identity across list updates. Search field with `imePadding()`. Alphabetical sort (lowercase sort key computed once per app), pinned apps first. `windowSoftInputMode="adjustResize"` in manifest.
- Uninstall from the tile menu now goes through a confirmation `AlertDialog` first — the request only leaves for the phone after it is accepted (audit UX-01). A search that matches nothing shows "no apps match" plus a clear-search action instead of a blank grid that reads as a load failure (UX-09), and a caption states that tile management lives behind a long-press, which a car touchscreen gives no clue about (UX-08).
- `AppTileData` (`@Immutable`): stable wrapper for `AppTile` inputs. `AppInfo` carries an unstable `ByteArray` (`iconPng`), which made `AppTile` non-skippable and forced full-grid recomposition on any `appList` reassignment. This wrapper excludes the byte array — `AppTile` reads the prepared icon from the cache by packageName, so the wrapper's identity is the only input that matters for recomposition.
- `AppTile`: long-press opens a context menu with **Pin to Top / Unpin**, **Uninstall**, and **App Info**. Pin state persists through `data/PinnedAppsRepository` (`dilinkauto_pinned`); the grid holds only a local copy to drive recomposition, the repository owns the persistence. App uninstall sends `APP_UNINSTALL` via control channel; phone processes the system dialog and sends `APP_UNINSTALLED` to refresh the car's app list. `AppInfoDataMessage` displays app info in a car-side dialog (`ui/screen/AppInfoDialog.kt`).

### MirrorScreen

Mirror content — `SurfaceView` for video + touch forwarding (`ui/screen/MirrorScreen.kt`). SurfaceView (not TextureView) so the decoder's output goes through a hardware overlay instead of an extra per-frame GL composite pass. On the Adreno 505 the TextureView composite cost (~2-5ms/frame at 1280x800) is a meaningful slice of the 50ms budget at 20fps; SurfaceView bypasses it entirely.

Tradeoff: SurfaceView destroys its surface when the view goes INVISIBLE, where TextureView kept it alive. Navigation HOME<->APP toggles `visible`, so `surfaceCreated`/`surfaceDestroyed` fire on each switch. The decoder is NOT stopped on `surfaceDestroyed` — it stays running, and `VideoDecoder.invalidateSurface()` gates the render flag off so frames aren't dropped to a destroyed surface. `surfaceCreated` calls `switchSurface` (`setOutputSurface`) to re-attach, restoring rendering with zero keyframe loss. `handleDisconnect`/`shutdown` owns the final decoder stop.

Both `SurfaceHolder.Callback` bodies are deliberately thin: they forward to `CarConnectionService.onMirrorSurfaceCreated/Destroyed`, which hand the work to a service-side single-thread executor so `MediaCodec` create/configure never runs on the main thread that delivers them (audit S-M3).

The persistent nav bar is a sibling (not overlapping) this composable in both landscape and portrait layouts (`CarShell`), so SurfaceView's default z-order — surface below the window hierarchy — renders the nav bar above the video correctly.

Touch forwarding: `ACTION_DOWN`/`ACTION_POINTER_DOWN`/`ACTION_UP`/`ACTION_POINTER_UP` send single-pointer `TouchEvent`s; `ACTION_MOVE` batches all active pointers into one `TouchMoveBatch` (reduces syscalls for multi-touch); `ACTION_CANCEL` releases all pointers to prevent ghost fingers. Coordinates are normalized to `[0,1]` by dividing by view dimensions.

### NavBarComponents

Individual nav bar widget composables (`ui/nav/NavBarComponents.kt`). `NavActionButton` (40dp icon, no label text — the label is exposed as `contentDescription` only) is the only remaining widget: it carries a 48dp `widthIn` minimum so the touch target is not a 40x60dp sliver on a control that is tapped blind while driving (audit UX-11). `ClockDisplay`, `NetworkInfo`, `RecentAppIcon`, and `NotificationsButton` were deleted along with the features they backed.

### CarTheme

Material3 dark color scheme (`CarDark`) with category-specific app tile colors: Navigation (green), Music (pink), Communication (blue), Other (gray). Colors come from protocol-core's `UiPalette` tokens (`onSurfaceVariant = 0xFFAAAAAA` replaces scattered literal grays; error red consolidated to a single `0xFFEF5350`), and the semantic state tokens (`SuccessColor` / `WarningColor` / `InfoColor` / `PinnedColor` / `NavBarBackgroundColor`) replace the raw hex literals screens used to hand-code in 20+ places. `CarTypography` (audit UX-07) is one hand-set scale — 12 / 14 / 16 / 18 / 20 / 22 / 24 / 32sp, nothing below 12sp, every bare `fontSize` in the car screens moved onto it — and it now defines the slots the screens had been relying on implicitly (`titleSmall`, `bodySmall`, `headlineSmall`, `labelMedium`), which previously fell through to the Material defaults.

### Car Display Info

Tested on BYD DiLink 4.0 (Qin PLUS DM-i 2023 Champion 55KM Leading trim — low-spec):
- SoC: Snapdragon 439 (8x Cortex-A53, Adreno 505), 4GB RAM, 16GB eMMC
- Screen: 1280x800 @ 240dpi, 2.4GHz-only WiFi, Android 9 / API 28
- H.264 hardware decode capped at 1080p

## Unit Tests

`app-server/src/test/` — JUnit 4 with no Robolectric or mocking framework: the module sets `unitTests.isReturnDefaultValues = true` and hand-writes its fakes (`ContextWrapper(null)`, injected clocks). 37 cases across 9 files; `:app-server:testDebugUnitTest` runs green offline.

- `service/CarConnectionServicePolicyTest` (3) — `reconnectBackoffMs` doubles 500 → 1000 → … → 8000 and holds the cap across a shift-clamped sweep, plus `iconBudgetAccepts` boundary arithmetic against the real 64MB `MAX_APP_LIST_ICON_BYTES` constant (6d25390: both formulas were inline and untested, and the tests used to re-derive the arithmetic instead of calling it).
- `service/CarConnectionValidationTest` (8) — peer viewport dimension clamping (including `Int` bounds), icon budget arithmetic, and the per-session black-screen rebuild budget.
- `service/CarViewportTest` (6) — even-aligned viewport math and the nav-bar offset that keeps the encoder's viewport even.
- `decoder/VideoDecoderFpsTest` (5) — `coerceFps` pulls 0 / negative / absurd values into `VideoConfig.MIN_FPS..MAX_FPS` and the divisor stays safe.
- `CarCrashReportTest` (7) — the crash-report format contract: header, US timestamp shape, thread name, stack-frame marker, `── Process State ──`, cause chain, `Suppressed:` section, body determinism. CarCrashHandler itself is deliberately not unit-tested (its `uncaughtException` path kills the JVM).
- `CarCrashHandlerArchiveTest` (5) — archive prune and consume-exactly-once.
- `adb/WifiGatewayProbeTest` (1) — gateway lookup falls back to `""` / a supplied value when `WIFI_SERVICE` cannot be resolved.
- `UsbPendingIntentTest` (1) / `MainActivityAdversarialTest` (1) — the permission `PendingIntent` flags, and the USB-intent caching lifecycle (deliver-exactly-once on bind, no re-delivery on a second connect, action filtering).

## DiLink 4.0 Low-Spec Performance

Nine fixes target the Snapdragon 439 / Adreno 505 / 4GB RAM head unit:

1. Encode dims capped to 1920x1080 (decoupled from VD dims via `EW EH` args; VD stays scaled-up to preserve the IME-crop fix, encode uses car-reported dims clamped to 1080p). Without this, >1080p phones trigger software decode on the 439 → single-digit fps.
2. Bitrate 8 Mbps → 4 Mbps; adaptive fallback tightened (floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps — was 2 Mbps / 5s / 1 Mbps).
3. Framerate 30 → 20 fps (`VideoConfig.TARGET_FPS = 20`). Per-frame budget 33ms → 50ms, -33% WiFi/GPU/allocation. (Was 24 fps before issue #71.)
4. MirrorScreen TextureView → SurfaceView (bypass Adreno 505 per-frame GL composite; `outputSurfaceValid` gate prevents rendering to destroyed surface across navigation hide/show).
5. Thread priorities: `THREAD_PRIORITY_URGENT_DISPLAY` on encode/decode/socket-drain threads; `THREAD_PRIORITY_BACKGROUND` on LifeWriter. A53 has no big cores.
6. `carLogEnabled` defaults to `BuildConfig.DEBUG` (was true); `ConcurrentLinkedQueue.size()` (O(n)) → `AtomicInteger`.
7. `AppIconCache.clear()` on disconnect (frees ~5-9MB heap + eMMC PNGs); `prepareAll` parallelized with `Semaphore(4)`, intermediates recycled.
8. `Debug.getPss()` removed from startup (deprecated binder call, 100-500ms on A53).
9. `@Immutable AppTileData` wrapper so the icon grid skips recomposition on `appList` reassignment.

## API 28 Car Compatibility

- `MediaCodecInfo.isHardwareAccelerated()` (API 29+) threw `NoSuchMethodError` on the BYD API-28 head unit, killing the process. Fix: removed the speculative hardware-decoder picker; `VideoDecoder.start()` calls `MediaCodec.createDecoderByType` directly.
- app-server `minSdk` 24 → 26; protocol `minSdk` 24 → 26 (re-arms NewApi lint gate).
- `am display move-stack` (API 29+) gated on `SDK_INT >= 29` with log on older levels (was silently masking shell failure as success).
- `cmd display power-reset` / `power-off` shell fallback is API 29+ (no `power-on` subcommand exists); on API 26-28 a DisplayControl reflection failure means the physical panel is not restored — now logged, was silent.

## Rotation Black-Screen Fix

`onCarViewportChanged` sets state to CONNECTING and tears down the VD for redeploy, but the streaming-layout gate used `isConnected` (false during the ~2s redeploy) → `CarShell` flipped to `CarLaunchScreen` → SurfaceView removed → new surface couldn't restart the decoder. Gate now also accepts `state == CONNECTING && appList.isNotEmpty()`. See `CarShell.showStreamingMode`.

The same layout-mounting fact was the root of the second half of that bug (35d7821, S-03): because the layout now stays mounted, `surfaceCreated` never fires again during a re-handshake, so anything that stops the decoder in the recovery path leaves the screen black for the rest of the session. `rehandshakeOnExistingControl` therefore tears down video/input and re-arms the black-screen detector but leaves the codec running, and `CarShell` covers the gap with the "waiting for video" overlay.

## Module Structure

```
app-server/
├── service/        CarConnectionService, HandshakeFactory, VdServerDeployer, CarLogWriter, CarTouchSender, CarPrefs, CarViewport
├── decoder/        VideoDecoder
├── adb/            RemoteAdbController, WifiGatewayProbe
├── data/           ManualConnectState, PinnedAppsRepository
├── ui/screen/      CarShell, CarLaunchScreen, CarLaunchSettings, HomeScreen, MirrorScreen, ConnectionStatusScreen, NowPlayingBar, AppInfoDialog
├── ui/nav/         PersistentNavBar, NavBarComponents
├── ui/theme/       CarTheme
├── AppIconCache.kt
├── ServerApp.kt
├── CarCrashHandler.kt
├── CarCrashReport.kt
└── MainActivity.kt
```

minSdk 26, compileSdk 34, Kotlin 1.9.22, Android Gradle 8.2.2, JDK 17.

## Dependencies

- Jetpack Compose (BOM 2023.10.01) + Material 3 + material-icons-extended
- Protocol module (shared with phone app): a thin Android-side layer (`UsbAdbConnection`, `AdbProtocol`, `TcpAdbConnection`, `DeviceInfo`, `Discovery`) that `api`-depends on `:protocol-core`, so the pure protocol surface is visible through it — `VideoConfig`, `VdDeploy`, `VdDeployArgs`, `VdDeploySequence`, `AppPrefs`, `WifiGatewayIp`, `DimAlign`, `UiPalette`
- From protocol-core (shared with the desktop receiver and tested there without Android): `BlackScreenDetector` (sustained black-screen policy), `H264NalParser` (IDR detection), `SettingsFormat` (settings coercion + manual-entry seeding), `AsyncLogQueue` (bounded log queue), `LogLine`
- `TcpAdbConnection` (in protocol/) for TCP ADB fallback

See [architecture.md](./architecture.md) for the cross-module diagram, [protocol.md](./protocol.md) for the wire format and port assignments, and [client.md](./client.md) for the phone-side orchestrator. Download from [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest).
