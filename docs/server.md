# Car Server App (app-server)

## Overview

The car server app runs on the BYD DiLink infotainment system. It uses a **parallel connection model** with WiFi (3 dedicated connections) and USB tracks running simultaneously:

1. Track A (WiFi): gateway IP + mDNS discovery, control connect (9637), handshake with phone
2. After handshake: video (9638) + input (9639) connections opened in parallel
3. Track B (USB): scan devices, USB ADB connect (with logSink diagnostics), launch phone app
4. `checkAndAdvance()` evaluates state when any prerequisite changes
5. Receives H.264 video and renders on car display (early decoder start on offscreen surface)
6. Captures touch events and sends to phone via input connection for VD input injection

States: IDLE → CONNECTING → CONNECTED → STREAMING

Car APK is embedded in the phone APK. The phone auto-updates the car app via dadb when version mismatch is detected during handshake. Car receives `UPDATING_CAR` message and shows status instead of reconnecting blindly.

### Two-Mode UI

The car app separates the connection flow from the streaming experience into two distinct modes, matching the phone app's approach:

- **Launch mode** (`CarLaunchScreen`): Full-screen, connection-focused. Shows branding, step-by-step connection instructions, connection status, and manual IP entry. No navigation bar — the entire screen is dedicated to getting connected. Shown when the car app starts and remains until app icons are received from the phone via the control connection.

- **Streaming mode** (`CarShell` with `PersistentNavBar`): The familiar layout with left navigation bar (notifications, home, back, recent apps) and content area (app grid, mirror view, notifications). Shown once `appList` is non-empty and the state is CONNECTED or STREAMING.

The transition trigger: when the phone sends `APP_LIST` via the control connection and the connection state reaches CONNECTED/STREAMING, the UI switches from launch mode to streaming mode.

## Components

### CarConnectionService

Foreground service managing the full connection lifecycle with a parallel prerequisite state machine and 3 dedicated connections.

**3-Connection Architecture:**
- `controlConnection`: handshake, heartbeat, app commands, DATA channel (app list, notifications, car logs)
- `videoConnection`: H.264 video frames only (phone → car)
- `inputConnection`: touch events only (car → phone)
- Heartbeat/watchdog on control connection only; video and input have no heartbeat overhead
- Any connection dying cascades → full session teardown

**Parallel Track Architecture:**
- `connectionScope`: parent Job for all discovery coroutines, cancelled on disconnect
- Track A and Track B run simultaneously
- `checkAndAdvance()` evaluates overall state when any prerequisite changes
- User disconnect: stays IDLE, no auto-reconnect (persisted to SharedPreferences)

**Track A — WiFi:**
- Discovery: gateway IP (hotspot/LAN, retries every 3s) → mDNS → manual IP
- Control connection: NIO non-blocking SocketChannel connect to phone TCP:9637
- Handshake: sends viewport dimensions + DPI + appVersionCode + targetFps (60) → receives phone info + vdServerJarPath
- On handshake response: opens video (9638) + input (9639) connections in parallel, sets `wifiReady = true` after all 3 established
- Video: receives H.264 frames via video connection, dispatches to VideoDecoder
- Touch: dedicated single-thread executor, `sendTouchEvent()` / `sendTouchBatch()` via input connection
- Heartbeat: 3s interval, 10s watchdog timeout (control connection only)
- Backoff: exponential delay on reconnect failures

**Track B — USB:**
- Registers `BroadcastReceiver` for `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED`
- `MainActivity` forwards USB intents to the service
- Scan USB devices for ADB interface
- USB ADB connect via UsbAdbConnection (in protocol/ module), with `logSink` routing all ADB auth logs to phone's FileLog
- Launches phone app: `am start -n com.dilinkauto.client/.MainActivity`

**Update Flow:**
- If phone sends `UPDATING_CAR`, car sets `updatingFromPhone = true`, shows "Updating car app..." status
- Skips video/input connection attempts and reconnect loop during update
- After `pm install -r`, car app restarts fresh

**State Flows:**
- `_state`, `_phoneName`, `_appList`, `_notifications`, `_mediaMetadata`, `_playbackState`: primary state exposed to UI
- `_videoReady`: true when first non-config video frame arrives
- `_statusMessage`: human-readable status for UI display
- `_vdStackEmpty` (SharedFlow): emitted when phone reports VD has no activities (triggers navigation to home)

**VD Server Launch:**
- `deployVdServer()`: `shellNoWait` with CLASSPATH from handshake's vdServerJarPath
- Args: `W H DPI PORT EW EH FPS` — FPS passed from handshake's targetFps

**Early Decoder Start:**
- On first CONFIG video frame, starts VideoDecoder on offscreen SurfaceTexture (before MirrorScreen)
- MirrorScreen's `onSurfaceTextureAvailable` stops and restarts decoder with real TextureView surface
- Prevents keyframe loss during UI composition delay

