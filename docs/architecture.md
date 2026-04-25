# Architecture

## Overview

DiLink-Auto is a three-module Gradle project (v0.13.1) plus a standalone VD server:

```
DiLink-Auto/
├── protocol/        Android library -- shared by both apps (Gradle module)
├── app-client/      Android application -- runs on the phone (Gradle module)
├── app-server/      Android application -- runs on the car (Gradle module)
└── vd-server/       Standalone Java -- compiled directly by app-client's buildVdServer task,
                     NOT a Gradle module (not in settings.gradle.kts)
```

## Virtual Display Architecture

The **phone** deploys and starts the VD server locally. The VD server connects back to the phone app (reverse connection on localhost:19637, fully NIO non-blocking). Apps render on a VirtualDisplay at the phone's native DPI (480dpi), GPU-downscaled to the car's viewport resolution for H.264 encoding. The phone's physical screen is completely independent.

```
+- Phone -----------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Physical Screen      |  Independent -- user can use phone      |
|  | (shows DiLink Auto   |  normally                               |
|  |  app or anything)    |                                          |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 3 NIO TCP servers    |<----+| Deployed by PHONE app          |  |
|  |  9637: control       | 19637| Reverse-connects to phone      |  |
|  |  9638: video         |  NIO | NIO non-blocking localhost     |  |
|  |  9639: input         |      |                                |  |
|  | VD JAR deploy        |      | VirtualDisplay (phone DPI)     |  |
|  | Car auto-update      |      | GPU SurfaceScaler (downscale)  |  |
|  | FileLog              |      |   periodic re-draw on idle     |  |
|  | Relays video to car  |      | H.264 encoder (car viewport)   |  |
|  | Forwards touch to VD |      | App launcher (am start)        |  |
|  +----------+-----------+      | Input injector (IInputManager) |  |
|             |                  | NIO write queue + Selector read |  |
|             |                  +--------------------------------+  |
+-------------+------------------------------------------------------+
              | WiFi TCP (3 connections)
              | 9637: control + data
              | 9638: H.264 video
              | 9639: touch input
              v
+- Car (BYD DiLink 3.0) -------------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Parallel connection model: WiFi + USB tracks run simultaneously  |
|                                                                   |
|  Track A (WiFi):                                                  |
|  +-- Gateway IP + mDNS discovery                                 |
|  +-- Control connect (9637) → handshake (viewport+DPI+FPS)       |
|  +-- Video (9638) + Input (9639) connect after handshake          |
|  +-- Receives H.264 video → VideoDecoder → TextureView            |
|  +-- Sends touch events via input connection                      |
|                                                                   |
|  Track B (USB):                                                   |
|  +-- Scan USB devices → USB ADB connect (logSink for diagnostics)|
|  +-- Launch phone app (am start)                                 |
|                                                                   |
|  Phone auto-updates car app via dadb when version mismatch       |
|  Car receives UPDATING_CAR message → shows status, stops reconnect|
|                                                                   |
|  UI: LauncherScreen → MirrorScreen                               |
|  Nav bar (76dp): Notifications (badge+progress), Home, Back      |
|  40dp icons, 14sp text                                            |
+-------------------------------------------------------------------+
```

## Why USB ADB from Car?

`app_process` must run as shell UID (2000) to create VirtualDisplays that can host third-party apps. The car connects to the phone's `adbd` via USB host mode using a custom ADB protocol implementation (`UsbAdbConnection` in protocol/ module).

**Current approach:** The car acts as USB ADB host. The phone only needs **USB Debugging** enabled (standard Developer Option). No Wireless Debugging, no pairing codes, no WiFi dependency for setup.

## Module Responsibilities

### protocol (Android Library)

Shared by both apps. Contains UsbAdbConnection, AdbProtocol, VideoConfig, and NioReader. Zero dependencies beyond Kotlin coroutines.

