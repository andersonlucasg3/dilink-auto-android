# Architecture

## Overview

DiLink-Auto is a six-module Gradle project that mirrors a phone's VirtualDisplay onto a BYD DiLink car head unit over WiFi. The phone runs the launcher apps on a shell-UID VirtualDisplay; the VD server encodes the rendered frames to H.264, encodes system audio to AAC-LC, and streams them directly to the receiver. The phone is a pure orchestrator — it does not relay video, audio, or touch. The receiver itself is replaceable: the car app (`app-server`) and the Windows desktop client (`app-desktop`) speak the same protocol over the same direct channels (four ports: 9637 control+data, 9638 video, 9639 touch input, 9640 audio).

```
DiLink-Auto/
├── protocol-core/   Pure JVM library -- framing/messages/constants/VD deploy sequence  (no Android)
├── protocol/        Android library -- shared by all three Android apps (Gradle module) minSdk 26
├── app-client/      Android application -- runs on the phone                           minSdk 29
├── app-server/      Android application -- runs on the car                             minSdk 26
├── app-desktop/     Kotlin/JVM + Compose Desktop -- runs on Windows                    JDK 17
├── vd-server/       Android library -- shell-privileged process, compiled to a JAR     minSdk 29
└── docs/            documentation
```

Tested against a BYD Qin PLUS DM-i 2023 Champion 55KM Leading trim — a DiLink 4.0 low-spec head unit (Snapdragon 439, 8x Cortex-A53, Adreno 505, 4GB RAM, 16GB eMMC, 1280x800, 2.4GHz-only WiFi, Android 9 / API 28, H.264 hardware decode capped at 1080p). The streaming pipeline is tuned for this hardware; see the DiLink 4.0 perf section for the rationale.

## Virtual Display Architecture

The **phone** creates a VirtualDisplay at the negotiated VD size and a shell-UID process (`vd-server`) that owns the encoder. Two different sizes are in play and must not be conflated: the **VD size** is the car viewport anti-crop scaled up to the phone's *real* physical long edge (`VdDimensions.compute`, shared in `:protocol-core`), while the **encode size** is the car-native viewport clamped to 1920x1080. The VD server binds `9638` (video), `9639` (input), and `9640` (audio, when negotiated) directly; the receiver connects to those ports on the phone's IP and exchanges H.264 + touch + AAC-LC audio with the VD server, with no phone-app relay in the hot path. The phone app's role is orchestration: handshake and feature negotiation (`FEATURE_AUDIO`), VD lifecycle (the `VD_PORTS_BOUND` control message), and car-log routing. `VirtualDisplayClient` on the phone is lifecycle-only — it owns the localhost command channel, not video, audio, or touch.

**Receiver is replaceable.** Nothing in that contract is car-specific: the handshake (`HandshakeRequest`/`HandshakeResponse`), the four ports (9637 control+data, 9638 video, 9639 input, 9640 audio) and the deploy sequence are protocol, not platform. `app-desktop` is a second receiver that speaks the same bytes:

```
Phone (app-client)                        Receiver (app-server OR app-desktop)
  ConnectionService  9637 <-- handshake --->  connection state machine
  VD server      --- 9638 -- H.264 ------>    decoder -> SurfaceView / Swing canvas
                 --- 9639 -- touch <------    touch encoder (mouse or car panel)
                 --- 9640 -- audio ------>    AudioPlayer -> AudioTrack (car)
                   19647 <-- lifecycle ----   (phone-local only)
```

Who deploys the VD server depends on the phone: with Shizuku the phone does it itself; otherwise `HandshakeResponse.connectionMethod` tells the receiver to do it over ADB (the car uses USB/TCP ADB, the desktop shells out to the local `adb.exe`). Both receivers stop at the same place — `exec app_process` must stay attached to a live shell stream, because adbd reaps the engine the moment that stream closes.

A separate lifecycle channel on `localhost:19647` carries `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` between the phone and the VD server. The VD server reverse-connects to the phone on this port (NIO, non-blocking). The phone's listener binds **`127.0.0.1` only** and refuses non-loopback peers (audit A-M10) — the channel is phone-local by definition, so nothing on the WiFi can insert itself into it.

```
+- Phone -----------------------------------------------------------+
|                                                                   |
|  +---------------------+                                          |
|  | Physical Screen      |  Independent -- user can use phone      |
|  | (shows DiLink Auto   |  normally while streaming               |
|  |  app or anything)    |                                          |
|  +---------------------+                                          |
|                                                                   |
|  +---------------------+      +--------------------------------+  |
|  | ConnectionService    |      | VD Server (shell UID 2000)     |  |
|  | 1 NIO TCP server     |      | Deployed by phone or receiver  |  |
|  |  9637: control+data  | 19647| Reverse-connects lifecycle     |  |
|  |  Handshake           |  NIO | VirtualDisplay (car viewport)  |  |
|  |  VD lifecycle        |      | GL render -> H.264 encoder     |  |
|  |  Car-app install     |      | TCP write 9638 (video)         |  |
|  |  Car log routing     |      | Touch read 9639 (input)        |  |
|  |  Allowlist filter    |      | Audio write 9640 (audio)       |  |
|  +----------+-----------+      | Display power + IME restore    |  |
|  +----------+-----------+      | App launcher (am start)        |  |
|             |                  | Input injector (IInputManager) |  |
|             |                  +--------------------------------+  |
+-------------+----------------------+-------------------------------+
              | WiFi TCP (control)   | WiFi TCP (direct VD <-> car)
              | 9637: control + data | 9638: H.264 video
              |                      | 9639: touch input
              |                      | 9640: AAC-LC audio
              v                      v
+- Car (BYD DiLink 4.0) --------------------------------------------+
|                                                                   |
|  CarConnectionService                                             |
|  Parallel connection model: WiFi control + WiFi direct video/input|
|  + USB track (ADB) and TCP ADB fallback                           |
|                                                                   |
|  Track A (WiFi control):                                          |
|  +-- Gateway IP + mDNS discovery                                  |
|  +-- Control connect (9637) -> handshake (viewport+DPI+FPS)       |
|  +-- Awaits VD_PORTS_BOUND -> connect direct channels (9638/9639/9640) |
|  +-- Receives H.264 video -> VideoDecoder -> SurfaceView          |
|  +-- Receives AAC-LC audio -> AudioPlayer (150ms buffer) -> AudioTrack |
|  +-- Sends touch events via input connection (9639)               |
|                                                                   |
|  Track B (USB ADB / TCP ADB):                                     |
|  +-- Used to deploy the VD server onto the phone (shell UID)      |
|  +-- USB host mode OR TCP ADB to phone:5555                       |
|  +-- Launch phone app (am start)                                  |
|                                                                   |
|  UI: CarLaunchScreen -> (app icons arrive) -> CarShell + NavBar   |
|  Two modes: launch (connection-focused, no nav) and streaming     |
|  Nav bar (rail or bottom): Eject / Home / Back only               |
|  40dp icons                                                       |
+-------------------------------------------------------------------+
```

### Direct VD streaming (no phone relay)

Earlier releases routed video through the phone app: VD server wrote frames to localhost, the phone forwarded them to the car over WiFi. That was four socket operations and two process context switches per frame. The current architecture binds `9638`, `9639`, and `9640` on the VD server itself (`0.0.0.0`); the car connects to the phone's IP on those ports directly. Two socket ops, zero context switches. The phone's `VirtualDisplayClient` was simplified to lifecycle-only — it accepts the VD server's reverse connection on `localhost:19647`, reads `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY`, and sends `CMD_STOP` on teardown. Video, audio, and touch no longer touch the phone app.

### Audio Pipeline Architecture

Audio mirroring completes an end-to-end loop operating on dedicated port `9640`:
- **Capture**: `AudioRecord` initialized with `MediaRecorder.AudioSource.REMOTE_SUBMIX` captures system audio rendered into the VirtualDisplay.
- **Encoding**: Handled by `AudioPipeline` and `AudioEncoderSelector` configuring `MediaCodec` for AAC-LC (48000 Hz, stereo, 128 kbps). The AAC-LC `csd-0` (AudioSpecificConfig) is sent out-of-band ahead of stream data (`AudioPayload.kt`).
- **Pacing & Synthetic Silence**: 48kHz AAC-LC produces 1024 samples per frame = 21.333ms per frame = 46.875 fps (`AudioSpec.kt`, `AudioPacing.kt`). When the capture stream is silent or starves, the pipeline steadily injects synthetic silence frames at a precise 46.875 fps cadence. This prevents downstream jitter buffer underflow and queuing latency spikes.
- **Transmission**: `SocketAudioSink` and `PortAcceptor` bind port 9640 (accepting only the pinned `CAR_HOST`) using `AsyncSocketSender`.
- **Receiver & Playback**: `CarSessionCoordinator` initiates the port 9640 connection upon `VD_PORTS_BOUND` (when negotiated via `isAudioNegotiated`). `AudioPlayer` manages AAC-LC decoding, a 150ms jitter buffer (configurable 60~200ms), and playback through `AudioTrack`. `AudioPlayer` periodically logs silence frame metrics (`silenceFrames`/`totalFrames`), closing the S23 contract against false-green verification.
- **Negotiation**: Two-way handshake negotiation via `FEATURE_AUDIO` in `HandshakeRequest` and `HandshakeResponse`. Client settings provide an audio switch persisted in preferences; car receiver dims audio toggle if phone does not support or report `FEATURE_AUDIO`.

### Why shell UID for the VD server

