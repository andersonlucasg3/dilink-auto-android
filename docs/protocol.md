# Protocol Specification

## Overview

DiLink-Auto uses a custom binary protocol over **3 dedicated TCP connections** between phone and car:

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control** | 9637 | Bidirectional | Handshake, heartbeat, app commands, DATA (app list, notifications, car logs, media) |
| **Video** | 9638 | Phone → Car | H.264 CONFIG + FRAME only |
| **Input** | 9639 | Car → Phone | Touch events only |

Each connection has its own socket, NioReader, and write queue — full I/O isolation prevents video stalls from non-video traffic (e.g., large app list payloads).

The control connection is established first (handshake happens here). After handshake, the car opens video and input connections in parallel. The phone accepts all three and associates them as one session. Heartbeat/watchdog runs only on the control connection; video and input have no heartbeat overhead. Any connection dying cascades to full session teardown.

A separate internal protocol runs between the phone app and VD server on `localhost:19637` (documented in [client.md](./client.md)). The VD server reverse-connects to the phone (phone listens, VD server connects). The VD server uses fully non-blocking NIO for both reads and writes on the localhost socket.

## Wire Format

```
+---------------+------------+--------------+-----------------+
| Frame Length   | Channel ID | Message Type | Payload          |
| (4 bytes)      | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian     |            |              |                  |
+---------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Value = `2 + payload_size`.
- **Max payload**: 128 MB.
- **Header overhead**: 6 bytes per frame.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Bidirectional | Handshake, heartbeat, app commands, VD server signals |
| 1 | VIDEO | Video (9638) | Phone → Car | H.264 encoded video frames (relayed from VD server) |
| 2 | AUDIO | (reserved) | Phone → Car | Reserved (not implemented) |
| 3 | DATA | Control (9637) | Bidirectional | Notifications, app list, media metadata, car logs |
| 4 | INPUT | Input (9639) | Car → Phone | Touch events (CMD_INPUT_TOUCH for raw MotionEvent injection) |

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+------+
| protocolVersion       | int32 |
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| screenWidth           | int32 |  car display width in pixels (after nav bar)
| screenHeight          | int32 |  car display height in pixels
| supportedFeatures     | int32 |  bitmask
| displayMode           | byte  |  0=MIRROR, 1=VIRTUAL (default)
| screenDpi             | int32 |  car display density (e.g. 240)
| appVersionCode        | int32 |  car app version code
| targetFps             | int32 |  car's requested FPS (e.g. 60)
+----------------------+------+
```

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+------+
| protocolVersion       | int32 |
| accepted              | byte  |  1=accepted, 0=rejected
| deviceName length     | int16 |
| deviceName            | UTF-8 |
| displayWidth          | int32 |  VD width (matches car request)
| displayHeight         | int32 |  VD height (matches car request)
| virtualDisplayId      | int32 |  -1 (set by VD server, not phone)
| adbPort               | int32 |  5555 (phone's ADB TCP port)
| vdServerJarPath       | UTF-8 |  path to deployed VD JAR on phone (e.g. /sdcard/DiLinkAuto/vd-server.jar)
+----------------------+------+
```

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Control connection only

Empty payload. Sent every 3 seconds on the control connection. If no frame is received within 10 seconds, the connection is considered dead (watchdog timeout). Video and input connections have no heartbeat.

### DISCONNECT (0x05) -- Bidirectional

Empty payload. Graceful shutdown.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+------+
| packageName           | UTF-8 |  raw bytes, no length prefix
+----------------------+------+
```

Phone forwards to VD server which runs `am start --display <id> -n <component>` (no `--activity-clear-task` — existing apps resume).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Empty payload. Phone forwards to VD server. GO_BACK sends `input -d <id> keyevent 4`, then checks for empty stack. GO_HOME is a no-op (car handles launcher navigation).

### APP_STARTED (0x13) -- Phone -> Car

Same format as LAUNCH_APP. Confirms the app was launched.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Empty payload. Sent after GO_BACK when the VD server detects no remaining app tasks on the virtual display (via `dumpsys activity activities`). The car uses this to switch from mirror view to home screen.

### UPDATING_CAR (0x30) -- Phone -> Car

Empty payload. Sent before the phone starts auto-updating the car app. The car shows "Updating car app..." status and stops reconnecting. After the update, the car app restarts fresh.

## Video Channel (0x01)

### CONFIG (0x01) -- Phone -> Car

H.264 SPS/PPS NAL units with start codes. Sent once at encoder start.

### FRAME (0x02) -- Phone -> Car

H.264 NAL units representing a video frame.

**Encoding parameters** (set by VD server, configurable via handshake):
- Codec: H.264/AVC
- Profile: High
- Resolution: car viewport dimensions (e.g., 1806x990)
- Bitrate: 12 Mbps CBR
- Frame rate: configurable via `targetFps` in handshake (default 30, car requests 60)
- IDR interval: 1 second
- SurfaceScaler: periodic re-draw every `1000/fps` ms ensures encoder output on static content

## Data Channel (0x03)

### NOTIFICATION_POST (0x01) / NOTIFICATION_REMOVE (0x02) -- Phone -> Car

Notification data with id, packageName, appName, title, text, timestamp, progressIndeterminate (byte), progress (int32), progressMax (int32). Car deduplicates by ID (updates replace existing). Tapping a notification launches the owner app on the VD.

### APP_LIST (0x03) -- Phone -> Car

List of installed apps with packageName, appName, category (NAV/MUSIC/COMM/OTHER), iconPng (96x96 PNG).

### CAR_LOG (0x30) -- Car -> Phone

UTF-8 text line. Car routes all logs (including VideoDecoder and UsbAdbConnection) through this channel. Phone writes to FileLog (`/sdcard/DiLinkAuto/client.log`) with tag `CarLog`.

### MEDIA_METADATA (0x10) / MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

Track info and playback state. Not yet actively populated.

### MEDIA_ACTION (0x12) -- Car -> Phone

Media control (play/pause/next/previous). Not yet wired to MediaSession.

## Input Channel (0x04)

### CMD_INPUT_TOUCH (0x32) -- Car -> Phone

```
+----------------------+------+
| action                | byte  |  MotionEvent action (DOWN/MOVE/UP)
| pointerId             | int32 |  multi-touch pointer ID
| x                     | float |  normalized 0.0-1.0
| y                     | float |  normalized 0.0-1.0
| pressure              | float |
| timestamp             | int64 |
+----------------------+------+
```

Raw MotionEvent injection. The phone dispatches input frames on `Dispatchers.IO` (avoids NetworkOnMainThreadException on localhost socket write). The VD server reads commands via NIO Selector and builds full MotionEvent objects with all active pointers, injecting via IInputManager.injectInputEvent.

### TOUCH_MOVE_BATCH (0x04) -- Car -> Phone

```
+----------------------+------+
| count                 | byte  |  number of pointers
| N × pointer:          |       |
|   pointerId           | int32 |
|   x                   | float |  normalized 0.0-1.0
|   y                   | float |  normalized 0.0-1.0
|   pressure            | float |
|   timestamp           | int64 |
+----------------------+------+
```

Batched MOVE events — all active pointers in one message. Reduces syscalls for multi-touch gestures.

## Constants

```
APP_VERSION_CODE      = 46
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637 (phone <-> car, handshake + heartbeat + commands + data)
VIDEO_PORT            = 9638 (phone -> car, H.264 frames only)
INPUT_PORT            = 9639 (car -> phone, touch events only)
VD_SERVER_PORT        = 19637 (phone <-> VD server, localhost only, NIO non-blocking)
TARGET_FPS            = 60 (configurable via handshake, default 30)
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS (16ms at 60fps, max wait for video-path loops)
HEARTBEAT_INTERVAL    = 3000 ms (control connection only)
HEARTBEAT_TIMEOUT     = 10000 ms (control connection only)
MAX_PAYLOAD_SIZE      = 134,217,728 bytes (128 MB)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

## Byte Order

All multi-byte integers and floats are **big-endian**.
