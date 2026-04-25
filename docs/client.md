# Phone Client App (app-client)

## Overview

The phone client manages VD server deployment, car auto-update, and 3-connection relay. The phone app:

1. Listens for TCP connections from the car on port 9637 (control, NIO ServerSocketChannel)
2. Responds to the handshake with device info, vdServerJarPath, and reads `targetFps`
3. Checks appVersionCode from handshake — sends `UPDATING_CAR` and auto-updates car app via dadb if version mismatch
4. Accepts video (9638) and input (9639) connections from the car after handshake
5. Deploys vd-server.jar to `/sdcard/DiLinkAuto/` and starts VD server (with FPS arg)
6. Accepts reverse connection from VD server on localhost:19637 (NIO ServerSocketChannel)
7. Relays H.264 video from VD server to car via the video connection
8. Relays touch events from car (input connection) to VD server, dispatched on `Dispatchers.IO`

**No screen capture. No MediaProjection.** All video comes from the VD server process.

## Components

### MainActivity

Entry point with two screens:

- **OnboardingScreen** (first launch): guides the user through each required permission one at a time — All Files Access, Battery Optimization, Accessibility Service, Notification Access. Each step explains what breaks without the permission. User can grant or skip. On completion, the main screen appears.
- **ClientScreen** (subsequent launches): status card, start/stop button, Install on Car, self-update card, and remaining permission status for anything skipped during onboarding.

### ConnectionService

Foreground service that manages the phone-car connection lifecycle with 3 dedicated connections.

- **Control connection** (port 9637): NIO TCP server on `0.0.0.0`, handles handshake, heartbeat, app commands, DATA channel
- **Video connection** (port 9638): accepted after handshake, passed to VirtualDisplayClient for video relay
- **Input connection** (port 9639): accepted after handshake, INPUT frame listener dispatched on `Dispatchers.IO` to avoid NetworkOnMainThreadException on localhost touch writes
- `deployAssets()`: extracts vd-server.jar to sdcard, app-server.apk to filesDir
- Detects version mismatch → sends `UPDATING_CAR` → auto-updates car app via dadb (WiFi ADB, dadb 1.2.10)
- Smart network callback: `onLost` checks if the lost network is the one the connection uses, ignores unrelated drops (mobile data cycling)
- Relays video frames (H.264 CONFIG + FRAME) from VD to car via video connection
- Routes touch events from car (input connection) to VD server
- Sends app list with 96x96 PNG icons to car via control connection
- Handles LAUNCH_APP, GO_BACK, GO_HOME commands and forwards to VD server
- Queues app launches if VD server is not connected yet
- Registers mDNS service for car auto-discovery
- `FileLog.rotate()` on service start — archives previous session log
- "Install on Car" button: manual install + automatic on handshake version mismatch

### VirtualDisplayClient

Accepts reverse connection from the VD server process on `localhost:19637`. Takes two Connection params: `videoConnection` and `controlConnection`.

- NIO ServerSocketChannel accept (non-blocking) — VD server connects TO phone
- Reads: `MSG_VIDEO_CONFIG`, `MSG_VIDEO_FRAME`, `MSG_STACK_EMPTY`, `MSG_DISPLAY_READY`
- Writes: `CMD_LAUNCH_APP`, `CMD_GO_BACK`, `CMD_GO_HOME`, `CMD_INPUT_TOUCH` (0x32)
- Video frames relayed via `videoConnection.sendVideo()` (isolated from control traffic)
- Stack empty signal (`MSG_STACK_EMPTY`) forwarded to car via `controlConnection.sendControl()`
- Touch writes to localhost are synchronous with `FrameCodec.writeAll()` under `writeLock`
- On disconnect: restores physical display (`cmd display power-on 0` + `KEYCODE_WAKEUP`) as safety net when VD server process is killed before cleanup

### FileLog

File-based logger that bypasses Android logcat filtering (HyperOS filters `Log.i/d` for non-system apps).

- Writes to `/sdcard/DiLinkAuto/client.log`
- `rotate()`: archives current log as `client-YYYYMMDD-HHmmss.log`, starts fresh
- Keeps 10 logs max (9 archived + current)
- Thread-safe: lock-free ConcurrentLinkedQueue drained by writer thread
- Also calls `android.util.Log.*` for standard logcat output

### Multi-Touch Relay

Touch events arrive from the car via the input connection as CMD_INPUT_TOUCH (0x32) with raw MotionEvent data. The `handleInputFrame` is dispatched on `Dispatchers.IO` (not Main) to allow localhost socket writes. The phone streams DOWN/MOVE/UP events with pointerId directly to the VD server, which handles full MotionEvent construction with all active pointers.

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| MANAGE_EXTERNAL_STORAGE | All Files Access for sdcard deployment of VD JAR |
| Accessibility Service | Input injection on physical display (fallback) |
| Notification Access | Forward notifications to car (with progress) |
| USB Debugging | Required for car USB ADB track (Developer Options) |

## Dependencies

- Jetpack Compose + Material 3
- kotlinx-coroutines
- dadb 1.2.10 (WiFi ADB for car auto-update)
- Protocol module (shared with car app)