`app_process` must run as shell UID (2000) to create VirtualDisplays that can host third-party apps and to reflect into `InputManager` for touch injection. The phone deploys `vd-server.jar` to `/sdcard/DiLinkAuto/` and starts it via `CLASSPATH=jar app_process / com.dilinkauto.vdserver.PipelineServer W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]` (the argv tail is built by `VdDeployArgs.format`; the trailing `CAR_HOST` peer-pin slot is optional and `-` means accept any peer). The argv tail, the kill/launch command lines (`VdDeploy.buildDeployPlan`) and the kill → wait-for-exit → launch orchestration (`vdRunDeploySequence`) are single-sourced in `:protocol-core` and shared by every deploy site: phone Shizuku, car USB ADB, car TCP ADB, and the desktop's `adb.exe` path.

## Trust Boundaries (Security Posture)

The full model — including the Shizuku threat surface, keystore handling and the car's ADB key storage — lives in `docs/setup.md` ("Security Model (Trust Boundaries)"). What matters for this architecture:

- **The trust boundary is the wire, and it is currently cleartext.** All four ports are plain TCP with no pairing secret and no integrity check, so the product assumption is "trusted local network / private hotspot". A MITM on the same LAN can watch the mirrored screen and inject input. Pairing/nonce is a tracked follow-up, not a shipped mitigation.
- **The shell-UID engine is the crown jewel.** Anything that can drive `9638`/`9639` can `am start`, `pm uninstall`, inject touch and power the phone's physical panel off — as shell UID. The 2026-10-09 hardening pass (`a7d2b72` wire protocol, `8601fb1` VD server, `1f6836a` desktop, `5eae3e7` deploy quoting) narrowed the surface without changing the trust assumption:
  - the engine still binds `0.0.0.0`, but **refuses accepts from any IP other than the receiver it was launched for** (`CAR_HOST` argv slot, populated by the phone's deployer from the live control connection's remote address; `-` = legacy accept-any);
  - every package/component name reaching `am start` / `pm uninstall` is shape-validated (`requirePackageName` / `requireComponentName`) and shell-quoted (`VdDeploy.shellQuote`) — peer strings never become shell syntax;
  - the desktop whitelists the phone's peer-supplied deploy input (`adbPort` must be `5555`, `vdServerJarPath` must sit under `/sdcard/DiLinkAuto/`, VD dims clamped) before it ever builds an `adb` command;
  - decoding is fail-closed: malformed frames throw `ProtocolDecodeException` at the decoder, payloads are capped at 16 MB, and the reader *disconnects* instead of crashing the process;
  - the lifecycle listener is loopback-only and the VD server's failure paths no longer leak a zombie shell-UID process holding the VirtualDisplay and both binds.
- **Shizuku is deliberately unhardened.** It grants shell-equivalent privileges to the phone app by design; anything that can bind Shizuku can direct shell commands. Pair it only with apps you trust.

## Module Responsibilities

### protocol-core (Pure JVM Library)

Everything in the protocol that does not touch Android. Three platforms share it: the car app (`app-server`), the Windows receiver (`app-desktop`) and — through `:protocol`'s `api(project(":protocol-core"))` — the phone app and the VD server. Keeping it Android-free is what makes `app-desktop` possible at all, and it is the reason the pure logic (framing, sequencing, deploy command lines, black-screen policy) can be unit-tested without a device.

