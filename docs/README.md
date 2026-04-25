# DiLink-Auto

**v0.13.1** — 3-connection architecture, 60fps, NIO everywhere, full touch input, notification progress. Plug phone into car USB → car app auto-installs and updates → streaming begins.

An open-source alternative to Android Auto, purpose-built for **Xiaomi HyperOS** phones and **BYD DiLink** infotainment systems.

DiLink-Auto bridges the gap for devices unsupported by Google's Android Auto — specifically Chinese-ROM phones (HyperOS, no Google Services) paired with BYD vehicles sold in markets where only Android Auto (not CarWith/CarPlay) is available on the head unit.

## The Problem

| Device | Issue |
|--------|-------|
| Xiaomi 17 Pro Max (HyperOS 3, Chinese ROM) | No Android Auto support — no Google Play Services |
| BYD Destroyer 05 / King (Brazil market) | Ships with Android Auto only — no CarWith support |

Neither Google's nor Xiaomi's ecosystem bridges this gap. DiLink-Auto does.

## How It Works

```
┌──────────────────┐     USB (ADB)      ┌──────────────────┐
│   PHONE CLIENT   │ ◄────────────────► │   CAR SERVER     │
│   (Xiaomi)       │                    │   (BYD DiLink)   │
│                  │   WiFi (3x TCP)    │                  │
│  VD JAR deploy   │  9637: control ──► │  USB ADB launch  │
│  Video relay     │  9638: video ────► │  Video decoder   │
│  Touch routing   │  9639: input ◄──── │  Launcher UI     │
│  Car app update  │                    │  76dp nav bar    │
└──────────────────┘                    └──────────────────┘
```

**USB ADB** handles: launching the phone app, starting the VD server process.
**WiFi TCP** handles: 3 dedicated connections — control (port 9637: handshake, commands, data), video (port 9638: H.264 streaming), input (port 9639: touch events).

The phone deploys the VD server JAR to shared storage on launch. The car starts it via USB ADB. The VD server creates a VirtualDisplay at the phone's native DPI (480dpi), GPU-downscales to the car's viewport, and encodes H.264. The car decodes and renders the stream.

**Auto-update**: The phone app embeds the car APK. On handshake, if the car's version is outdated, the phone sends `UPDATING_CAR` to the car (which shows "Updating..." status), then pushes and installs the update via WiFi ADB (dadb).

## Quick Start

1. **Build:** `./gradlew :app-client:assembleDebug` (builds both — car APK is embedded in phone)
2. **Install** client on phone only
3. **Enable USB Debugging** on phone (Developer Options)
4. **Grant All Files Access** on phone (prompted on first launch)
5. **Plug phone into car USB** — phone auto-installs car app if needed
6. Both apps connect, VD server starts, streaming begins

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](./architecture.md) | 3-connection model, module responsibilities, connection flow |
| [Protocol Specification](./protocol.md) | Wire format, 3 TCP connections, message types, VD server protocol |
| [Client (Phone) App](./client.md) | ConnectionService, VD JAR deploy, auto-update, NIO sockets, FileLog |
| [Server (Car) App](./server.md) | State machine, USB ADB, VideoDecoder, car UI, touch forwarding |
| [Setup Guide](./setup.md) | Build, install, USB debugging setup |
| [Progress Tracker](./progress.md) | Feature status, milestones, roadmap |

## Current Status

**Working (v0.13.1):**
- 3-connection architecture: control (9637), video (9638), input (9639) — full I/O isolation
- Full NIO non-blocking I/O everywhere (including VD server localhost socket)
- Configurable FPS via handshake (car requests 60fps, phone/VD server honor it)
- H.264 video streaming (12Mbps CBR, High profile, 60fps target)
- SurfaceScaler periodic re-draw ensures encoder produces frames on static content
- VideoDecoder: 30-frame queue, early start on offscreen surface before MirrorScreen
- Parallel connection: WiFi + USB ADB tracks run simultaneously, VD deploys when both ready
- Auto-update: phone detects outdated car app, sends UPDATING_CAR message, car shows status
- Phone deploys VD server JAR to shared storage (no car-side push needed)
- GPU-scaled VirtualDisplay at native phone DPI (480dpi)
- Multi-touch input: batched MOVE events, direct MotionEvent injection (IInputManager, pooled arrays)
- Screen power-off during streaming (background thread, proximity/lift wake disabled)
- Battery optimization exemption (HyperOS greeze prevention)
- Car WiFi track retries gateway IP every 3s + ConnectivityManager callback
- Smart network callback: ignores unrelated network drops (mobile data), only reacts to connection's network
- Car log routing via protocol (all car logs — including VideoDecoder and UsbAdb — visible in phone's FileLog)
- FileLog: file-based logging on phone (`/sdcard/DiLinkAuto/client.log`) with rotation, bypasses HyperOS logcat filtering
- Display power: SurfaceControl via DisplayControl (services.jar, Android 14+), shell fallback
- Screen restore on disconnect: phone powers display back on when VD server dies
- Decoder catchup: skips every other frame at 2x speed when queue exceeds 100ms to stay realtime
- App grid with search (keyboard doesn't push activity), sorted alphabetically, 64dp icons
- Notifications: dedup by ID, progress bar support (determinate + indeterminate), tap-to-launch owner app
- 76dp nav bar with 40dp icons, 14sp text
- App launch dedup: existing apps resume instead of restarting
- Recent apps: prune unavailable apps when app list updates
- Eject state persisted across app restarts
- Tested on real BYD DiLink 3.0 (1920x990) + Xiaomi 17 Pro Max (Android 16)

**Not yet implemented:** audio streaming, media session control, navigation widgets, encryption.

**Known limitations:**
- VD server dies on USB disconnect (reconnects on re-plug; process detachment broke localhost)
- USB ADB auth: fixed in v0.13.1 — prehashed SHA-1 signing with NONEwithRSA. "Always allow" now persists correctly.
- Hotspot must be enabled manually (Android 16 limitation)

## Project Structure

```
DiLink-Auto/
├── protocol/       Shared library (framing, messages, discovery, USB ADB, VideoConfig)
├── app-client/     Phone APK — relay + VD JAR deploy + car auto-update + FileLog
├── app-server/     Car APK — UI + connection state machine (embedded in phone APK)
├── vd-server/      VirtualDisplay server (compiled to JAR, deployed by phone app)
├── docs/           Documentation
└── gradle/         Build system
```

## App Version

APP_VERSION_CODE read at runtime via `PackageManager.getPackageInfo()` — no hardcoded constant. Both gradle files must be bumped for each build (car auto-update compares version codes).

## License

MIT — see [LICENSE](../LICENSE)