| Component | File | Purpose |
|-----------|------|---------|
| Frame codec | `FrameCodec.kt` | Binary frame encoding/decoding, reusable header buffer, NIO writeAll |
| Channels | `Channel.kt` | Channel IDs (control, video, audio, data, input) |
| Message types | `MessageType.kt` | Byte constants including `VD_STACK_EMPTY`, `UPDATING_CAR` |
| Messages | `Messages.kt` | Serializable data classes (handshake includes `appVersionCode`, `vdServerJarPath`, `targetFps`) |
| Connection | `Connection.kt` | TCP connection with optional heartbeat/watchdog, lock-free write queue, NioReader |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS`, `FRAME_INTERVAL_MS` — shared timing constants |
| NioReader | `NioReader.kt` | Selector-based non-blocking reader, configurable select timeout |
| Discovery | `Discovery.kt` | mDNS service registration/discovery, port constants (9637/9638/9639) |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | ADB protocol over USB (CNXN, AUTH, OPEN, WRTE), logSink callback |
| AdbProtocol | `adb/AdbProtocol.java` | ADB message constants and serialization |

### app-client (Phone Application)

Manages VD server deployment, car auto-update, 3-connection relay, and FileLog.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | 3-port accept (9637/9638/9639), VD JAR deploy, car auto-update, smart network callback |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | NIO accept on localhost:19637, video relay (videoConnection), touch forwarding, stack empty (controlConnection) |
| NotificationService | `service/NotificationService.kt` | Captures and forwards phone notifications with progress |
| InputInjectionService | `service/InputInjectionService.kt` | Touch injection fallback (physical display) |
| FileLog | `FileLog.kt` | File-based logging to `/sdcard/DiLinkAuto/client.log`, rotation, bypasses logcat filtering |
| MainActivity | `MainActivity.kt` | UI — start/stop, permission status, Install on Car button |

### app-server (Car Application)

Parallel connection model with WiFi (3 connections) and USB tracks.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Parallel state machine, 3-connection WiFi + USB tracks, UPDATING_CAR handling |
| VideoDecoder | `decoder/VideoDecoder.kt` | H.264 decode, 30-frame queue, early start on offscreen surface, logSink callback |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | TextureView + touch forwarding, decoder restart on surface available |
| LauncherScreen | `ui/screen/LauncherScreen.kt` | App grid (64dp icons, 160dp cells), search (imePadding), alphabetical sort |
| NotificationScreen | `ui/screen/NotificationScreen.kt` | Notification list with progress bars, tap-to-launch |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | 76dp nav bar (40dp icons, 14sp text), recent apps (pruned) |
| RecentAppsState | `ui/nav/RecentAppsState.kt` | Tracks recent apps, prunes unavailable |
| MainActivity | `MainActivity.kt` | Fullscreen immersive, USB intent forwarding, screen routing |

### vd-server (Shell-Privileged Process)

Standalone Java source, compiled directly by `app-client/build.gradle.kts` `buildVdServer` task (javac → d8 → JAR), NOT a Gradle module. Deployed by phone to `/sdcard/DiLinkAuto/`.

| Component | File | Purpose |
|-----------|------|---------|
| VirtualDisplayServer | `VirtualDisplayServer.java` | Creates VD, NIO write queue + Selector reader, H.264 encoder, configurable FPS |
| FakeContext | `FakeContext.java` | Spoofs `com.android.shell` for DisplayManager access |
| SurfaceScaler | `SurfaceScaler.java` | EGL/GLES GPU downscale pipeline, periodic re-draw on idle |

## Connection Flow

```
1. Phone and car on same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB tracks

   Track A (WiFi):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + targetFps
   d. Phone responds with device info + vdServerJarPath
   e. Phone checks appVersionCode -- if mismatch, sends UPDATING_CAR, auto-updates via dadb
   f. Car connects video (9638) + input (9639) in parallel after handshake
   g. Phone accepts both, session fully established

   Track B (USB):
   a. Scan USB devices for ADB interface
   b. USB ADB connect (CNXN -> AUTH -> connected), logSink routes to carLogSend
   c. Launch phone app via am start