| Component | File | Purpose |
|-----------|------|---------|
| Ports | `Ports.kt` | The single definition of the wire ports — `DEFAULT_PORT = 9637` (control + data), `VIDEO_PORT = 9638`, `INPUT_PORT = 9639`, `AUDIO_PORT = 9640`, `LIFECYCLE_PORT = 19647`, `ADB_PORT = 5555`. `Discovery` only forwards deprecated aliases to it |
| Connection | `Connection.kt` | TCP connection with heartbeat/watchdog (3s interval / 10s timeout, injectable for tests), bounded write queue (64 frames, suspends on backpressure) with `sendDispatcher` = `Dispatchers.IO.limitedParallelism(1)` + `sendMutex` → strict send ordering, inline INPUT/CONTROL dispatch so touch and commands keep their order, async dispatch only for DATA. Graceful-EOF flush grace (500ms) before the writer is cancelled |
| NioWriter | `NioWriter.kt` | SocketChannel spin-blocking write with configurable timeout and partial-write progress tracking |
| NioReader | `NioReader.kt` | Selector-based non-blocking reader with grow-on-demand buffer (hard ceiling `MAX_READ_UNIT`), `maybeShrink` back to the default capacity after a large frame, mid-frame stall deadline (a silent half-open peer), and explicit EOF/close handling |
| FrameCodec | `FrameCodec.kt` | Binary framing (`HEADER_SIZE = 6`, `MAX_PAYLOAD_SIZE` = 16MB), `ThreadLocal` header buffer for the hot path, shared `validatePayloadSize` so every read path rejects oversize frames the same way |
| AudioPayload | `AudioPayload.kt` | Framing and serialization for AAC-LC audio CONFIG (`csd-0`) and audio stream frames |
| BackoffPolicy | `BackoffPolicy.kt` | Parameterized exponential backoff calculation with jitter and upper bounds |
| DecodeLivenessTracker | `DecodeLivenessTracker.kt` | Unified decode liveness state tracking and baseline timestamp management across receivers |
| AppIconLimits | `AppIconLimits.kt` | Safe limits for app launcher icons: `MAX_ICON_DIM = 512`, `MAX_ICON_BYTES = 1048576` |
| Ipv4Validator | `Ipv4Validator.kt` | Strict and loose IPv4 address validation routines |
| Messages | `Messages.kt` | Serializable data classes (handshake, app list, touch). Every decoder validates its bounds (`require`) and throws `ProtocolDecodeException`; `requirePackageName` / `requireComponentName` reject unsafe names at the decode boundary |
| ProtocolConstants | `ProtocolConstants.kt` | `PROTOCOL_VERSION = 1` + `requireSupportedProtocolVersion` (peers announcing a different version are rejected instead of decoded at wrong offsets), display modes, feature flags, connection methods |
| Channels / message types | `Channel.kt`, `MessageType.kt` | Channel IDs (control, video, audio, data, input) — each on a dedicated TCP connection — and the per-channel byte constants. `VD_PORTS_BOUND` (0x31) signals the receiver to connect video/input directly to the VD server; `LOG_TOGGLE` toggles car-side logging; `SET_DISPLAY_POWER` (0x32) carries the manual physical-panel switch over the input channel |
| VdDeploy | `VdDeploy.kt` | `app_process` kill/stop/launch command builders: `gracefulStopCommand` (停止哨兵), `probeCommand` / `probeExitCodeCommand` (liveness built on `pgrep -f` — toybox 0.8.11-android rejects `pkill -0`), and `killCommand` / `killCommandForce` (**`pgrep` + `kill <pid>`**, not `pkill -f`: the bracket pattern is a regex trick that only `pgrep` honors). Plus `shellQuote` (POSIX single-quote) for peer-supplied paths and `buildDeployPlan` assembling the argv tail + launch line |
| VdDeployArgs | `VdDeployArgs.kt` | `app_process` argv tail shared by every deploy path: `W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST [ALLOWED]]`, aspect-preserving scale to the 1920x1080 encode cap, DPI override range `120..480` + `coerceDpiOverride`, `sanitizeCarHost` for the peer-pin slot, `formatAllowedPackages`/`parseAllowedPackages` for the car-visible package allowlist (issue #21) |
| VdDeploySequence | `VdDeploySequence.kt` | The single definition of "stop → wait until it is really gone (two consecutive GONE probes; UNKNOWN accepted immediately; force-kill on timeout)", split into two entry points over one implementation: `vdRunDeploySequence` (stop → wait → **launch**) for the four deploy sites, and `vdStopEngine` (stop → wait → `killCommandForce` fallback, **no launch**) for teardown paths — the desktop receiver's session close is the first caller. `VdDeployExecutor` abstracts one shell channel; clock and sleeper are injectable |
| BlackScreenDetector | `BlackScreenDetector.kt` | Sustained-black-screen policy shared by the car decoder and the desktop pipeline: a run of tiny I-frames means the encoder produces nothing. Alerts after N frames, escalates only after a sustained window (a short burst during warm-up is normal) — 3s by default, the desktop widens it via `setSustainWindow(10s)`. Clock is injected |
| ScreenMode / CaptureModePolicy | `ScreenMode.kt` | VD-first capture strategy with the documented degradation ladder `VIRTUAL_DISPLAY → PROJECTION_SCREEN_OFF → PROJECTION_MAIN → (none)`; pure functions, no platform dependency (see `docs/FEATURE_DUAL_MODE_RECEIVER.md`) |
| H264NalParser | `H264NalParser.kt` | NAL walker — `isKeyFrame` (type 5) drives "drop P-frames until the first IDR" |
| RecentsParser | `RecentsParser.kt` | `dumpsys activity recents` parsing + quick-switch target selection behind the GO_RECENT command: live tasks only (`hasTask=true`), component extraction gated by the same shell-injection whitelist (`COMPONENT_REGEX`), target = most recent task that is not the current VD-top / the control app / launcher / systemui (2026-10-10) |
| VideoConfig | `VideoConfig.kt` | `TARGET_FPS = 20`, `DEFAULT_BITRATE` (4Mbps) plus the layered bitrate bounds (UI / deploy / adaptive floor 1.5Mbps), `calculateOptimalDpi(width, height, reportedDpi)` |
| VdDimensions | `VdDimensions.kt` | Pure viewport math: car viewport (even-aligned) + anti-crop scale for Chinese-ROM IME hardcoding + DPI override vs. auto-calibrate. Feeds both the VD size and the handshake response's `vdWidth`/`vdHeight` |
| PlatformLog / ThreadPriority | `PlatformLog.kt`, `ThreadPriority.kt` | Platform hooks so the shared code can log and set thread priority without importing `android.util.Log` |

### protocol (Android Library, minSdk 26)

Shared by the three Android modules (`app-client`, `app-server`, `vd-server`). Zero UI dependencies. This module is now **Android-only glue**: it re-exports `:protocol-core` through `api(project(":protocol-core"))`, so the wire format, framing, constants and deploy command lines all live in `protocol-core` (they were moved there so the desktop can share them). What is left here is the piece that genuinely needs the Android framework: the ADB clients (USB + TCP), mDNS discovery, the platform hooks installation and the car-side notifier.

| Component | File | Purpose |
|-----------|------|---------|
| AndroidPlatformHooks | `AndroidPlatformHooks.kt` | Installs the `:protocol-core` `PlatformLog` / `ThreadPriority` implementations (`android.util.Log`, `Process.setThreadPriority`) in each process that hosts shared code |
| Discovery | `Discovery.kt` | mDNS (`NsdManager`) service registration/discovery for `_dilinkauto._tcp`. Only responders whose service name and port match the protocol are offered as connection targets (an impostor on the same LAN used to show up as a phone). Port constants are deprecated aliases of `Ports` |
| ForegroundNotifier | `ForegroundNotifier.kt` | `NotificationCompat` foreground-service notification, routed through the app's own `core-ktx` so the module itself stays `compileOnly` on androidx |
| DeviceInfo | `DeviceInfo.kt` | The shared "Device Info" diagnostics block used by the phone's startup log and the car's crash report (memory section opt-in), so the two copies cannot drift |
| ConnectionFactory | `ConnectionFactory.kt` | Centralized TCP socket channel creation and setup |
| ConnectionKeepAlive | `ConnectionKeepAlive.kt` | TCP keepalive configuration helper |
| UsbAdbKeyStore | `adb/UsbAdbKeyStore.kt` | Android Keystore wrapper for ADB authentication keys |
| UsbAdbConnection | `adb/UsbAdbConnection.java` | ADB protocol over USB (CNXN, AUTH, OPEN, WRTE), `logSink` callback; dead sync/push code removed |
| AdbProtocol | `adb/AdbProtocol.java` | ADB message constants and serialization (validated header parse, CRC check) |
| AdbCrypto | `adb/AdbCrypto.java` | Prehashed `AUTH_SIGNATURE` (`NONEwithRSA` + SHA-1 DigestInfo prefix), ANDROID_PUBKEY base64 encoding — pure JVM, covered by unit tests |
| TcpAdbConnection | `adb/TcpAdbConnection.kt` | Persistent ADB over TCP — single socket reused for all shell commands, payload negotiation and CRC-checked reads. Used by the car-side `RemoteAdbController`. (`dadb` is still used by the phone-side `CarAppInstaller` for the car APK install.) |

### app-client (Phone Application, minSdk 29)

Manages handshake, VD lifecycle, car-app install, allowlist filtering, audio feature negotiation, and `FileLog`.

| Component | File | Purpose |
|-----------|------|---------|
| ConnectionService | `service/ConnectionService.kt` | Lean orchestrator: accepts 9637, negotiates features (`FEATURE_AUDIO`), manages lifecycle ServerSocket on `127.0.0.1:19647`, and delegates deployment to `ShizukuVdDeployer`, install to `CarAppInstallHost`, network tracking to `ClientNetworkMonitor`, and allowed apps to `AllowlistRepository`. Session generations and `cleanupGuard` prevent redundant teardown |
| ShizukuVdDeployer | `service/ShizukuVdDeployer.kt` | Deploys the VD server via Shizuku shell when available, executing `vdRunDeploySequence` |
| ClientNetworkMonitor | `service/ClientNetworkMonitor.kt` | Monitors network connectivity changes and manages IP-change events without terminating active sessions prematurely |
| CarAppInstallHost | `service/CarAppInstallHost.kt` | Encapsulates the car APK installation workflow and state orchestration |
| AllowlistRepository | `service/AllowlistRepository.kt` | Persistence and filtering for allowed launcher apps |
| VirtualDisplayClient | `display/VirtualDisplayClient.kt` | Lifecycle-only. `startListening()` opens the ServerSocket synchronously on **loopback** and refuses non-loopback peers; `acceptConnection()` waits for the VD server's reverse connection on `127.0.0.1:19647`, reads `MSG_DISPLAY_READY` (displayId + direct-injection flag), relays `MSG_STACK_EMPTY`, sends `CMD_STOP` |
| VdDimensions | `protocol-core/.../VdDimensions.kt` (shared) | Pure viewport math (2026-10-09 moved out of app-client): car viewport (even-aligned) + anti-crop scale for Chinese-ROM IME hardcoding + DPI override vs. auto-calibrate. Caller must pass the phone's REAL physical size (`maximumWindowMetrics`/`getRealMetrics`), not compat-scaled `displayMetrics` |
| AssetDeployer / CarInstallCoordinator | `service/AssetDeployer.kt`, `service/CarInstallCoordinator.kt`, `service/CarInstaller.kt` | Extracts `vd-server.jar` from assets behind an `AssetSource` seam with a CRC freshness gate, and runs the car-APK install state machine through an injectable `CarInstaller` transport. Both exist so the pure logic is unit-testable — behaviour is unchanged |
| AppListBuilder | `service/AppListBuilder.kt` | Builds the car-visible app list, filtered by the user's allowlist. Icon PNGs sent once per package per session (hash-suppressed) |
| AllowlistScreen | `AllowlistScreen.kt` | Phone-side picker for which launcher apps reach the car. Toggling a row fires `ACTION_ALLOWLIST_UPDATED` so the running service re-sends the list live |
| CarAppInstaller | `service/CarAppInstaller.kt` | Installs the embedded `app-server.apk` onto the car via `dadb` over WiFi (15s connect timeout) |
| CarIpLocator | `service/CarIpLocator.kt` | Locates the car's ADB-over-WiFi service (port 5555): control-connection remote IP, subnet enumeration, ARP, neighbor cache, parallel /24 scan, gateway |
| PhoneDisplayRestorer | `service/PhoneDisplayRestorer.kt` | Restores the phone's physical display and IME after the VD server tears down. **Owns a process-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.IO)`** — deliberately not the Service's scope, which `onDestroy()` cancels. `NonCancellable` context ensures `cmd display power-reset` cannot be skipped mid-restore. `inFlight` `AtomicBoolean` collapses concurrent restore requests into one. Layered: Shizuku (graceful stop via `VdDeploy.gracefulStopCommand` + wait + `killCommandForce` fallback, then `cmd display power-reset` + IME restore), then `PowerManager.wakeUp`, then `FLAG_TURN_SCREEN_ON`, then a wake lock |
| FileLog | `FileLog.kt` | File-based logging to `/sdcard/DiLinkAuto/client.log`, rotation (30 files max), bypasses HyperOS logcat filtering. Defaults to ON for debug/pre-release, OFF for release; user choice persists via `AppPrefs.LOG_ENABLED`. The settings switch reads the *effective* state through `isEnabledIn(prefs)` (issue #72), so a release build shows OFF instead of a misleading ON. Writes drain through a bounded `AsyncLogQueue` and peer-supplied `CAR_LOG` lines are length-capped before they reach it |
| MainActivity | `MainActivity.kt` | UI — start/stop, onboarding, settings, allowlist, install-on-car |

### app-server (Car Application, minSdk 26)

Parallel connection model: WiFi control (9637) + WiFi direct video/input/audio (9638/9639/9640), with USB ADB and TCP ADB tracks for VD-server deployment.

| Component | File | Purpose |
|-----------|------|---------|
| CarConnectionService | `service/CarConnectionService.kt` | Lean foreground Service coordinator (slimmed down to 407 lines). Delegates connection orchestration to `CarSessionCoordinator`, ADB tracking to `CarAdbTracker`, and app list parsing to `CarAppListReceiver` |
| CarSessionCoordinator | `service/CarSessionCoordinator.kt` | Multi-channel lifecycle coordinator across 9637 (control), 9638 (video), 9639 (input), and 9640 (audio); handles handshake, feature negotiation (`FEATURE_AUDIO`), and mid-stream rotation re-handshake |
| CarAdbTracker | `service/CarAdbTracker.kt` | Manages USB host ADB and TCP ADB dual tracks, reconnect limits (`noAdbCount >= 3`), and engine deploy triggers |
| CarAppListReceiver | `service/CarAppListReceiver.kt` | Receives app lists from phone, applying `AppIconLimits` to reject malformed or oversized payloads |
| AudioPlayer | `audio/AudioPlayer.kt` | MediaCodec AAC-LC audio decoder with a 150ms jitter buffer (60~200ms configurable) and AudioTrack playback; periodically logs silence frame statistics (`silenceFrames`/`totalFrames`) |
| VideoDecoder | `decoder/VideoDecoder.kt` | H.264 hardware decode via `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` (REGULAR_CODECS lists hardware first; selects `OMX.qcom.video.decoder.avc` on the BYD). Uses `DecodeLivenessTracker` and `BackoffPolicy`; 4-frame `ArrayBlockingQueue`, post-flush IDR resync (skips P-frames until a keyframe arrives), `outputSurfaceValid` gate prevents rendering to a destroyed surface, `debugFrameStats` per-30-frame decode-time log. Feed thread at `THREAD_PRIORITY_URGENT_DISPLAY`. Sustained-black-screen is judged by `:protocol-core`'s `BlackScreenDetector` (3s window, `SystemClock.elapsedRealtime` injected), shared with the desktop |
| AppIconCache | `AppIconCache.kt` | Car-side icon cache. `prepareAll` decodes/resizes all icons to grid size in parallel (`Semaphore(4)`), intermediates recycled. `getPrepared` is O(1) `ConcurrentHashMap` lookup. `clear()` on disconnect frees ~5-9MB heap + eMMC PNGs |
| CarShell | `ui/screen/CarShell.kt` | Streaming layout (mirror + nav bar) or launch screen. Accepts `CONNECTING` state during mid-stream rotation re-handshake so the video-wait overlay covers the redeploy gap instead of flashing CarLaunchScreen |
| MirrorScreen | `ui/screen/MirrorScreen.kt` | `SurfaceView` (not `TextureView`) — bypasses Adreno 505 per-frame GL composite. `surfaceCreated` calls `switchSurface` to re-attach the decoder with zero keyframe loss across HOME<->APP navigation; `surfaceDestroyed` gates the render flag off without stopping the decoder |
| CarLaunchScreen | `ui/screen/CarLaunchScreen.kt` | Full-screen launch/connection screen, branding, manual IP, dev-mode toggle, startup-DPI numeric input |
| HomeScreen | `ui/screen/HomeScreen.kt` | App grid (64dp icons, long-press Pin-to-Top / Unpin / uninstall / app info). `@Immutable AppTileData` wrapper excludes the unstable `ByteArray` icon so the grid skips recomposition on `appList` reassignment |
| PersistentNavBar | `ui/nav/PersistentNavBar.kt` | Three buttons only: Eject / Home / Back. Landscape: left rail. Portrait: bottom bar. Width computed to guarantee an even viewport for the H.264 encoder |
| NavBarComponents | `ui/nav/NavBarComponents.kt` | `NavActionButton` — 40dp icon, `onSurfaceVariant` tint |
| HandshakeFactory | `service/HandshakeFactory.kt` | Builds `HandshakeRequest` for both initial connect and mid-stream rotation re-handshake |
| VdServerDeployer | `service/VdServerDeployer.kt` | Deploys the VD server onto the phone from the car via USB ADB or TCP ADB (`deploy` / `deployDirect`). `shellBackground` keeps the ADB stream open so `app_process` is not killed by shell exit. Encode dims clamped to 1920x1080 (Snapdragon 439 VPU cap). Owns the retry policy and one `VdDeployExecutor` per transport; the kill → wait → force-kill → launch sequence itself is `:protocol-core`'s `vdRunDeploySequence` |
| CarLogWriter | `service/CarLogWriter.kt` | Car -> phone log relay. `ConcurrentLinkedQueue` buffer with `AtomicInteger` counter (O(1) cap check — was O(n) `size()`), 10k line cap when control connection is down |
| CarTouchSender | `service/CarTouchSender.kt` | Encodes and sends touch events on the input connection (9639). Single-thread executor serializes sends |
| RemoteAdbController | `adb/RemoteAdbController.kt` | Persistent TCP ADB connection to the phone via `TcpAdbConnection` |
| CarCrashHandler | `CarCrashHandler.kt` | Uncaught exception handler. Saves a crash report to `filesDir/crash-pending.log`; flushed to the phone via `carLogSend` on the next successful connection |
| CarTheme | `ui/theme/CarTheme.kt` | Dark color scheme, tokenized colors (`onSurfaceVariant` replaces `Color.Gray` / `0xFF888888`), labels floored to 14sp |
| MainActivity | `MainActivity.kt` | Fullscreen immersive, USB intent forwarding, rotation re-handshake (`onCarViewportChanged`) |

### app-desktop (Windows Receiver, JDK 17)

A second receiver for the same protocol, written in Kotlin/JVM with Compose Desktop 1.6.2 (`jpackage` app-image via `:app-desktop:createDistributable`, bundling its own trimmed JRE). It connects directly to the phone's 9637/9638/9639, decodes with FFmpeg (JavaCV, Windows x86_64) and paints into a Swing canvas embedded through `SwingPanel`. Config and logs live in `%APPDATA%\DiLinkAuto` (override with `DILINK_DESKTOP_HOME`). The `adb.exe` deployment path is gated on the `dev_mode` setting and was **verified end to end on a real MI 9 on 2026-10-10** (handshake → deploy → `VD_PORTS_BOUND` → 24 fps decode); with Shizuku the phone deploys the engine itself and the desktop only consumes streams.

| Component | File | Purpose |
|-----------|------|---------|
| DesktopMain | `DesktopMain.kt` | Entry point: config/log wiring, `--probe` mode (link verification without decode/render), window mode. `PlatformLog.sink` forwards WARN+ only — `NioReader`'s per-poll DEBUG logging would drown the session log |
| DesktopApp | `DesktopApp.kt` | Composition root and session-generation owner. All lifecycle work is serialized on one thread; `stop()` is awaitable (the caller is about to `exitProcess`). Session-end signals are identity-guarded so "apply & reconnect" cannot close the window; sustained black screen triggers at most 2 automatic reconnects per process, shared with the decode-stall escalation. `closeSession()` order is load-bearing: `service.stop()` → `pipeline.stop()` → **`runBlocking { adbDeployer.gracefulStop() }`** → `adbDeployer.close()` → `scope.cancel()` → `keepAwake.disable()` |
| DesktopConnectionService | `DesktopConnectionService.kt` | Session state machine `IDLE → CONNECTING → HANDSHAKING → WAITING_VD → STREAMING` (`DISCONNECTED` on exit); opens control+data, handshake, waits for `VD_PORTS_BOUND` (with timeout — the phone never reports a deploy failure), then connects video/input directly to the VD server |
| HandshakeFactory | `HandshakeFactory.kt` | Builds the `HandshakeRequest` from the desktop config; owns the sender-side even-alignment (`DimAlign.evenMin2`) |
| VideoDecodePipeline | `video/VideoDecodePipeline.kt` | `FFmpegFrameGrabber` in raw Annex B mode (`maximumSize = 0` disables seek emulation, which hangs forever on a live stream), CONFIG replay + wait-for-IDR on rebuild, D3D11VA hardware decode with a "started but produced nothing" watchdog → permanent fallback to software, integrated with `DecodeLivenessTracker` and `BackoffPolicy`, sustained-black-screen detection (10s window, wider than the car's) and decode-stall escalation |
| FeedGate / VideoStreamPipe | `video/FeedGate.kt`, `video/VideoStreamPipe.kt` | Gate drops frames until SPS/PPS and then until the first IDR; the pipe is a bounded queue that drops the oldest chunk under backpressure without evicting the CONFIG it just received |
| SwingVideoView | `ui/SwingVideoView.kt` | Letterbox painter + mouse→normalized-touch mapping. Copies every delivered frame into its own buffer: JavaCV reuses one `BufferedImage` for all frames, so handing the reference to an asynchronous repaint would tear |
| DesktopWindow | `ui/DesktopWindow.kt` | Window shell: nav rail (mirror / apps / display) + main view. The video `SwingPanel` is composed **only** on the mirror page — AWT components always draw above Compose content |
| AppCatalog / AppIconStore | `apps/*` | Turns the phone's `APP_LIST` / `APP_UNINSTALLED` frames into a `StateFlow<List<AppEntry>>`, enforcing `AppIconLimits`; PNG decode happens on the IO dispatch coroutine, never on the UI thread |
| InputSender | `input/InputSender.kt` | Touch / nav commands on the input connection, plus the manual physical-panel switch |
| AdbDeployer / AdbDeploy / ProcessAdbRunner | `deploy/*` | No-Shizuku path, gated on the `dev_mode` setting: `adb.exe connect` → shared `vdRunDeploySequence` → foreground `exec app_process`. The phone's peer-supplied `adbPort` and `vdServerJarPath` are whitelisted before anything is built. Held `adb shell` processes are the engine's liveness anchor — which is why the teardown goes `gracefulStop()` (shared `vdStopEngine`, 6s budget, remembers `lastSerial`) **before** `close()`; a JVM shutdown hook is the last-resort reaper |
| DesktopSettings / JsonConfig / DesktopSettingsStore | `config/*` | `config.json` (defaults, paths and atomic tmp+move writes in `DesktopSettingsStore`) with the same keys as the Android `SharedPreferences` (`AppPrefs`), plus `startup_hwaccel` / `keep_awake`; hand-written JSON is tolerated (type mismatch → default, DPI coerced into the protocol range) |
| KeepAwake | `display/KeepAwake.kt` | `kernel32!SetThreadExecutionState` through JNA on a dedicated thread; degrades to a no-op off Windows |
| SessionStatsLogger / FrameDumper | `SessionStatsLogger.kt`, `video/FrameDumper.kt` | Per-second session statistics line (fps, rebuilds, hw/soft decode) and a debugging hook that dumps frame N to a PNG when `DILINK_DUMP_FRAME` is set (zero behaviour change otherwise) |

### vd-server (Shell-Privileged Process, minSdk 29)

Android library module (`com.android.library`), compiled via `bundleLibRuntimeToJarDebug` then D8 into a JAR by the `buildVdServer` task in `app-client/build.gradle.kts`. Depends on `:protocol` and `kotlinx-coroutines-core`. Deployed by the phone to `/sdcard/DiLinkAuto/vd-server.jar`. Runs as shell via `app_process`.

| Component | File | Purpose |
|-----------|------|---------|
| PipelineServer | `PipelineServer.kt` | Process entry point (`main` parses `W H DPI PHONE_HOST EW EH FPS BITRATE [CAR_HOST]`) and lifecycle owner. Creates AVC encoder via `VideoEncoderSelector`. CBR `VideoConfig.DEFAULT_BITRATE` = 4Mbps Main profile, I-frame interval 1s. Binds `9638` (video), `9639` (input), and `9640` (audio) on `0.0.0.0` via `PortAcceptor`, accepting **only** the receiver named by the `CAR_HOST` argv slot. Manages lifecycle with `ProcessWatchdog` and coordinates `AudioPipeline` when negotiated |
| AudioPipeline | `audio/AudioPipeline.kt` | Audio capture (`REMOTE_SUBMIX`) and AAC-LC encoding pipeline with continuous synthetic silence pacing (46.875 fps, 1024 samples/frame) |
| AudioEncoderSelector | `audio/AudioEncoderSelector.kt` | MediaCodec AAC encoder selection with candidate ordering |
| AudioSpec / AudioPacing | `audio/AudioSpec.kt`, `audio/AudioPacing.kt` | Audio constants: 48kHz, 2-channel stereo, 128kbps, 21.333ms frame pacing (46.875 fps) |
| SocketAudioSink | `audio/SocketAudioSink.kt` | Pushes AAC-LC config and stream frames to port 9640 using `AsyncSocketSender` |
| PortAcceptor | `PortAcceptor.kt` | Non-blocking multi-port listener (`9638`, `9639`, `9640`) enforcing `CAR_HOST` peer pinning |
| AsyncSocketSender | `AsyncSocketSender.kt` | Unified non-blocking asynchronous socket writer template |
| ProcessWatchdog | `ProcessWatchdog.kt` | Decoupled watchdog monitoring pipeline liveness and stop-request sentinels |
| VideoEncoderSelector | `VideoEncoderSelector.kt` | AVC/H.264 candidate ordering (Codec2 `c2.` ahead of legacy `OMX.`, non-software first) |
| PersistentShell | `PersistentShell.kt` | The long-lived `sh` every command goes through, with drained stdout/stderr. Started before the encoder is set up, destroyed after the panel/IME restore — the teardown order depends on it. `exec` is fire-and-forget (it returns once stdin is written), so `awaitIdle()` is the only way to know the shell has caught up: it writes `echo <marker>` and waits on `ShellSyncPoint` for the echo to come back through the drain thread — dual FIFOs turn that into a full pipeline barrier. `cleanup()` calls it before `close()`, otherwise the last restore commands die with the SIGTERM |
| CarCommandRouter | `CarCommandRouter.kt` | Everything the car can ask for over `Channel.CONTROL` (launch / home / back / recent / app info / uninstall / physical-panel power) plus the display queries lifecycle needs. All shell access is injected, so it owns no thread or socket state of its own; every interpolation is shell-quoted |
| LifecycleWriter | `LifecycleWriter.kt` | Single-writer for lifecycle responses (`MSG_STACK_EMPTY`) built on top of `AsyncSocketSender` |
| GlPipeline | `GlPipeline.kt` | EGL14 + GLES20 render loop on the pipeline thread. Single-threaded: `parkNanos` pace -> `updateTexImage` -> fullscreen quad -> `eglSwapBuffers` -> encoder drain -> TCP write. Natural flow control: a TCP stall blocks the next swap, slowing encoder input. The frame-available listener is registered in `initEglAndSurfaceTexture()` — **before** the VirtualDisplay is created — and runs on its own `PipeCB` HandlerThread; registering it in `pipelineLoop()` (i.e. only once the car connects) lost every frame in between and produced a probabilistically all-black stream (~50% on the MI 9, 2026-10-10). The loop's periodic log line carries `tex=`/`cb=`/`swapFails=` for exactly this diagnosis. Adaptive bitrate (`AdaptiveBitrate.kt`): floor 1.5Mbps, 2s clean recovery window, 0.5Mbps steps (down-shifts at >15ms write time) |
| TouchInjector | `TouchInjector.kt` | `InputManager` reflection (`injectInputEvent`), `MotionEvent.setDisplayId` reflection, multi-touch state with a bounded pointer table (`PointerCap.kt`: evict + truncate + id-range check). Falls back to `input -d` shell taps when reflection is unavailable |
| DisplayPowerController | `DisplayPowerController.kt` | `DisplayControl.setDisplayPowerMode` reflection (loaded from `services.jar` via `DelegateLastClassLoader`); shell fallback `cmd display power-reset` (on) / `cmd display power-off` (off), API 29+. There is no `power-on` subcommand — `cmd display help` on Android 15 lists only `power-off` and `power-reset` ("Turn the DISPLAY_ID power to a state the display supposed to have"); the old `power-on` writing failed as "Unknown command" and left a forced-off panel dark. On API 26-28 the fallback is a no-op — a `DisplayControl` reflection failure means the physical panel is not restored (now logged, was silent). Persists and restores the original IME and the screen-timeout sentinel (`SessionScreenTimeout.kt`), gated by `ImeRestore.shouldRestoreIme` — excludes the linkpc IME |
| VirtualDisplayCreator | `VirtualDisplayCreator.kt` | Shell-UID VirtualDisplay via `DisplayManagerGlobal` reflection (trust flag `0x6c49`, `OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`), `DisplayManager` fallback with `mDisplayIdToMirror` forced to 0. Applies the Android 12L+ letterbox style so portrait apps render at a sensible aspect ratio, and re-checks that the DTA is still attached to the display tree (`ensureDtaAttached`). `create()` only creates the VD — `configureEnvironment()` (which applies letterbox style + disables screen-off/wake gestures) must be called separately AFTER the caller has snapshotted the original settings via `DisplayPowerController.saveCurrentIme()` |
| VdDisplayHealer | `VdDisplayHealer.kt` | Diagnostic and self-healing checker for DTA (DisplayTaskAssociation) attachment on the virtual display tree |
| ActivityDumpParser | `ActivityDumpParser.kt` | Bounds-checked parser for `dumpsys window` and `dumpsys display` outputs, eliminating cross-display leakage |
| FakeContext | `FakeContext.kt` | Spoofs `com.android.shell` for DisplayManager access. Uses `ActivityThread.getSystemContext()` for a real system Context |
| PipeLog | `PipeLog.kt` | `println`-based logging (no `android.util.Log` in `app_process`); `ShellExec` runs commands against the persistent `sh` process, and `startOutputDrain(p, onLine)` feeds every drained line to `ShellSyncPoint` as well as the log |

**PipelineServer architecture.** Single pipeline thread processes each frame sequentially — `parkNanos` pace -> `updateTexImage()` -> GL render -> `eglSwapBuffers()` -> encoder drain -> TCP write. No queues between stages. Flow control is natural: if TCP stalls, the pipeline blocks, delaying the next swap. Uses `System.nanoTime()` + `LockSupport.parkNanos()` for drift-free 24fps timing. The pipeline thread is a **daemon** and the only thread allowed to touch EGL/GL: it signals readiness through a `CountDownLatch` in a `finally` (the main thread waits on it with a 10s timeout), parks in bounded slices so that `running=false` can actually stop it, and tears GL down in its own `finally`. Alongside it run the TouchReader and Lifecycle reader daemons, the LifeWriter daemon (background priority), the Watchdog daemon, the `PipeCB` frame-available HandlerThread, and a short-lived `StackCheck` thread per nav command. `cleanup()` only *requests* the pipeline thread's exit (`running=false` + unpark + a bounded 2s join) — it never destroys EGL from another thread.

**VD teardown (`cleanup()`).** Idempotent (guarded by `cleanedUp` `AtomicBoolean`). Order is deliberate:

1. **Global state first** — `cmd window reset-letterbox-style` + `wm user-rotation -d <id> free`. These are device-wide (not per-display), so a leaked value corrupts every later session. Doing them first means even a process killed midway has already returned the device to normal.
2. **Move foreground app** back to the physical display (`am display move-stack`).
3. **Restore panel + IME** — while `shellInput` is still usable. `setDisplayPower(true)` + `input keyevent 224` + `restoreIme()`.
4. **Drain the shell** — `shell.awaitIdle()`, so the restore writes are actually executed before the shell dies. `exec` returns on write, not on execution: without this barrier `shell.close()`'s SIGTERM cut off whatever was still in the pipe (实测丢的是 `screen_off_timeout` 恢复).
5. **Release resources** — LifeWriter thread interrupted, shell destroyed, GL/encoder/VD released.

The previous order had `virtualDisplay.release()` dead last with nothing after it to fail, so a mid-way exception or a SIGKILL from the phone side left the panel off and the VD alive — one leaked display per reconnect.

**Stop-request sentinel (2026-10-10, supersedes the two-stage stop).** The old `VdDeploy.stopCommand` (`pkill -f ...; sleep 1; pkill -9 -f ...`) assumed SIGTERM triggers the JVM shutdown hook — **false on ART**: `app_process` treats a bare SIGTERM as immediate termination, so `cleanup()` (panel power / letterbox / IME / `screen_off_timeout` restore, VD release) never ran on that path. MI 9 实测：`kill -TERM` → 进程 1 秒内消失、cleanup 零执行（`screen_off_timeout` 永远停在哨兵值）。Now the stop protocol is a **file sentinel**: `VdDeploy.gracefulStopCommand` writes `/sdcard/DiLinkAuto/stop-request` and returns immediately; the engine's watchdog polls it, **consumes** (deletes) it, sets `running=false` — every wait point (accept polling, park slices, bind retries) then exits within ~100ms, `run()` reaches its `finally`, and `cleanup()` runs to completion. The caller waits via the shared `vdAwaitExit` (12s graceful budget — the measured sentinel→exit latency is 6.1~6.3s, see below) and only then falls back to `killCommandForce`. The engine also deletes a stale sentinel at startup so a leftover request can never kill a fresh instance.

Four defects found on the same MI 9 pass and fixed alongside it:

- **`pkill -0` is not a liveness probe on toybox.** `toybox 0.8.11-android` rejects `-0` (`pkill: bad -L '0'`, rc=1 — its option table only takes `-SIGNAL|-l SIGNAL`), so `probeCommand` / `probeExitCodeCommand` returned **GONE unconditionally** and `vdAwaitExit` never actually waited; "wait for the old engine to exit" was a no-op in production forever. Both probes now use `pgrep -f` (same toybox source and option table as `pkill`, so any device where the force-kill path works has a working `pgrep`), with the `[P]ipelineServer` bracket pattern still keeping `-f` from matching the wrapper shell's own cmdline.
- **The tail of `cleanup()` was truncated by `destroy()`.** `ShellExec.execShell` is fire-and-forget (it returns once stdin is written); `cleanup()` wrote its last restore command and immediately called `shell.close()` → `input.close()` + `destroy()` (SIGTERM) — the `sh` died before running it. 实测：日志完整打印 `settings put system screen_off_timeout 60000` 与 `Cleanup complete`，但设备读回仍是 `2147483647`。 Fix: `ShellSyncPoint` — `arm()` mints a unique marker, `PersistentShell.awaitIdle()` writes `echo <token>` into the long-lived `sh` and blocks until the drain thread sees the marker echoed back; because stdin and stdout are both FIFOs, the echo proves every earlier command finished. `cleanup()` now calls `shell.awaitIdle()` between `lifecycle.stop()` and `shell.close()` (2s timeout, non-throwing, logged on expiry).
- **The desktop receiver killed the anchor before the engine could clean up.** On the ADB deploy path the local `adb shell` process **is** the engine's liveness anchor (it holds the `app_process` stdin/stdout); `AdbDeployer.close()` destroys it, the device-side shell stream drops and adbd reaps the engine — `cleanup()` never ran. `DesktopApp.closeSession()` now runs `runBlocking { adbDeployer.gracefulStop { … } }` (i.e. the shared `vdStopEngine`) **before** `close()`, and `STOP_TIMEOUT_MS` grew 5s → 20s to cover decode join + graceful wait + `-9` fallback + shell round-trips. `ProbeRunner` does the same in its `finally`.
- **Every budget was too tight, because the measured exit latency is 6.1–6.3s, not the assumed 2–4s.** With the teardown budget at 6s the wait missed by **130 ms on every single reconnect** — the probe log read `探针 ALIVE @+189ms` then `探针 GONE @+6123ms`, and an independent device-side poller (sentinel → pid gone ≈6.3s) agreed. So a `-9` was always fired just as `cleanup()` was finishing and truncated its tail. The old estimate only covered the shell commands *inside* `cleanup()`; it missed the pipeline thread's bounded join, encoder/VD release, and the wait on the phone-side `ConnectionService` teardown grabbing the same display/rotation locks. All three budgets are now 12s (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, `AdbDeployer.TEARDOWN_GRACEFUL_TIMEOUT_MS`, `PhoneDisplayRestorer.ENGINE_EXIT_WAIT_MS`) ≈ 2× measured. `AdbDeployer.gracefulStop` now also logs the elapsed time and every probe transition, which is what made this diagnosable.

**Force kill is `pgrep` + `kill <pid>`, not `pkill -9 -f` (2026-10-10, MI 9).** The `[P]ipelineServer` bracket trick is a *regex* trick: `pgrep` honors it (engine present → rc=0, absent → rc=1, no self-match), but `pkill -f` does **not** — it also kills the wrapper shell that is running it, because the pattern text sits in that shell's own cmdline (inline `adb shell 'pkill -9 -f "[P]ipelineServer"'` returns 137 before the next `echo`). The engine *does* get signaled (reproduced twice), but the exit code stops being evidence — unacceptable for a last-resort path. `VdDeploy.killCommandForce` is therefore `for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done 2>/dev/null; exit 0`, which keeps identification and killing on one matching rule.

## Connection Flow

```
1. Phone and car on the same network (or phone plugged into car USB)
2. Car app launches, starts parallel WiFi + USB/TCP ADB tracks

   Track A (WiFi control):
   a. Gateway IP discovery + mDNS lookup
   b. NIO connect to phone control port (9637)
   c. Handshake: car sends viewport + DPI + appVersionCode + targetFps + dpiOverride + bitrate
   d. Phone responds with device info + vdServerJarPath + connectionMethod + vdDpi
      + the phone-side VD dims (vdWidth/vdHeight) so a deployer without shell
      access to the phone can size the VirtualDisplay correctly
   e. Phone opens lifecycle ServerSocket on 127.0.0.1:19647 (loopback only)
   f. If Shizuku available: phone deploys VD server directly.
      Else the receiver deploys via ADB (car: USB or TCP; desktop: local adb.exe,
      gated on its dev_mode setting).
   g. VD server starts: CLASSPATH=jar app_process / PipelineServer W H DPI PHONE_HOST
      EW EH FPS BITRATE [CAR_HOST [ALLOWED]]   (PHONE_HOST is 127.0.0.1 on every path)
   h. VD server reverse-connects to phone localhost:19647 (NIO non-blocking)
   i. Phone accepts, reads MSG_DISPLAY_READY (displayId + direct-injection flag)
   j. Phone sends VD_PORTS_BOUND to car on the control connection
   k. Car connects video (9638) + input (9639) directly to the VD server in parallel
   l. VD server accepts both -- session fully established

   Track B (USB ADB / TCP ADB):
   a. USB: scan USB devices for ADB interface -> CNXN -> AUTH -> connected
      TCP: dev-mode phone-IP lookup, connect to phone:5555 via TcpAdbConnection
   b. shellBackground(launchCommand) -- keeps ADB stream open so app_process survives
   c. Launch phone app via am start (AppTargets.PHONE_MAIN_ACTIVITY)

3. VD server creates VirtualDisplay (car viewport + auto-calibrated DPI)
4. VD server powers off the phone's physical panel via DisplayControl
5. VD server saves the original IME and sets the VD's IME policy
6. Pipeline thread: EGL/GL init -> SurfaceTexture -> encoder -> bind 9638/9639
7. Car starts VideoDecoder on the SurfaceView surface when MirrorScreen creates it
8. Video streams: VD -> SurfaceTexture -> GL -> encoder -> TCP 9638 -> car VideoDecoder -> SurfaceView
9. Touch: car SurfaceView onTouch -> TouchEvent encode -> TCP 9639 -> VD TouchInjector -> InputManager
```

States: `IDLE -> CONNECTING -> CONNECTED -> STREAMING`

A mid-stream car-panel rotation reuses the control TCP connection. `MainActivity.onConfigurationChanged` calls `CarConnectionService.onCarViewportChanged`, which tears down video/input + the old VD server and re-sends a `HandshakeRequest` at the new dims. The phone deploys a fresh VD server and re-sends `VD_PORTS_BOUND`. The streaming-layout gate accepts `state == CONNECTING && appList.isNotEmpty()` so the video-wait overlay covers the ~2s redeploy gap instead of flashing `CarLaunchScreen` (which would destroy the SurfaceView and lose the decoder state).

A sustained black stream — no visible content for `BLACK_SCREEN_SUSTAIN_MS` (3s on the car, 10s on the desktop) of continuous tiny keyframes — triggers the same re-handshake path via `rehandshakeForBlackScreen()`: the phone tears down and redeploys the VD server, which is the recovery a wedged compositor needs.

## DiLink 4.0 Low-Spec Performance (Snapdragon 439)

The reference car is a low-spec DiLink 4.0 head unit: 8x Cortex-A53 (no big cores), Adreno 505, 4GB RAM, 1280x800, 2.4GHz-only WiFi, API 28, H.264 hardware decode capped at 1080p. The streaming pipeline was retuned for this hardware. The nine fixes:

1. **Encode dims capped to 1920x1080** (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`). Decoupled from VD dims via the `EW EH` args; the VD stays scaled-up to preserve the IME-crop fix (capped at 2560 px wide, issue #71), the encoder uses car-reported dims clamped to 1080p. Without this, >1080p phones trigger software decode on the 439 -> single-digit fps.
2. **Bitrate 8 Mbps -> 4 Mbps CBR** (`PipelineServer.BITRATE`, an alias of `VideoConfig.DEFAULT_BITRATE = 4_000_000`; the value is passed in as argv `BITRATE` and clamped to the deploy range). Adaptive fallback tightened: floor 1.5 Mbps, 2s recovery window, 0.5 Mbps steps (was 2 Mbps / 5s / 1 Mbps).
3. **Framerate 30 -> 20 fps** (`VideoConfig.TARGET_FPS = 20`). Per-frame budget 33ms -> 50ms, ~33% lower WiFi/GPU/allocation load. (Was 24 fps; lowered to 20 in issue #71 after a 2.5h navigation session kept the phone hot.)
4. **MirrorScreen TextureView -> SurfaceView** (`MirrorScreen.kt`). Bypasses the Adreno 505 per-frame GL composite (~2-5ms/frame at 1280x800). The `outputSurfaceValid` gate prevents rendering to a destroyed surface across navigation hide/show; `switchSurface` re-attaches the decoder with zero keyframe loss.
5. **Thread priorities.** `THREAD_PRIORITY_URGENT_DISPLAY` on the encode pipeline, the decode feed thread, and the socket-drain reader; `THREAD_PRIORITY_BACKGROUND` on the LifeWriter. A53 has no big cores, so the scheduler must favor the hot path.
6. **`carLogEnabled` defaults to `BuildConfig.DEBUG`** (`CarConnectionService` re-applies it per session through `CarLogWriter.setEnabled`); `ConcurrentLinkedQueue.size()` (O(n)) replaced by `AtomicInteger` (`CarLogWriter.bufferCount`).
7. **`AppIconCache.clear()` on disconnect** frees ~5-9MB heap + eMMC PNGs; `prepareAll` parallelized with `Semaphore(4)`, intermediate bitmaps recycled.
8. **`Debug.getPss()` removed from startup** (deprecated binder call, 100-500ms on A53).
9. **`@Immutable AppTileData` wrapper** (`HomeScreen.kt`) excludes the unstable `ByteArray` icon from `AppTile` inputs so the icon grid skips recomposition on `appList` reassignment.

## API 28 Car Compatibility

The BYD head unit runs Android 9 (API 28). Several framework APIs the project used are API 29+:

- **`MediaCodecInfo.isHardwareAccelerated()` (API 29+)** threw `NoSuchMethodError` on the BYD, killing the process. Fix: removed the speculative hardware-decoder picker; `VideoDecoder.start()` calls `MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)` directly. `REGULAR_CODECS` lists hardware first and selects `OMX.qcom.video.decoder.avc` on the BYD.
- **`am display move-stack` (API 29+)** gated on `SDK_INT >= 29` in `CarCommandRouter.moveTopApp`. On older levels the foreground app is left in place rather than a silent shell failure masking as success.
- **`cmd display power-reset` / `power-off` (API 29+)** is the shell fallback in `DisplayPowerController`. There is no `power-on` subcommand (`cmd display help` on Android 15: only `power-off` and `power-reset`); `power-reset` re-powers a forced-off panel. On API 26-28 a `DisplayControl` reflection failure means the physical panel is not restored — now logged via `logErr`, was silent.
- **`minSdk` raised to 26** for `protocol` and `app-server` (was 24). Re-arms the NewApi lint gate. `app-client` stays at 29.

## Removed Features (relative to upstream)

- **Notification forwarding.** Phone notifications -> car screen, the nav-bar notification button, the notification list screen, the phone-side `NotificationService` (manifest entry, onboarding step, strings), and the `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` protocol messages are all deleted. The car nav bar has no notification button.
- **Recent-apps rail, clock, network info** in the nav bar: deleted. The nav bar is four buttons (home, back, previous app, disconnect; `NavBarLayout.actions()` owns the order — the left rail top-down is home/back/recent/eject, the portrait bottom bar is its exact reverse). The fourth one is not the old recents rail coming back — it is the single "switch to the previous app" key added in issue #152-PA, which just sends `GO_RECENT` (0x1F, double-tap-recents semantics, resolved on the phone by `CarCommandRouter.quickSwitch`). No task list, no thumbnails, no per-app state is cached on the car.
- **Media controls and navigation widgets** are not implemented. (Audio streaming is implemented in v1.0.3; `NowPlayingBar` exists in the source tree but is only composed when `MediaMetadata` is present, which the phone never sends.)

## Session Stability

- **Active sessions are not killed by reconnect attempts.** The WiFi gateway retry loop and manual connect stop after 3 consecutive ADB failures (`noAdbCount >= 3`) instead of looping indefinitely.
- **TCP ADB reconnects on phone IP change.** Dev mode tracks `lastAdbHost` and reconnects when the phone's IP changes.
- **Auto-fallback to TCP ADB when USB unavailable.** `VdServerDeployer.deploy` falls back to TCP ADB using the phone-host IP if known.
- **User disconnect stays IDLE**, no auto-reconnect. Persisted to SharedPreferences (`user_disconnected`).
- **VD leak prevention.** `ConnectionService.cleanupGuard` prevents duplicate `cleanupSession()` from the teardown call sites, and session generations make a stale teardown a no-op (was: 1 disconnect → 9 wake calls). Before a replacement engine is launched, every deploy site runs the shared `vdStopEngine` (graceful stop-request sentinel → two consecutive `GONE` probes, liveness read with `pgrep -f`, within a 12s budget → `killCommandForce` fallback → wait again) so two live instances never race for the same VD/DTA/9638-9639 ports.
- **Black screen self-heal.** `VideoDecoder.onSustainedBlackScreen` fires after `BLACK_SCREEN_SUSTAIN_MS` (3s) of continuous tiny keyframes, not on transient 3-frame bursts during app warm-up; the desktop uses a wider 10s window via `setSustainWindow`. `CarConnectionService.rehandshakeForBlackScreen()` rebuilds the phone-side VD via a re-handshake at the current dimensions. `blackScreenRecoveryInFlight` latch ensures at most one rebuild per session, preventing the reconnect storm that originally caused the VD leak.

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Direct VD streaming (no phone relay)** | VD server binds 9638/9639; car talks to VD directly. Was 4 socket ops + 2 context switches per frame; now 2 socket ops + 0. Phone is pure orchestrator. |
| **Lifecycle channel on `localhost:19647`** | Carries `MSG_DISPLAY_READY` / `MSG_STACK_EMPTY` / `CMD_STOP` between phone and VD server. Separate from video/touch so a stuck encoder cannot block lifecycle commands. The phone's listener binds loopback only and refuses non-loopback peers. |
| **`VD_PORTS_BOUND` control message** | Phone tells the car when the VD server has bound 9638/9639. Car connects video/input only after this signal, avoiding connect races. |
| **Dedicated TCP connections (4 sockets)** | Control (9637), video (9638), input (9639), and audio (9640) on separate sockets. Eliminates cross-channel backpressure (large video frames cannot stall audio or input). |
| **AAC-LC audio mirroring (port 9640)** | Direct VD server-to-receiver streaming on port 9640 with continuous 46.875 fps synthetic silence pacing, preventing jitter buffer underflow. Receiver uses 150ms jitter buffer and reports silence metrics. |
| **Single-threaded pipeline, no queues** | `clock -> GL -> encoder -> TCP` on one thread. Natural flow control: a TCP stall blocks the next `eglSwapBuffers`, slowing the encoder. Uses `System.nanoTime()` + `LockSupport.parkNanos()` for drift-free 20fps. |
| **Shizuku deploys VD server when available** | Phone runs `app_process` directly via Shizuku; the car only needs WiFi. The car waits for `VD_PORTS_BOUND` instead of deploying via ADB. |
| **`shellBackground` for `app_process` launch** | Keeps the ADB stream open so the shell does not exit and kill the VD server process. |
| **NIO non-blocking everywhere** | All sockets non-blocking: WiFi connections, lifecycle localhost, Selector-based reads. No blocking I/O in the pipeline. |
| **20fps / 4Mbps CBR** | Tuned for the Snapdragon 439 + Adreno 505 + 2.4GHz WiFi. 50ms per-frame budget; adaptive bitrate floor 1.5Mbps for congested links. |
| **SurfaceView (not TextureView) on the car** | Bypasses the Adreno 505 per-frame GL composite. `outputSurfaceValid` gate + `switchSurface` handle surface destroy/re-create across navigation without keyframe loss. |
| **`createDecoderByType` direct** | `isHardwareAccelerated()` is API 29+ and threw on the BYD API-28 head unit. `REGULAR_CODECS` lists hardware first; `OMX.qcom.video.decoder.avc` is selected on the BYD. |
| **Encode dims clamped to 1920x1080** | Snapdragon 439 VPU caps hardware AVC decode at 1080p. Encoding larger forces software decode on the 8x A53 (single-digit fps). |
| **DPI auto-calibration** | `VideoConfig.calculateOptimalDpi` caps DPI so portrait-only apps get >=360dp logical width in a landscape VD. Optional car-side override in [120, 480]. |
| **Anti-crop VD scale** | VD width scaled up to match the phone's physical width, capped at `VdDimensions.MAX_UPSCALED_WIDTH = 2560` (issue #71). Chinese ROMs (Meizu, Xiaomi) hardcode IME width to the physical display; a narrower VD chops the keyboard horizontally. The cap accepts some IME crop on >2560px phones in exchange for ~36% less per-frame composite area. issue #153 PB adds a **session-scoped area tier** (`VdAreaTier`, car setting → `HandshakeRequest.vdAreaTier` trailing field → `VdDimensions.tierCap`): default `max(carWidth*1.6, 1600)` (still <= 2560, ~44% less area on a 1204-wide car), compatibility tier keeps the full 2560. The tier only changes the ceiling — it never scales a VD down, and switching tiers takes effect on the next handshake, never via an in-session resize. |
| **IME restore on teardown** | The VD's linkpc IME must not outlive the session. `ImeRestore.shouldRestoreIme` excludes it; the phone's `PhoneDisplayRestorer` and the VD's `DisplayPowerController` both restore the original IME. |
| **Watchdog forces `cleanup()`** | If the pipeline thread hangs in a native MediaCodec call, the lifecycle reader's `finally` never runs. The watchdog polls `running` + `cleanedUp` and forces `cleanup()` + `System.exit(1)` after a 3s grace. It also **consumes the stop-request sentinel** (`/sdcard/DiLinkAuto/stop-request`): on seeing the file it deletes it, logs, sets `running=false` and unparks the pipeline thread — the engine-side half of `gracefulStopCommand`. |
| **Mid-stream rotation re-handshake** | Control connection is reused; only video/input + VD server are recycled. The streaming-layout gate accepts `CONNECTING && appList.isNotEmpty()` so the SurfaceView is not destroyed during the redeploy gap. |
| **Reconnect stops after 3 ADB failures** | `noAdbCount >= 3` stops the reconnect loop instead of looping indefinitely. User is told to plug the phone into car USB. |
| **Stop-request sentinel (`gracefulStopCommand`)** | 2026-10-10 重写：ART 的 `app_process` 收 SIGTERM 直接终止、**不跑 JVM shutdown hook**（MI 9 实测 cleanup 零执行），旧的"两段式 SIGTERM→SIGKILL"因此几乎从不触发 `cleanup()`——面板/letterbox/IME/screen_off_timeout 永远不恢复。现在外部写 `/sdcard/DiLinkAuto/stop-request`（瞬时返回），引擎 watchdog 轮询→消费（删除）→ `running=false` → 正常退出路径 → **完整 cleanup**；超时才 `killCommandForce` 兜底。引擎启动时删除残留哨兵防误杀新实例。Outsiders drive it through `vdStopEngine` only (no per-site copies); liveness is read with `pgrep -f` (toybox rejects `pkill -0`), and the `[P]ipelineServer` bracket pattern is honored by **`pgrep`** — `pkill -f` still kills the wrapper shell running it, which is why the force kill is now `pgrep` + `kill <pid>` instead of `pkill -9 -f`. |
| **Cleanup idempotency (`cleanupGuard`)** | `AtomicBoolean` guard on `cleanupSession()`. Was 7 call sites with no guard — 1 disconnect fired the whole teardown 9 times, each racing a `pkill` against the previous restore. `resetCleanupGuard()` before each new session. |
| **Peer pinning (`CAR_HOST` argv slot)** | The engine still binds `0.0.0.0` (it must accept the receiver), but the deployer passes the receiver IP it took from the live control connection and every other accept is refused. `-` keeps the legacy accept-any behaviour only for sites that cannot determine their own outbound address. See the trust-boundaries section. |
| **VD exit wait before relaunch** | One shared `vdStopEngine` (protocol-core), used by `vdRunDeploySequence` (all four deploy sites, then launch) and by the receiver's teardown: graceful stop-request → two consecutive `GONE` probes (`pgrep -f`) within a 12s budget → `killCommandForce` fallback → wait again. Two live instances race for the same VirtualDisplay / DTA / 9638-9639 ports; the loser skips `cleanup()` entirely. Per-site variants of this rule are how the leak came back once. |
| **`awaitIdle()` before `shell.close()`** | `ShellExec.execShell` returns once stdin is written; `cleanup()` used to write its last restore command and immediately SIGTERM the `sh`, losing it (实测 `settings put system screen_off_timeout 60000` 被吞). `PersistentShell.awaitIdle()` writes `echo __DILINK_SYNC__<n>-<nanos>` into the same `sh` and blocks on `ShellSyncPoint` until the drain thread sees the echo — dual FIFOs make that a full pipeline barrier. 2s timeout, non-throwing. |
| **`saveCurrentIme()` before `create()`** | The snapshot must run before `configureEnvironment()` writes `screen_off_timeout=2147483647`, or the restore path sees the sentinel and does nothing. Old code snapshotted after `create()` — the user's real timeout was lost forever. |
| **Process-lifetime PhoneDisplayRestorer scope** | Owns `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — not the Service scope, which `onDestroy()` cancels. `NonCancellable` ensures `cmd display power-reset` cannot be skipped. `inFlight` collapses concurrent requests. |
| **Black screen self-heal** | `onSustainedBlackScreen` fires after the sustained window (3s car / 10s desktop) of tiny keyframes, not on 3-frame bursts. `rehandshakeForBlackScreen()` rebuilds the VD. `blackScreenRecoveryInFlight` latch prevents storms; the desktop additionally caps automatic reconnects at 2 per process. |
| **`stopVdServer()` returns Boolean** | Callers can now check if CMD_STOP succeeded or fell back to shell kill. Was void — callers assumed graceful stop always worked. |
| **Allowlist filters the wire payload** | Phone-side `AllowlistScreen` selects which launcher apps reach the car. `AppListBuilder.sendAppList` filters before building the wire payload; `ACTION_ALLOWLIST_UPDATED` triggers a live re-send. Pre-seeded with common map apps on first run. |
| **Car APK embedded in phone APK** | `embedServerApk` bundles `app-server.apk` into `app-client` assets; `CarAppInstaller` pushes it to the car via `dadb`. Enables "Install on Car" from the phone. |
| **`buildVdServer` task** | `bundleLibRuntimeToJarDebug` -> D8 -> `vd-server.dex` -> `vd-server.jar` -> copied to `app-client/src/main/assets/`. Bundles Kotlin stdlib + coroutines (needed at `app_process` runtime). |
| **Heartbeat on control only** | Video and input connections have no heartbeat overhead. Control connection watchdog detects dead peers (10s timeout). |
| **FileLog bypasses HyperOS logcat filtering** | File-based logging with rotation on `/sdcard/DiLinkAuto/`. Defaults to ON for debug/pre-release, OFF for release; user choice persists. |
| **Car log relay with bounded buffer** | `CarLogWriter` buffers up to 10k lines when the control connection is down, flushes on reconnect. `AtomicInteger` counter for O(1) cap check. |
| **Crash report flush on next connect** | `CarCrashHandler` writes to `filesDir/crash-pending.log`; the next successful connection sends it to the phone via `carLogSend`. |
| **logSink callbacks** | `VideoDecoder`, `UsbAdbConnection`, and `CarCrashHandler` route logs through the protocol to the phone's `FileLog`. |
| **ADB prehashed auth** | `AUTH_SIGNATURE` uses `NONEwithRSA` + SHA-1 DigestInfo prefix (prehashed). Matches AOSP's `RSA_sign(NID_sha1)`. "Always allow" persists correctly. |
| **Display power via DisplayControl** | `DisplayControl.setDisplayPowerMode` loaded from `services.jar` via `DelegateLastClassLoader`. Falls back to `cmd display power-reset` / `power-off` (API 29+; no `power-on` subcommand exists). The phone's `PhoneDisplayRestorer` does a layered restore (Shizuku -> PowerManager.wakeUp -> FLAG_TURN_SCREEN_ON -> wake lock). |
| **Trusted VD flags** | `0x6c49` (`OWN_DISPLAY_GROUP` + `OWN_FOCUS` + `TRUSTED`) prevents activity migration off the VD. |
| **Even viewport width** | Nav bar width adjusted to guarantee H.264-compatible even dimensions. |
| **App launch dedup** | `am start` without `--activity-clear-task`. Existing apps resume instead of restarting. |
| **Behaviour-preserving test seams** | The hot pure logic was made reachable on the JVM instead of being rewritten: ADB framing/crypto (`negotiateMaxPayload`, `readMessageFrom`, `java.util.Base64` key encoding), `Connection` heartbeat/watchdog intervals, `AssetSource` and `CarInstaller` in the phone, `reconnectBackoffMs` / `iconBudgetAccepts` on the car. Runtime behaviour is unchanged; the point is the regression net. See `docs/IMPLEMENTATION_REPORT_TESTING.md`. |
| **Fail-closed decoding** | Every message decoder validates its bounds and throws `ProtocolDecodeException`; payload caps (16MB), reader ceilings and listener dispatch all treat a malformed peer as a dead *connection*, never a dead process. |

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9.22 (all modules); Java for `UsbAdbConnection` / `AdbProtocol` / `AdbCrypto` |
| Build | Gradle 8.7, AGP 8.2.2, JDK 17 |
| UI | Jetpack Compose + Material 3 (Compose BOM 2023.10.01) on Android; Compose Desktop 1.6.2 (JetBrains) on Windows |
| Video | MediaCodec H.264 — encoder: VD server 4Mbps CBR Main, I-frame 1s; car decoder: `createDecoderByType` (hardware-first); desktop decoder: JavaCV 1.5.10 + FFmpeg 6.1.1 (Windows x86_64), D3D11VA with software fallback |
| Audio | AudioRecord (REMOTE_SUBMIX) + MediaCodec AAC-LC (48kHz stereo, 128kbps, 46.875 fps synthetic silence pacing) -> TCP 9640 -> AudioTrack (150ms jitter buffer) |
| GPU | EGL14 + GLES20 + SurfaceTexture (single-threaded pipeline) |
| Networking | NIO `ServerSocketChannel` / `SocketChannel` / `Selector`, Android NSD (mDNS) |
| USB ADB | Custom protocol in `protocol/` module (`UsbAdbConnection`, `AdbProtocol`, `AdbCrypto`), `logSink` for diagnostics |
| TCP ADB | `TcpAdbConnection` (single socket, reused for all shell commands) for the car -> phone path; `dadb` 1.2.10 for the phone -> car install path; the desktop shells out to `adb.exe` |
| Elevated shell | Shizuku (`dev.rikka.shizuku:api/aidl/provider:13.1.5`) — phone-side, optional |
| Desktop extras | JNA 5.14 (`SetThreadExecutionState`), `jpackage` app-image with a bundled JRE |
| Async | Kotlin Coroutines + Flow (kotlinx-coroutines 1.7.3) |
| Tests | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3, no Robolectric/Mockito — Android modules use `isReturnDefaultValues = true` plus hand-written fakes |
| Min API | 26 (`protocol`, `app-server`); 29 (`app-client`, `vd-server`) |
| Releases | See [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) |
| Protocol Version | `PROTOCOL_VERSION = 1` |

## Port Reference

| Port | Direction | Purpose |
|------|-----------|---------|
| 9637 | receiver -> phone | Control + data (handshake, app list, heartbeat, `VD_PORTS_BOUND`) |
| 9638 | VD server -> receiver | H.264 video (VD server binds `0.0.0.0`, accepts only the pinned `CAR_HOST`) |
| 9639 | receiver -> VD server | Touch input (VD server binds `0.0.0.0`, accepts only the pinned `CAR_HOST`) |
| 9640 | VD server -> receiver | AAC-LC audio (VD server binds `0.0.0.0`, accepts only the pinned `CAR_HOST`) |
| 19647 | VD server -> phone | Lifecycle channel (reverse-connect to the phone's `127.0.0.1` listener) |
| 5555 | car -> phone | ADB TCP (dev-mode phone-IP lookup, self-install) |