**Car Log Routing:**
- `carLogSend()` routes all car-side logs through DATA channel `CAR_LOG` to phone
- `videoDecoder.logSink` and `adb.setLogSink()` wire VideoDecoder and UsbAdbConnection logs through the same path
- All logs visible in phone's `/sdcard/DiLinkAuto/client.log`

### VideoDecoder

H.264 decoder using MediaCodec with Surface output (GPU-direct rendering).

- Frame queue: 15 frames — buffers startup race and network jitter
- `onFrameReceived()`: queues frames even before `start()` is called
- `start()`: feeds cached CONFIG first, then drains queue
- Drop-oldest on queue full: prefers dropping P-frames, evicts queued P-frames for keyframes/CONFIG
- `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0` for minimum decode delay
- CONFIG (SPS/PPS) cached and replayed on decoder restart
- `isRunning` property for early start coordination
- `logSink` callback routes all decoder logs to phone via carLogSend
- Catchup mode: four graduated speedup zones based on queue depth — normal (0-6 frames), gentle 1.5x (7-12 frames, skips 1 of 3 non-keyframes), medium 2x (13-20 frames, skips 1 of 2), aggressive 3x (21+ frames, skips 2 of 3). Keyframes never skipped.
- Flushes codec and re-feeds CONFIG on 10+ consecutive dequeueInputBuffer drops

### ServerApp

Application class. Creates notification channel `dilinkauto_car_service` with `IMPORTANCE_LOW`.

### RemoteAdbController

Direct ADB client using dadb library. Provides tap, swipe, back, home, and app launch via shell commands on the virtual display. Used as an alternative input path.

### CarLaunchScreen

Full-screen connection-focused composable shown before the phone connection is established — no nav bar, no app grid.

- DiLink Auto branding (icon, title, tagline)
- Connection status card with colored indicator dot (green=streaming, orange=connected/connecting, gray=idle) and live status text
- "How to connect" instructions: 4 numbered steps (enable hotspot, plug USB, open phone app, wait for auto-connect)
- Manual IP entry for direct connection
- Replaced by streaming mode layout when `appList` becomes non-empty and state reaches CONNECTED/STREAMING

### PersistentNavBar

76dp left navigation bar — **only shown in streaming mode** — with:
- Clock display (HH:mm, updates every 1s)
- Eject button (disconnects and persists user preference)
- Network status indicator
- Notifications button with unread badge count
- Home button
- Back button
- Recent app icons (max 5, pruned when apps become unavailable)
- 40dp icons, 12-14sp text

Width computed to guarantee even viewport for H.264 encoder.

### NotificationScreen

- Notification list sorted by timestamp (newest first)
- Dedup by ID: updates replace existing (handles progress notifications)
- Progress bars: determinate (filled) and indeterminate (spinning)
- Tap-to-launch: tapping a notification launches the owner app on the VD and switches to mirror view

### App Grid (HomeContent)

Shown as the main content area when streaming mode is active and current screen is HOME:
- Search field with `imePadding()` — keyboard doesn't push activity, only search bar moves
- `windowSoftInputMode="adjustNothing"` in manifest
- 64dp app icons in 160dp adaptive grid cells
- App name text: bodyLarge
- Alphabetical sort
- Manual IP entry
- Connection status

### LauncherScreen (Legacy)

Full integrated launcher layout with `CarStatusBar`, `SideNavBar` (80dp), and `AppGrid`. Not used in the current `CarShell` routing — the active UI uses `PersistentNavBar` + `HomeContent`/`MirrorContent`/`NotificationContent` composables inline.

### RecentAppsState

Tracks recently launched apps (max 5), persisted to SharedPreferences. `pruneUnavailable()` removes apps no longer present when app list updates.

### NavBarComponents

Individual nav bar widget composables: `ClockDisplay` (updates every 1s), `NetworkInfo` (connected/disconnected state), `RecentAppIcon` (40dp, with active state highlight), `NavActionButton` (40dp icons, 12sp labels).

### CarTheme

Material3 dark color scheme (`CarDark`) with category-specific app tile colors: Navigation (green), Music (pink), Communication (blue), Other (gray).

### Car Display Info

Tested on BYD DiLink 3.0:
- Screen: 1920x990 @ 240dpi
- Viewport: ~1806x990 (after 76dp nav bar)
- VD: ~3282x1800 @ 480dpi (GPU-scaled to ~1806x990 for encoding)

## Dependencies

- Jetpack Compose + Material 3
- Protocol module (shared with phone app, includes UsbAdbConnection + AdbProtocol + VideoConfig)
- dadb 1.2.10 (TCP ADB fallback)