3. Phone deploys vd-server.jar to /sdcard/DiLinkAuto/
4. Car starts: CLASSPATH=jar app_process / VirtualDisplayServer W H DPI PORT EW EH FPS
5. VD server creates VirtualDisplay (phone DPI) + GPU SurfaceScaler (periodic re-draw)
6. VD server reverse-connects TO phone on localhost:19637 (NIO non-blocking)
7. Phone accepts VD server connection via NIO ServerSocketChannel
8. Car starts VideoDecoder on offscreen surface immediately on first CONFIG frame
9. MirrorScreen shows, restarts decoder with real TextureView surface
10. Video streams: VD -> SurfaceScaler -> encoder -> NIO write queue -> localhost -> phone NioReader -> videoConnection write queue -> WiFi TCP -> car NioReader -> VideoDecoder -> TextureView
```

States: IDLE -> CONNECTING -> CONNECTED -> STREAMING

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **3 dedicated TCP connections** | Control/video/input on separate sockets. Eliminates cross-channel backpressure (app list can't stall video). |
| **Parallel WiFi + USB tracks** | Both run simultaneously; checkAndAdvance() evaluates state when any prerequisite changes. |
| **Phone deploys VD JAR** | No car-side push needed. Phone extracts vd-server.jar to sdcard via deployAssets(). |
| **Reverse VD connection** | VD server connects TO phone (not phone to VD), simplifying firewall/NAT handling. |
| **NIO non-blocking everywhere** | All sockets non-blocking: WiFi connections, VD server localhost, Selector-based reads. No blocking I/O in the pipeline. |
| **Configurable FPS** | Car sends `targetFps` in handshake, VD server uses it. All pipeline timeouts derived from `FRAME_INTERVAL_MS = 1000/fps`. |
| **Periodic SurfaceScaler re-draw** | Feeds encoder input every frame interval even on static content. Prevents encoder starvation. |
| **Early decoder start** | Decoder starts on offscreen SurfaceTexture when first CONFIG arrives, before MirrorScreen's TextureView is created. Prevents keyframe loss during UI composition. |
| **Smart network callback** | `onLost` ignores unrelated network drops (mobile data). Only tears down session if the active connection's network is lost. |
| **Car auto-update with messaging** | Phone sends UPDATING_CAR before installing. Car shows status, doesn't reconnect blindly. |
| **Car APK embedded in phone APK** | Build system bundles app-server.apk inside app-client, enabling Install on Car feature. |
| **GPU SurfaceScaler** | VD renders at phone's 480dpi (no compat scaling). GPU downscales to car viewport. |
| **FakeContext** | ActivityThread + getSystemContext() for real system Context. Bypasses UserManager NPE via mDisplayIdToMirror reflection. |
| **Trusted VD flags** | `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED` prevent activity migration. |
| **Even viewport width** | Nav bar width adjusted to guarantee H.264-compatible even dimensions. |
| **Heartbeat on control only** | Video and input connections have no heartbeat overhead. Control connection watchdog detects dead peers. |
| **FileLog** | Bypasses HyperOS logcat filtering. File-based logging with rotation on `/sdcard/DiLinkAuto/`. |
| **logSink callbacks** | VideoDecoder and UsbAdbConnection route logs through protocol to phone's FileLog. |
| **ADB prehashed auth** | AUTH_SIGNATURE uses `NONEwithRSA` + SHA-1 DigestInfo prefix (prehashed). Matches AOSP's `RSA_sign(NID_sha1)`. "Always allow" persists correctly. |
| **Display power via SurfaceControl** | `DisplayControl` loaded from `services.jar` via `ClassLoaderFactory` (Android 14+). Falls back to `cmd display power-off/on`. Phone restores display on VD disconnect. |
| **Decoder catchup** | When queue exceeds 100ms of frames, skips every other non-keyframe (2x playback speed) to catch up with realtime. |
| **App launch dedup** | `am start` without `--activity-clear-task`. Existing apps resume instead of restarting. |
| **User disconnect** | Stays IDLE, no auto-reconnect. Persisted to SharedPreferences. |

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 (apps), Java 17 (VD server) |
| Build | Gradle 8.7, AGP 8.2.2 |
| UI | Jetpack Compose + Material 3 |
| Video | MediaCodec H.264 (encoder: VD server 12Mbps CBR High, decoder: car) |
| GPU | EGL14 + GLES20 + SurfaceTexture (SurfaceScaler with periodic re-draw) |
| Networking | NIO ServerSocketChannel / SocketChannel / Selector, Android NSD (mDNS) |
| USB ADB | Custom protocol in protocol/ module (shared), logSink for diagnostics |
| WiFi ADB | dadb 1.2.10 (car auto-update) |
| Async | Kotlin Coroutines + Flow |
| Min API | 29 (Android 10) |
| App Version | versionCode = 46 (read at runtime via PackageManager) |
| Protocol Version | PROTOCOL_VERSION = 1 |
