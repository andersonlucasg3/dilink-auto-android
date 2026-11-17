# Protocol Specification

## Overview

DiLink-Auto 在手机与车机之间使用 **4 条专用 TCP 连接**（控制/数据、视频、输入、音频），手机 App 与 VD server 之间还有一条内部生命周期连接：

| Connection | Port | Direction | Content |
|------------|------|-----------|---------|
| **Control + Data** | 9637 | Bidirectional | Handshake, heartbeat, app commands, app list, car logs, media metadata |
| **Video** | 9638 | VD server -> Car | H.264 CONFIG + FRAME only |
| **Input** | 9639 | Car -> VD server | Touch events only |
| **Audio** | 9640 | VD server -> Car | AAC-LC CONFIG + FRAME；双向协商后绑定，独立传输 |
| **Lifecycle** | 19647 | VD server -> Phone (localhost) | Display ready / stack empty / stop signals |
| **ADB TCP** | 5555 | Car -> Phone (or Phone -> Car for dadb) | Persistent single-socket ADB for shell commands and car APK install |

9640 刻意**不复用** 9638：`VideoFrameSender` 是单线程 + 有界队列的写侧，一个 1 MB 的 IDR 会把 341 B 的音频帧堵在队头后面。音频必须走独立连接，且音频连接失败不得拖垮整条视频会话。

The VD server binds the video and input ports directly on `0.0.0.0:9638` and `0.0.0.0:9639`, so video frames and touch events flow directly between the VD server and the car without phone-side relaying. The phone app is not on the video or input data path. A deploy site that knows its own outbound address can pass a receiver IP in the trailing `CAR_HOST` argv slot (`VdDeployArgs.CAR_HOST_ANY` = `-` keeps the accept-any behaviour), which pins those two accepts to the peer the engine actually serves.

The control connection (9637) is established first and carries the handshake, heartbeat, app commands, and the DATA channel (app list, car logs, media metadata). After handshake the phone deploys the VD server, which binds 9638/9639 and reverse-connects to the phone on `localhost:19647`. When the VD server signals display-ready on the lifecycle channel, the phone sends `VD_PORTS_BOUND` to the car on the control connection; only then does the car open video (9638) and input (9639) connections to the VD server. Heartbeat/watchdog runs only on the control connection; video and input have no heartbeat overhead, but every connection still inherits the reader's mid-frame stall deadline (see [NioReader stalls](#wire-format)). Any connection dying cascades to full session teardown.

The lifecycle channel on `localhost:19647` is documented in [client.md](./client.md). It uses a small fixed message set (`MSG_DISPLAY_READY`, `MSG_STACK_EMPTY`, `CMD_STOP`) and is the only channel the VD server reverse-initiates to the phone.

## Wire Format

```
+-----------------+------------+--------------+-----------------+
| Frame Length     | Channel ID | Message Type | Payload          |
| (4 bytes)        | (1 byte)   | (1 byte)     | (N bytes)        |
| big-endian uint32|            |              |                  |
+-----------------+------------+--------------+-----------------+
```

- **Frame Length**: `uint32` big-endian. Value = `2 + payload_size` (does not include the 4-byte length field itself). A declared length below `2` is rejected (`Frame too small`).
- **Max payload**: 16 MB (`FrameCodec.MAX_PAYLOAD_SIZE`). The old 128 MB value let a 7-byte header force ~256 MB of allocation (payload `ByteArray` plus the `NioReader` grow-on-demand buffer) on the phone and on the shell-UID vd-server; 16 MB leaves 8x headroom over a 1080p keyframe and turns the same hostile header into a 32 MB event that both sides survive.
- **Header overhead**: 6 bytes per frame (`FrameCodec.HEADER_SIZE`).
- **Header encoding**: big-endian. `FrameCodec.encodeHeaderInto` is the single definition of the 6-byte layout, shared by `writeFrame`, `writeFrameToChannel` and the `Connection` write coroutine; a `ThreadLocal` 6-byte header buffer avoids per-frame allocation.
- **Write path**: a single dedicated writer coroutine drains a bounded `Channel<Frame>` of size 64 with `BufferOverflow.SUSPEND`, so a slow TCP consumer applies backpressure to readers instead of growing unbounded. Gathering writes (`channel.write(ByteBuffer[])`) combine header + payload into one syscall. Enqueue order is pinned by a single-thread `sendDispatcher` plus a `sendMutex`: without the lock, `limitedParallelism(1)` serialises dispatch but not suspension, so a full queue reordered touch DOWN/MOVE/UP exactly when the link was congested.
- **Read path**: `FrameCodec.readFrame(NioReader)` (suspend) for coroutine callers (phone, car); `FrameCodec.readFrame(InputStream)` and `FrameCodec.readFrameBlocking(NioReader)` for blocking callers (VD server). The two blocking variants share one `readFrameCore` skeleton, and every path validates the declared payload size through the same `validatePayloadSize` before allocating anything.
- **Reader buffer ceiling**: `NioReader.MAX_READ_UNIT` = `HEADER_SIZE + MAX_PAYLOAD_SIZE` is a hard ceiling for a single grow; `maybeShrink()` at each frame boundary drops an oversized buffer back to `DEFAULT_CAPACITY` (128 KB) so one big frame does not pin memory for the session.
- **NioReader stalls**: `readStallTimeoutMs` (10 s on every `Connection`) fails a read that stalls *mid-frame* — partial bytes already buffered, then silence (half-open TCP, killed peer). An idle wait at a clean frame boundary (touch link with no user input) deliberately arms nothing and is never timed out.
- **Write timeout**: `FrameCodec.writeAll` throws if no progress is made for 10 seconds (send buffer full / peer not reading). Was 5s; raised in issue #70 — the deadline only resets on real byte progress, so 5s zero-progress on a 512KB buffer meant a brief WiFi hiccup killed the session. The `Connection` writer coroutine has no deadline of its own — the watchdog owns dead connections.
- **Graceful close**: a clean peer EOF (`reader: EOF`) flushes already-queued frames for `FLUSH_GRACE_MS` (500 ms) before the writer is cancelled and the socket closed, so a `DISCONNECT` followed by FIN still delivers queued replies.
- **Connect deadline**: `Connection.connect` fails after `CONNECT_TIMEOUT_MS` (10 s) instead of spinning `finishConnect()` forever on a black-holed SYN.
- **Version check**: `requireSupportedProtocolVersion` rejects any `protocolVersion` != `PROTOCOL_VERSION` with `ProtocolDecodeException`. The field used to be carried and never validated, which decoded trailing fields at the wrong offsets.
- **Large-frame logging**: frames above 8 MB log one warning per stream (a 1080p keyframe is ~1 MB). The previous 100 KB threshold logged once per GOP on both ends.

## Channels

| ID | Name | Connection | Direction | Purpose |
|----|------|------------|-----------|---------|
| 0 | CONTROL | Control (9637) | Bidirectional | Handshake, heartbeat, app commands, VD lifecycle signals |
| 1 | VIDEO | Video (9638) | VD server -> Car | H.264 encoded video frames |
| 2 | AUDIO | Audio (9640) | VD server -> Car | AAC-LC CONFIG + FRAME；双向协商开启（isAudioNegotiated） |
| 3 | DATA | Control (9637) | Bidirectional | App list, app info data, car logs, media metadata, log toggle |
| 4 | INPUT | Input (9639) | Car -> VD server | Touch events (batched MOVE for multi-touch) |

The DATA channel multiplexes on the same TCP socket as CONTROL. VIDEO, INPUT and CONTROL frames are dispatched inline on the reader thread for low latency and for arrival order (touch DOWN/MOVE/UP and navigation commands are order-sensitive); only DATA is dispatched to a coroutine scope, because app-list decode is the one heavy handler that must not stop the reader draining TCP. A frame listener that throws (a decoder raising `ProtocolDecodeException` or `BufferUnderflowException` on a malformed peer payload) fails the connection with a logged reason instead of killing the reader coroutine — there is no `CoroutineExceptionHandler` anywhere in this repo.

## Control Channel (0x00)

### HANDSHAKE_REQUEST (0x01) -- Car -> Phone

```
+----------------------+--------+
| protocolVersion      | int32  |
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| screenWidth          | int32  |  car display width in pixels (after nav bar)
| screenHeight         | int32  |  car display height in pixels
| supportedFeatures    | int32  |  **接收端**想要的能力位掩码；默认仅 FEATURE_VIDEO
| displayMode          | byte   |  0=MIRROR, 1=VIRTUAL (default)
| screenDpi            | int32  |  car display density (e.g. 160)
| appVersionCode       | int32  |  car app version code (informational)
| targetFps            | int32  |  car's requested FPS (e.g. 24)
| appVersionName len   | int16  |  version name string length
| appVersionName       | UTF-8  |  car app version name
| dpiOverride          | int32  |  trailing field, default 0
| bitrate              | int32  |  trailing field, target bitrate in bps, default 0
| vdAreaTier           | int32  |  trailing field, VD 合成面积档位（0=AUTO / 1=COMPAT_2560），default 0
+----------------------+--------+
```

All three trailing fields are appended after `appVersionName` and decode as `0` when absent, so older peers interoperate: a missing `dpiOverride` means auto-calibrate, a missing `bitrate` means the 4 Mbps default, a missing `vdAreaTier` means the default area tier ([VdAreaTier.AUTO]).

`vdAreaTier` (issue #153 PB) 是**会话级**档位：车机在握手时下发 `0` 或 `1`，手机在本次建 VD 时把它交给 `VdDimensions.compute` 决定 anti-crop 放大上限（默认档 `max(carW*1.6, 1600)`、兼容档 2560）。它**不是**运行期尺寸同步消息，切档靠下次连接 / 旋转重握手，不在会话中 `resize`。老手机忽略尾部 4B；老车机不带该字段时手机解出 `0` = 默认档。注意车机 ADB 部署路径（`VdServerDeployer`）本来就用 car-native 视口建 VD、不消费 `HandshakeResponse.vdWidth/vdHeight`，因此档位对它是 no-op；真正消费方是 Shizuku 路径与桌面端 `AdbDeploy`。

`supportedFeatures` 默认只置 `FEATURE_VIDEO`。`FEATURE_AUDIO` 必须由 `HandshakeRequest.builder().supportedFeatures(...)` 显式开启，含义是「接收端想播且能解码音频」，与手机能不能采集无关。S1 之前默认值是 `FEATURE_VIDEO or FEATURE_AUDIO`，导致两个构造点（车机 `buildHandshakeRequest`、桌面端 `HandshakeFactory.build`）都在假广告一个根本没实现的音频通道（issue #67）。手机自身的能力由 `HandshakeResponse` 的尾字段 `supportedFeatures` 回传；protocol-core 的 `isAudioNegotiated(request, response)` 是唯一判据，要求 `accepted == true` **且**两侧都置了 `FEATURE_AUDIO`，之后才允许 bind 9640。接收端音频开关的置灰依据必须是手机的 response，绝不能用接收端自己的 `SDK_INT`：Android 11（SDK 30）的采集下限约束的是手机，不是车机（`app-server` 的 `minSdk` 为 26）。

`dpiOverride` is a trailing field. `0` means auto-calibrate via `VideoConfig.calculateOptimalDpi` (the portrait-app-safe cap). A non-zero value in `[120, 480]` bypasses the cap and is used verbatim, fixing "UI too small" for landscape apps at the cost of squeezing portrait-only apps. The decoder's remaining-bytes guard reads `0` when the field is absent, so older car peers that do not send it decode as auto. The car coerces input through `VdDeployArgs.coerceDpiOverride` so the phone and car agree on what counts as a valid override.

`bitrate` is the receiver's requested encoder bitrate in bps (e.g. 2_000_000, 4_000_000), `0` = `VideoConfig.DEFAULT_BITRATE` (4 Mbps). It rides through `VdDeployArgs.format` into the VD argv, where it is validated against the deliberately wider deploy range `VideoConfig.INPUT_MIN_BITRATE..INPUT_MAX_BITRATE` (0.5-20 Mbps) and falls back to the default when out of range. Both senders (car `buildHandshakeRequest`, desktop `HandshakeFactory.build`) assemble the message through `HandshakeRequest.builder()`; the builder does not align dimensions, because alignment direction is sender-specific and load-bearing (desktop floors to even, the car widens its nav bar by one pixel so the viewport lands even).

### HANDSHAKE_RESPONSE (0x02) -- Phone -> Car

```
+----------------------+--------+
| protocolVersion      | int32  |
| accepted             | byte   |  1=accepted, 0=rejected
| deviceName length    | int16  |
| deviceName           | UTF-8  |
| displayWidth         | int32  |  VD width (matches car request)
| displayHeight        | int32  |  VD height (matches car request)
| virtualDisplayId     | int32  |  -1 (set by VD server, not phone)
| adbPort              | int32  |  5555 (phone's ADB TCP port)
| vdServerJarPath len  | int16  |
| vdServerJarPath      | UTF-8  |  path to deployed VD JAR (e.g. /sdcard/DiLinkAuto/vd-server.jar)
| connectionMethod     | byte   |  0=USB_ADB, 1=WIFI_ADB, 2=SHIZUKU
| vdDpi                | int32  |  DPI the VD will use (auto or override)
| vdWidth              | int32  |  trailing field, recommended VD width, 0 = not provided
| vdHeight             | int32  |  trailing field, recommended VD height, 0 = not provided
| supportedFeatures    | int32  |  尾字段，**手机侧**采集能力位掩码，0 = 不支持音频
+----------------------+--------+
```

`vdWidth` / `vdHeight` are trailing fields carrying the phone-side recommended VirtualDisplay size — the output of `VdDimensions.compute`, which even-aligns the car viewport and then scales it up to the phone's **real physical** long edge. A Chinese-ROM IME hardcodes its width to the phone's physical pixel width regardless of VD density, so a viewport-sized VD crops the keyboard no matter what DPI is negotiated (a 1280 px canvas with a 1368 px keyboard chops the right edge).

`0` means an older phone did not send the field. Deploy sites with no shell access to the phone — the desktop ADB path — MUST use these dims for the VirtualDisplay when they are `> 0`; the desktop's own viewport is the *encoder* size, not the VD size. The phone-side metrics must come from real physical display metrics (`WindowManager.maximumWindowMetrics` / `Display.getRealMetrics`), never `resources.displayMetrics`, which reports compat-scaled values in screen-compat mode.

`supportedFeatures`（S1）是音频能力交换里**手机 / 采集侧**的那一半，追加在 `vdHeight` 之后，与 request 里的同名字段语义不同：request 说的是接收端想用什么，这里说的是手机能提供什么。老手机不带末尾这 4 字节时解码为 `0`，这是安全的回退方向 —— `isAudioNegotiated` 返回 false，没人会去连一个从未监听的端口。手机只有在自身 SDK 与采集探针允许 `REMOTE_SUBMIX` 时才在这里置 `FEATURE_AUDIO`。

### HEARTBEAT (0x03) / HEARTBEAT_ACK (0x04) -- Control connection only

Empty payload. Sent every 3 seconds on the control connection. If no frame is received within 10 seconds, the connection is considered dead (watchdog timeout). Video and input connections have no heartbeat. The reader enqueues `HEARTBEAT_ACK` inline (no separate coroutine) so heartbeat acks cannot be starved by a busy writer queue.

The intervals are injectable: `Connection.start(enableHeartbeat, heartbeatIntervalMs, heartbeatTimeoutMs)` defaults to `HEARTBEAT_INTERVAL_MS` (3000) and `HEARTBEAT_TIMEOUT_MS` (10000), so the tuning is testable without a socket. The watchdog ticks on `heartbeatIntervalMs` and tears the connection down when `now - lastFrameReceivedAt > heartbeatTimeoutMs`. Both jobs are cancelled by `disconnect()` and are only started when `enableHeartbeat` is true; the reader's separate mid-frame stall deadline (`readStallTimeoutMs`, also 10 s) covers the connections that have no watchdog at all.

### DISCONNECT (0x05) -- Bidirectional

Empty payload. Graceful shutdown.

### LAUNCH_APP (0x10) -- Car -> Phone

```
+----------------------+--------+
| packageName          | UTF-8  |  raw bytes, no length prefix
+----------------------+--------+
```

Phone forwards to VD server which runs `am start --display <id> -n <component>` (no `--activity-clear-task` -- existing apps resume).

### GO_HOME (0x11) / GO_BACK (0x12) -- Car -> Phone

Empty payload. Phone forwards to VD server. GO_BACK sends `input -d <id> keyevent 4`, then checks for empty stack. GO_HOME is handled by the car launcher (the phone does not forward it to the VD server).

### APP_STARTED (0x13) -- Phone -> Car

Same format as LAUNCH_APP. Defined for confirmation; the car-side handler is a no-op.

### APP_STOPPED (0x14) -- Phone -> Car

Defined in `MessageType.kt`. No send/receive references outside the constant definition; retained for protocol completeness.

### VD_STACK_EMPTY (0x15) -- Phone -> Car

Empty payload. Sent after GO_BACK when the VD server detects no remaining app tasks on the virtual display (via `dumpsys activity activities`). The car uses this to switch from mirror view to home screen.

### APP_INFO (0x17) -- Car -> Phone

Payload: UTF-8 package name. Car requests the phone to open the system app info/settings screen for the given package.

### APP_UNINSTALL (0x1B) -- Car -> Phone

Payload: UTF-8 package name. Car requests the phone to uninstall the given package. The phone handles the uninstall system dialog and sends back `APP_UNINSTALLED` via the data channel when done.

### GO_RECENT (0x1F) -- Car -> Phone

Empty payload. Quick-switches the virtual display to the most recently used *other* task (the "double-tap recents" semantics). Deliberately NOT `input -d <id> keyevent 187` — AOSP's system recents hard-codes the default display, so an injected APP_SWITCH only toggles the *physical* screen's recents and never the VD, and there is no per-display recents shell interface. vd-server instead parses `dumpsys activity recents` (skipping the current VD-top task, the control app, launcher and systemui), then runs `am start --display <id> --task <taskId> -n <component>`. Fully async — never blocks the touch reader.

### VD_SERVER_READY (0x20)

Defined in `MessageType.kt` as a Car -> Phone signal, but unused in the current codebase (no send or receive references). The `VD_PORTS_BOUND` message (0x31) is the active signal for VD readiness. Documented here to avoid confusion when reading the constant table.

### VD_PORTS_BOUND (0x31) -- Phone -> Car

Empty payload. Sent after the VD server has bound the video (9638) and input (9639) ports and signaled display-ready on the lifecycle channel. The car waits for this message before calling `connectVideoAndInput(phoneHost)`, which opens the video and input TCP connections directly to the VD server. This gate prevents the car from connecting to ports that the VD server has not yet bound, which was a race in earlier versions.

### SET_DISPLAY_POWER (0x32) -- Car/PC -> Phone (control channel)

1-byte payload: a non-zero first byte = screen on, `0` or an empty payload = screen off (an empty payload deliberately means off, so a peer that forgets the field cannot accidentally light the panel). Routed by the vd-server's `handleCarCommand` to `DisplayPowerController.powerOffPanel()` (off) or `restorePanel()` (on) — the same path as `GO_HOME`, so app-client is not involved. The vd-server powers the phone's physical panel off by default once it holds video + input, and restores it on disconnect; this message hands the mid-session manual toggle to the user.

## Video Channel (0x01)

### CONFIG (0x01) -- VD server -> Car

H.264 SPS/PPS NAL units with start codes. Sent once at encoder start.

### FRAME (0x02) -- VD server -> Car

H.264 NAL units representing a video frame.

**Encoding parameters** (set by VD server `PipelineServer`):
- Codec: H.264/AVC
- Profile: Main
- Resolution: encode dimensions proportionally scaled down to fit inside 1920x1080 (`VdDeployArgs.MAX_ENCODE_WIDTH/HEIGHT`), decoupled from VD dimensions via the `EW EH` args. The VD stays scaled-up to preserve the IME-crop fix (capped at 2560 px wide since issue #71); the encoder uses car-reported dims clamped to 1080p. Scaling is aspect-preserving (the old per-axis clamp turned a 1080x2152 portrait viewport into a square 1080x1080). Without the cap, phones with >1080p output trigger software decode on low-spec car SoCs (e.g., Snapdragon 439) and drop to single-digit fps.
- Bitrate: 4 Mbps CBR (`VideoConfig.DEFAULT_BITRATE`, `PipelineServer.BITRATE`). Receiver-requested via `HandshakeRequest.bitrate`; deploy-argv validation range 0.5-20 Mbps (`INPUT_MIN_BITRATE`..`INPUT_MAX_BITRATE`), settings-UI range 1-12 Mbps. Adaptive fallback: floor 1.5 Mbps (`VideoConfig.ADAPTIVE_MIN_BITRATE`), drop to 75% of current on a stalled decoder, recovery with a 2 s window and 0.5 Mbps steps.
- Frame rate: 20 fps (`VideoConfig.TARGET_FPS = 20`, issue #71). Car requests this via `targetFps` in handshake; settings UI accepts 10-60.
- Frame interval: `1000 / 20` = 50 ms (`VideoConfig.FRAME_INTERVAL_MS`). Used as the max wait for video-path loops (encoder drain, SurfaceScaler redraw).
- IDR interval: 3 seconds (`PipelineServer.I_FRAME_INTERVAL = 3`, issue #70). Was 1s; the 2.5h navigation log showed ~1 IDR/s (`keys=659 / 15731 frames @24fps`), which burned bandwidth on keyframes the car did not need.
- SurfaceScaler: periodic re-draw every `FRAME_INTERVAL_MS` ensures encoder output on static content.

**Keyframe identification**: `H264NalParser.isKeyFrame` (protocol-core) is the single definition shared by the car decoder and the Windows pipeline: it scans only the first ~1 KB of a frame for a 3-byte (`00 00 01`) or 4-byte (`00 00 00 01`) Annex B start code and tests `nal_header & 0x1F == 5` — the type mask, not the whole byte, because a real encoder emits `0x65` (`nal_ref_idc = 3`).

**Black-screen detection**: `BlackScreenDetector` lives in `:protocol-core` and is shared by the car decoder and the Windows `VideoDecodePipeline` — both ends face the same encoder failure (TCP alive, receiver parked on the last frame), so the policy has one definition. An encoder producing nothing emits very small I-frames, so a streak of keyframes below `keyframeMaxBytes` (2 KB) means the stream is black. Reaction is deliberately asymmetric: `alertStreak` (3) tiny keyframes only set a log flag, and `onSustainedBlackScreen` fires once the streak lasts `BLACK_SCREEN_SUSTAIN_MS` (3 s default; the receivers override it) — app/VD warm-up bursts are normal, and escalating on those is the reconnect storm that leaked VirtualDisplays. The monotonic clock is injected (`clock`), so protocol-core stays Android-free, and the escalation is latched to once per session until `reset()`.

## 音频通道 Audio Channel (0x02)

音频镜像协议在 1.0.3 实现完整闭环（Refs #67, #70, #72, #73, #79, #84）。包含 S1 载荷契约、S2 采集编码管线与静音补帧、S3 端口绑定与接收端播放、S4 双向能力协商与开关持久化、以及 S23 端到端测试与防假绿静音帧统计。载荷编解码由 `AudioPayload` 承载，纯 JVM，不依赖 Android 直接单测。

### 阶段演化与技术规范

- **S1 载荷契约与帧格式**：定义 `AudioPayload` 编解码格式，独立端口 `9640`（`AUDIO_PORT`），包含 `CONFIG (0x01)` 与 `FRAME (0x02)`。
- **S2 编码与静音补帧**：VD server 端通过 `REMOTE_SUBMIX` 录音源采集，由 `AudioPipeline` + `AudioEncoderSelector` 编码为 AAC-LC（48000Hz, 双声道立体声, 128kbps）。48kHz AAC-LC 每帧 1024 采样 = **21.333 ms / 46.875 fps**（`AudioSpec.kt`, `AudioPacing.kt`）。采集静音期持续按 46.875 fps 节拍注入合成静音帧，杜绝接收端抖动缓冲下溢与排队延迟膨胀。
- **S3 独立 TCP 连接与播放**：VD Server 启动 `PortAcceptor` 绑定 9640 端口（通过 `CAR_HOST` 进行对端 IP 锁定），使用 `AsyncSocketSender` 异步写入。车机端 `CarSessionCoordinator` 连接 9640，交付 `AudioPlayer`（内部维护 150ms 抖动缓冲，支持 60~200ms 配置），由 `AudioTrack` 自由跑播放。
- **S4 双向能力协商**：握手双方必须在 `supportedFeatures` 均置位 `FEATURE_AUDIO`（`isAudioNegotiated` 判定）。客户端提供音频开关并持久化在本地配置；车机端根据手机响应动态置灰或启用音频开关。
- **S23 端到端场景与防假绿**：落地 S23 音频镜像 e2e 场景；车机端 `AudioPlayer` 周期日志埋点静音帧统计（`silenceFrames`/`totalFrames`），杜绝全静音帧被误判为功能正常的假绿情况（issue #84）。

### CONFIG (0x01) -- VD server -> Car

在任何 FRAME 之前发一次。大端布局：

```
+----------------------+--------+
| codec                | byte   |  1 = AAC-LC（`AudioPayload.CODEC_AAC_LC`）
| sampleRate           | int32  |  48000
| channelCount         | byte   |  2（立体声）
| bitrate              | int32  |  128000
| csdLen               | byte   |  csd-0 长度（实测 2）
| csd                  | bytes  |  MediaCodec 的 `csd-0`
+----------------------+--------+
```

`csd-0` 必须**带外**发送：裸 AAC 访问单元不带 ADTS 头，没有它解码器无法初始化。两侧都会拒绝 `csdLen` 与剩余字节数不符、未知 codec、非正的采样率 / 声道数、负的 bitrate，以及超过 `FrameCodec.MAX_PAYLOAD_SIZE` 的载荷。

### FRAME (0x02) -- VD server -> Car

```
+----------------------+--------+
| ptsMs                | uint32 |  毫秒时间戳，无符号；到 2^32 回绕
| aacFrame             | bytes  |  一帧裸 AAC-LC 访问单元（约 341 B）
+----------------------+--------+
```

48 kHz 的 AAC-LC 每帧 1024 samples = **21.333 ms / 46.875 帧每秒**（不是 20 ms / 50 fps）。`ptsMs` 只用于日志与欠载统计，本方案不做精确 A/V 同步；接收端使用抖动缓冲（默认 150 ms，可配 60~200 ms）自由跑播放。空载荷会被拒绝。采集静音期生产者仍按 46.875 fps 补发编码后的静音帧；若按 20 ms 节拍补帧，队列会膨胀约 300 ms 延迟（issue #13 真机稳定性实验结论）。

## Data Channel (0x03)

### APP_LIST (0x03) -- Phone -> Car

List of installed apps with icons. Payload layout:

```
+----------------------+--------+
| count                | int16  |  number of entries
| N x entry:           |        |
|   packageName len    | int16  |
|   packageName        | UTF-8  |
|   appName len        | int16  |
|   appName            | UTF-8  |
|   category           | byte   |  0=NAVIGATION, 1=MUSIC, 2=COMMUNICATION, 3=OTHER
|   iconSize           | int32  |  PNG bytes length (e.g. 96x96 icon)
|   iconPng            | bytes  |
|   iconHash len       | int16  |
|   iconHash           | UTF-8  |  hash string for cache invalidation
+----------------------+--------+
```

The car uses `iconHash` to skip re-decoding icons it has already cached. Clearing the icon hash cache on disconnect prevents stale icons after a session ends.

### APP_UNINSTALLED (0x06) -- Phone -> Car

Payload: UTF-8 package name. Phone confirms an app was uninstalled (in response to `APP_UNINSTALL`). Car removes the app from its launcher grid.

### APP_INFO_DATA (0x07) -- Phone -> Car

Payload: `AppInfoDataMessage`. Phone sends app metadata for display in a car-side dialog when the user selects "App Info" from the context menu.

```
+----------------------+--------+
| packageName len      | int16  |
| packageName          | UTF-8  |
| appName len          | int16  |
| appName              | UTF-8  |
| versionName len      | int16  |
| versionName          | UTF-8  |
| versionCode          | int64  |
| installTime          | int64  |  epoch millis
| targetSdk           | int32  |  targetSdkVersion of the app
+----------------------+--------+
```

### MEDIA_METADATA (0x10) -- Phone -> Car

Track info. Three length-prefixed strings (title, artist, album) followed by `durationMs` (int64). Reserved for media widget integration; not actively populated.

### MEDIA_PLAYBACK_STATE (0x11) -- Phone -> Car

1-byte state (0=stopped, 1=playing, 2=paused) followed by `positionMs` (int64). Reserved.

### MEDIA_ACTION (0x12) -- Car -> Phone

1-byte enum: 0=PLAY, 1=PAUSE, 2=NEXT, 3=PREVIOUS, 4=SEEK. Reserved (not yet wired to MediaSession).

### NAVIGATION_STATE (0x20) -- Phone -> Car

Navigation state data. Reserved for navigation widget integration.

### CAR_LOG (0x30) -- Car -> Phone

UTF-8 text line. Car routes all logs (including VideoDecoder and UsbAdbConnection) through this channel. Phone writes to FileLog (`/sdcard/DiLinkAuto/client.log`) with tag `CarLog`.

### LOG_TOGGLE (0x31) -- Phone <-> Car

1-byte payload (0 or 1). Toggles car-side logging. Defaults: ON for debug/pre-release builds, OFF for release. The user choice persists. When off, zero disk writes occur on the car.

### PHONE_STATUS (0x21) -- Phone -> Car

手机电量与充电状态（issue #151）。定长 3 字节：

```
+----------------------+--------+
| level                | uint8  |  0-100（手机已按 EXTRA_LEVEL/EXTRA_SCALE 换算）
| flags                | uint8  |  bit0 = 充电中；其余位保留，接收端忽略
| temperature          | int8   |  摄氏度；-128 = 手机未上报
+----------------------+--------+
```

- 发送时机：手机端在**握手完成且控制连接可用**后，按 `BatteryReportGate` 节流上行 —— 首次采样必发、充电位翻转或 ≥2% 变化立即发、1% 变化需距上次 ≥5s、电量不变时 60s 兜底一次。未连接/未握手不发，发送失败只记日志并保留状态以便下一轮重试（issue #41 的教训）。
- 载荷长度必须恰好 3 字节：短于或长于 3 字节都抛 `ProtocolDecodeException`（定长状态向量多出字节说明组帧错位，不能静默按前 3 字节解析）。`level > 100` 同样判错。
- 车机端只用于信息栏 chip：`86%` / `86%+`（充电中）/ `—`（未收到或已断开）。文本由 `protocol-core` 的 `BatteryFormat` 生成，受横屏左栏 76dp 定宽约束，不加图标。

**向后兼容**：车机端 `CarSessionCoordinator.handleDataFrame` 的 `when` 没有 else 分支，老版本收到 `0x21` 会直接忽略，不会误当作别的消息执行；手机端只在握手完成（`handshakeReceived`）后才发送，且未知类型不会影响老会话的其他帧。

### Decoding contract (all payloads)

Every fixed-size read is preceded by a remaining-bytes check and every length prefix is read as **unsigned** (a `0x8004` short used to become `-32764` and throw `NegativeArraySizeException`). Violations raise `ProtocolDecodeException` — never an unchecked `BufferUnderflowException` — so `Connection`'s reader can catch it, log the reason and disconnect, instead of the exception escaping into an unhandled coroutine and killing the process.

- Optional trailing fields use a lenient read that returns `""` / `0` rather than throwing, so a peer that omits them still decodes.
- A declared `iconSize` that exceeds the remaining bytes **fails** the decode: silently dropping the icon made the next `iconHash` length consume icon bytes and cache a garbage hash under a real key.
- Unknown `AppCategory` / `MediaAction` ids throw instead of coercing to `OTHER` / `PLAY` — a peer typo used to silently start playback.
- 2-byte UTF-8 prefixes are encode-side guarded: a payload >= 64 KB used to wrap negative and desynchronise the peer's parser.
- `LAUNCH_APP` / `APP_UNINSTALL` payloads are validated at the decoder boundary by `requirePackageName` / `requireComponentName` (`PACKAGE_NAME_REGEX` / `COMPONENT_REGEX` exclude every shell metacharacter — `;` `$` backtick quote whitespace `|` `&` `>` `<` `(` `)`), because the payload is interpolated into `am start` / `pm uninstall` shell lines running as shell UID. Shell quoting at the call sites is defence in depth, not the only gate.
- `AppInfoDataMessage` requires the trailing `8 + 8 + 4` bytes before reading them.

## Input Channel (0x04)

### TOUCH_DOWN (0x01) -- Car -> VD server

Single-pointer down event. Payload is a `TouchEvent` (25 bytes):

```
+----------------------+--------+
| action               | byte   |  InputMsg.TOUCH_DOWN (0x01)
| pointerId            | int32  |  multi-touch pointer ID
| x                    | float  |  normalized 0.0-1.0
| y                    | float  |  normalized 0.0-1.0
| pressure             | float  |
| timestamp            | int64  |
+----------------------+--------+
```

### TOUCH_MOVE (0x02) -- Car -> VD server

Single-pointer move event. Same `TouchEvent` payload format as TOUCH_DOWN.

### TOUCH_UP (0x03) -- Car -> VD server

Single-pointer up event. Same `TouchEvent` payload format as TOUCH_DOWN.

### TOUCH_MOVE_BATCH (0x04) -- Car -> VD server

```
+----------------------+--------+
| count                | byte   |  number of pointers
| N x pointer:         |        |
|   pointerId          | int32  |
|   x                  | float  |  normalized 0.0-1.0
|   y                  | float  |  normalized 0.0-1.0
|   pressure           | float  |
|   timestamp          | int64  |
+----------------------+--------+
```

Batched MOVE events -- all active pointers in one message. Each pointer is 24 bytes (no per-pointer action byte). Reduces syscalls for multi-touch gestures.

The `count` byte is the wire contract, so the frame protocol accepts up to 255 pointers. The **receiver** caps them: `TouchInjector.MAX_POINTERS` is 10, matching the wire encoder's emitted `pointerId` range (`0..MAX_POINTERS-1`), and `PointerCap` evicts the lowest entries rather than letting an over-full batch jam injection for the rest of the session. The wire must not enforce the cap — it is the injector's table size, not a protocol limit — and `TouchSequenceTest` pins that split, while `PointerCapTest` pins the two bounds staying in step.

### KEY_EVENT (0x10) -- Car -> VD server

Key event (e.g., media keys, navigation keys). Reserved for future use.

## Lifecycle Channel (localhost:19647)

Internal to the phone: the VD server reverse-connects to the phone app on `localhost:19647`. This is a separate small protocol, not the frame protocol above. Messages:

| Value | Direction | Meaning |
|-------|-----------|---------|
| `0x10` (`MSG_DISPLAY_READY`) | VD server -> Phone | VirtualDisplay is up and the VD server has bound 9638/9639. Payload: 4-byte `displayId`, then 1 flags byte (bit0 = direct touch injection). Triggers the phone to send `VD_PORTS_BOUND` to the car. |
| `0x11` (`MSG_STACK_EMPTY`) | VD server -> Phone | No activities on the VD after GO_BACK. No payload — length byte only. Triggers the phone to send `VD_STACK_EMPTY` to the car. |
| `0xFF` (`CMD_STOP`) | Phone -> VD server | Stop the VD server process. |

These values deliberately overlap `ControlMsg`'s numbering (0x10 = `LAUNCH_APP`, 0x11 = `GO_HOME`) and are a **separate numbering space**: the lifecycle channel exchanges raw bytes, not `FrameCodec` frames, and a future `ControlMsg` renumber must never be allowed to move this wire format. Both ends read the values from `VdLifecycle`.

The VD server uses `FrameCodec.readFrameBlocking(NioReader)` and `NioReader.readIntOrNullBlocking()` for its non-coroutine read loop. See [client.md](./client.md) for the orchestrator side.

## ADB Reference

The car-side RemoteAdbController uses `TcpAdbConnection` -- a persistent single-socket ADB-over-TCP client that keeps one socket open and multiplexes shell commands through it (unlike dadb, which opens a new connection per command). The phone-side CarAppInstaller still uses dadb for car APK install.

ADB protocol details (`AdbProtocol.java`):
- Header: 24 bytes, little-endian (command, arg0, arg1, dataLen, checksum, magic).
- Commands: `A_CNXN` (0x4e584e43), `A_AUTH` (0x48545541), `A_OPEN` (0x4e45504f), `A_OKAY` (0x59414b4f), `A_CLSE` (0x45534c43), `A_WRTE` (0x45545257).
- `A_VERSION = 0x01000000`.
- `MAX_PAYLOAD = 256 KB`. `TcpAdbConnection.negotiateMaxPayload` reads the peer's max out of the CNXN banner (little-endian `int` at offset 4) and uses `min(peerMax, 256KB)`; a missing/short banner, a `0` or a negative result falls back to `MAX_PAYLOAD` rather than stalling.
- `AUTH_TOKEN` (1) = device sends 20-byte token, `AUTH_SIGNATURE` (2) = host signs with SHA-1 DigestInfo + NONEwithRSA (prehashed, matching AOSP `RSA_sign(NID_sha1)`), `AUTH_RSAPUBLICKEY` (3) = host sends public key for user approval.
- Reply sequencing is fixed and shared: the first `AUTH_TOKEN` is answered with `AUTH_SIGNATURE`, a second one (signature rejected) with `AUTH_RSAPUBLICKEY`. `AdbCrypto` owns this for both transports.
- Public key: `ANDROID_PUBKEY` struct (modulus words, `n0inv`, little-endian modulus, `rr`, exponent), base64-encoded, then `" DiLinkAuto@car\0"` — NUL-terminated per the ADB spec (the TCP path's old unterminated form was a latent auth-acceptance bug). `AdbCrypto.encodePublicKey` uses `java.util.Base64` (standard alphabet, one `=` padding), which is byte-for-byte identical to Android's `NO_WRAP` output and assertable under the JVM test jar.
- Fingerprint: SHA-1 over the DER public key, first 4 bytes hex — the format `adb` itself prints. Both transports use SHA-1.
- Key storage: `TcpAdbConnection` writes PEM-format keys (`adbkey` / `adbkey.pub`) in its `keyDir` so dadb can read the same keys. The car passes an app-private directory (`filesDir/adb_keys`) so the shell-UID phone credential is not exposed to any app holding `READ_EXTERNAL_STORAGE`.

**Hardened read path.** Both the USB and TCP loops read through `TcpAdbConnection.readMessageFrom(InputStream)` / `AdbProtocol.parseHeader`, which rejects a header whose `magic != command ^ 0xFFFFFFFF` and any `data_len` that is negative or above `MAX_PAYLOAD` — an unvalidated `0x7FFFFFFF` made `ByteArray(dataLen)` throw `OutOfMemoryError` (an `Error`, so the callers' `catch (Exception)` never saw it). The payload CRC is now verified on every read, not merely parsed.

**The desktop ADB path is an external `adb.exe`.** The Windows receiver does not implement the ADB protocol itself: it runs `adb connect <phoneHost>:<adbPort>` and `adb -s <serial> shell <command>`, with the launch line coming from the same `VdDeploy.buildDeployPlan` (with `background = false`, so the ADB stream stays attached to the engine). That makes two `HandshakeResponse` fields peer-controlled inputs, so the desktop validates both:

- `adbPort` must be exactly `Ports.ADB_PORT` (the phone always sends it, so the whitelist costs no compatibility). Anything else aborts the session rather than silently connecting to a host:port of the peer's choosing.
- `vdServerJarPath` must be empty, equal to `VdDeploy.JAR_PATH`, or inside `VdDeploy.DIR_PATH` (and contain no `..`). `shellQuote` only stops a path from *escaping the command*; it cannot stop a peer from choosing *which* code runs on the device as shell UID, so the choice itself is whitelisted.
- `vdWidth` / `vdHeight` are clamped to `[2, 4096]` and floored to even via `DimAlign.evenMin2` (`DimAlign.even` maps negatives to ~2^31 positive, letting a hostile peer request an arbitrarily large device-side VirtualDisplay).

`adb` is also still used by the phone app (`CarAppInstaller` via dadb) to install the car APK, which is why `adbkey` / `adbkey.pub` stay in a dadb-readable format.

### VD Server Shell Commands (`VdDeploy`)

All VD server lifecycle commands are defined in `VdDeploy.kt` (protocol module) and shared across phone and car:

| Command | Description |
|---------|-------------|
| `killCommand` | Bare SIGTERM: `for p in $(pgrep -f "[P]ipelineServer"); do kill -TERM $p; done 2>/dev/null; exit 0`. **On ART this terminates the process immediately without running the JVM shutdown hook** (see `gracefulStopCommand`) — kept for manual debugging only, no longer used by the deploy sequence |
| `killCommandForce` | Force kill: **locate with `pgrep -f "[P]ipelineServer"`, then `kill -9` by pid** — `for p in $(pgrep -f "[P]ipelineServer"); do kill -9 $p; done 2>/dev/null; exit 0`. Not `pkill -9 -f`: on MI 9 (2026-10-10) the inline `pkill -f` also SIGKILLs the wrapper shell that is running it (the command text is in that shell's own cmdline, adb returns 137), which makes "did the kill hit anything" unreadable — and that is the last-resort path. Reusing `pgrep -f` also keeps identification and killing on one matching rule. SIGKILL is the only kill signal with well-defined semantics on ART |
| `gracefulStopCommand(token)` | Graceful stop request for **one session**: writes `stop-request-<token>` (fixed `stop-request` when the token is absent) as `p=<path>; tmp="$p.tmp.$$"; (cat vd-server.pid > "$tmp") \|\| touch "$tmp"; mv -f "$tmp" "$p"; exit 0` — same-directory temp + `mv`, so a concurrent reader never sees a truncated/empty file, and it returns immediately (no waiting: the tightest transport shell timeout is 5s). The engine's watchdog polls and **consumes** (deletes) the file, then sets `running=false` so the normal exit path runs the full `cleanup()`. External callers wait via `vdAwaitExit` and fall back to `killCommandForce` |
| `gracefulStopAnyCommand` | Stop **whatever engine is running** (pre-launch cleanup only): reads the token from `/sdcard/DiLinkAuto/vd-server.session` and writes that session's sentinel, falling back to the fixed `stop-request` when the file is missing/invalid. Safe **only** where no new engine has started yet (`vdRunDeploySequence`); reading the device from a session teardown is exactly the issue #180 regression |
| `probeCommand` | Liveness probe (output form): `if pgrep -f [P]ipelineServer >/dev/null 2>&1; then echo Y; else echo N; fi` — prints `Y` if alive, `N` if gone. **`pgrep -f`, not `pkill -0`** (2026-10-10): toybox 0.8.11-android rejects `pkill -0` with `bad -L '0'` (always rc=1 → probe always reported GONE, silently disabling the exit wait). `pgrep` and `pkill` share toybox's option table, so any device where the kill path works has a working probe |
| `probeExitCodeCommand` | Liveness probe (exit code form): `if pgrep -f [P]ipelineServer >/dev/null 2>&1; then exit 0; else exit 1; fi` — exit 0 = alive, exit 1 = gone. For callers that only see exit status (car's ADB `shell()` path, desktop's `AdbDeployer.probe()`). Also `pgrep` for the same reason as above |

**Stopping the engine (2026-10-10, measured on MI 9) — per-session sentinel since issue #180.** SIGTERM does **not** run the JVM shutdown hook on Android: ART's `app_process` treats a bare SIGTERM as an immediate, uncatchable terminate. MI 9 实测 `kill -TERM` → process gone within 1s, `PipelineServer.cleanup()` zero-executed (no recovery step in vd-server.log). `cleanup()` therefore only runs on the engine's **normal exit paths** (main-thread `run()` finally: socket loss, bind timeouts, watchdog). The stop protocol is a **file sentinel**, named per session (`stop-request-<token>`, token generated at deploy time and handed over in argv slot 11) so a previous session's late teardown can never land on the next engine — a fixed filename, and equally a pid *content* check, loses that race because the stop side reads the device at stop time, when the pid/token already belongs to the new engine. Engines launched **without** a token (old client / old scripts / e2e) fall back to the fixed `stop-request` with content-addressed pid and an mtime-vs-session-start rule. A **token** engine still accepts the fixed `stop-request`, but only when its content equals its own pid: the car ADB deploy path starts the engine on the car, so the phone never learns the token and its teardown can only write the fixed name (the command copies `vd-server.pid`). Empty/unparseable content is ignored there — an old client's late empty sentinel carries a fresh mtime, so honouring it would bring issue #180 back. The engine's watchdog consumes its sentinel and exits gracefully; only if that times out (`VdDeploySequence.GRACEFUL_EXIT_TIMEOUT_MS`, **12s**) does the caller force-kill. Startup cleanup deletes stale legacy sentinels (and, in token mode, other sessions' sentinel files) so a leftover request can never kill a fresh instance; a sentinel at *this* session's own path is kept (it can only come from the client that owns the token, i.e. a real stop during the init window). Engines publish `vd-server.pid` + `vd-server.session` atomically and remove them on cleanup only when they still carry their own value.

**Exit latency is ~6.1–6.3s, not 2–4s (2026-10-10, two independent measurements).** The earlier "cleanup takes 2–4s" estimate only covered the shell commands *inside* `cleanup()`; it missed the pipeline thread's bounded join, encoder/VD release, and the wait on the phone-side `ConnectionService` teardown grabbing the same display/rotation locks. Desktop-side probe log (`探针 ALIVE @+189ms` → `探针 GONE @+6123ms`) and an independent device-side poller (sentinel → pid gone ≈ 6.3s) agree. With the then-6s budget the wait missed by **130 ms on every reconnect**, so a `-9` was always issued right as `cleanup()` was finishing — truncating the tail of it (the `screen_off_timeout` restore). Both budgets are now 12s (≈2× the measured value).

**Process pattern (`[P]ipelineServer`) — what it does and does not protect (2026-10-10, MI 9).** A plain `PipelineServer` pattern is unusable: the command text lands in the invoking wrapper shell's own cmdline (`sh -c "... pgrep -f PipelineServer ..."`), so the lookup matches *itself* and every later command on that line is silently skipped. The bracket form is a **regex** that matches the engine's `com.dilinkauto.vdserver.PipelineServer` but not the literal `[P]ipelineServer` text in that wrapper. Measured limits:

- `pgrep -f "[P]ipelineServer"` — the trick **holds**: engine present → rc=0, absent → rc=1, no self-match. All probes are built on this.
- `pkill -f "[P]ipelineServer"` — **does not hold**: it also kills the wrapper running it (adb returns 137 before the next `echo`). The engine *is* signaled (reproduced twice), but the exit code stops being evidence. That is why the force kill uses `pgrep` + `kill <pid>` instead — identification and killing then share one matching rule.

### VD Server argv (`VdDeployArgs`)

`PipelineServer.main()` parses `W H DPI PHONE_HOST EW EH FPS [BITRATE] [CAR_HOST] [ALLOWED] [SESSION] [DPI_SOURCE]`:

| Slot | Source / clamping |
|------|-------------------|
| `W H` | VirtualDisplay dims. Phone-side: `VdDimensions.resolve` (car viewport, even-aligned, anti-crop scaled to the phone's physical long edge). Car-side: native car viewport |
| `DPI` | 单一来源（issue #202）：`VdDpiResolver` 依次取 用户 override（协议区间 120..480 **且不超过 `VideoConfig.maxSafeDpi`**）> 自动标定（`calculateOptimalDpi`）> 默认回落。越界/超上限不夹边界，回落自动值并打 W 日志 |
| `PHONE_HOST` | Host the VD server reverse-connects to on `LIFECYCLE_PORT` |
| `EW EH` | Encoder dims, proportionally scaled to fit 1920x1080, then floored to even |
| `FPS` | Target frame rate; all pipeline timeouts derive from it |
| `BITRATE` | bps, validated against `500_000..20_000_000`, out-of-range falls back to 4 Mbps |
| `CAR_HOST` | Trailing, optional. Dotted-quad IPv4 or hostname; blank or malformed degrades to `-` (= accept any peer) rather than failing the deploy. Pins the engine's 9638/9639 accepts to the receiver it serves |
| `ALLOWED` | issue #21: comma-joined package names (`[A-Za-z0-9._]{1,255}`) the user allows onto the car screen; `-` / empty / all-invalid = no allowlist filtering (car/desktop ADB deploys cannot read the phone prefs). Bounds `RecentsParser.pickQuickSwitchTarget` so a private app is never pulled into the VD |
| `SESSION` | issue #180: the session token generated at deploy time (`[A-Za-z0-9_-]{1,64}`, 32 hex chars from a UUID), also used to name this session's stop sentinel. `-` / invalid = legacy engine, listening on the fixed `stop-request` with pid content addressing + mtime fallback |
| `DPI_SOURCE` | issue #202: DPI 来源令牌（`auto` / `user-240` / `fallback-480-234`，`[A-Za-z0-9._-]{1,48}`），引擎只把它打进 `Starting:` 行；`-` / 缺省 = 旧部署端未上报（日志记 `unknown`） |

### Launch command line

`VdDeploy.commandLine(jarPath, logPath, args, background)` produces:

```
CLASSPATH='<jarPath>' <exec|setsid> app_process / com.dilinkauto.vdserver.PipelineServer <args> >>'<logPath>' 2>&1[ &]
```

- `background = false` (car USB/TCP-ADB paths) uses `exec`, so `app_process` replaces the caller's shell and stays attached to the ADB stream — the car's `TcpAdbConnection` has no stream-demux reader, and a closing stream kills the engine it just started.
- `background = true` (phone Shizuku path) uses `setsid ... &`, which is what actually detaches the engine from the parent `sh`'s process group; with plain `exec app_process ... &` Shizuku could reap it, leaving a 0-byte `vd-server.log`.
- Output is appended (`>>`), never truncated: a reconnect storm runs pkill + restart several times, and truncation lets the last (often short-lived) restart erase the first failure's diagnostics.
- **Both paths are single-quoted** (`VdDeploy.shellQuote`, embedded `'` escaped as `'\''`) because `jarPath` is peer-controlled — it arrives in `HandshakeResponse.vdServerJarPath` — and the command runs as shell UID. `logPath` is quoted too, so nobody can later wire it to a parameter and reintroduce the hole. The argv tail is numeric/host-shaped only, so it needs no quoting.

## Constants

```
PROTOCOL_VERSION      = 1
CONTROL_PORT          = 9637  (phone <-> car, handshake + heartbeat + commands + data)
VIDEO_PORT            = 9638  (VD server binds 0.0.0.0 -> car connects)
INPUT_PORT            = 9639  (VD server binds 0.0.0.0 -> car connects)
AUDIO_PORT            = 9640  (VD server binds 0.0.0.0 -> car connects, AAC-LC CONFIG + FRAME)
LIFECYCLE_PORT        = 19647 (VD server reverse-connects to phone localhost)
ADB_PORT              = 5555  (standard ADB TCP port)
TARGET_FPS            = 20
FRAME_INTERVAL_MS     = 1000 / TARGET_FPS  (~= 50 ms)
DEFAULT_BITRATE       = 4000000  (4 Mbps, CBR start bitrate)
ADAPTIVE_MIN_BITRATE  = 1500000  (runtime adaptive floor)
BITRATE range (UI)    = 1000000 .. 12000000
BITRATE range (argv)  = 500000 .. 20000000  (deliberately wider, for hand-edited configs)
HEARTBEAT_INTERVAL    = 3000 ms  (control connection only, injectable)
HEARTBEAT_TIMEOUT     = 10000 ms (control connection only, injectable)
CONNECT_TIMEOUT_MS    = 10000 ms (TCP connect deadline)
FLUSH_GRACE_MS        = 500 ms  (writer drain on a graceful peer close)
SOCKET_BUF_BYTES      = 524288   (512 KB requested send/receive buffer; explicit SO_SNDBUF = hard cap)
WRITE_QUEUE_CAPACITY  = 64       (frames, BufferOverflow.SUSPEND)
MAX_PAYLOAD_SIZE      = 16,777,216 bytes  (16 MB, FrameCodec)
HEADER_SIZE           = 6 bytes  (4 length + 1 channel + 1 type)
LARGE_FRAME_LOG_THRESHOLD = 8 MB
NIO_READ_UNIT_MAX     = HEADER_SIZE + MAX_PAYLOAD_SIZE  (reader grow ceiling)
NIO_BUFFER_CAPACITY   = 131072   (128 KB default read buffer)
BLACK_SCREEN_SUSTAIN_MS = 3000  (default; receivers override)
KEYFRAME_MAX_BYTES    = 2048     ("tiny" I-frame threshold for black detection)
TINY_KEYFRAME_STREAK  = 3        (alert-only threshold)
SERVICE_TYPE (mDNS)   = "_dilinkauto._tcp."
SERVICE_NAME (mDNS)   = "DiLink-Auto"
DISPLAY_MODE_MIRROR   = 0
DISPLAY_MODE_VIRTUAL  = 1
```

The single source of the port constants is `Ports` in `:protocol-core`; `Discovery` keeps deprecated aliases that forward to it. mDNS discovery ignores responders whose `serviceName` is not `SERVICE_NAME` and any resolved port outside `1..65535` — an impostor on the same LAN has no other gate.

VD server deploy constants (`VdDeploy`):

```
MAIN_CLASS      = "com.dilinkauto.vdserver.PipelineServer"
PROCESS_PATTERN = "[P]ipelineServer"
DIR_PATH        = "/sdcard/DiLinkAuto"
JAR_PATH        = "/sdcard/DiLinkAuto/vd-server.jar"
LOG_PATH        = "/sdcard/DiLinkAuto/vd-server.log"
MAX_ENCODE_WIDTH  = 1920
MAX_ENCODE_HEIGHT = 1080
DPI_OVERRIDE_MIN  = 120
DPI_OVERRIDE_MAX  = 480
CAR_HOST_ANY      = "-"
```

ADB constants (`AdbProtocol`):

```
HEADER_SIZE   = 24
A_VERSION     = 0x01000000
MAX_PAYLOAD   = 262144  (256 KB)
AUTH_TOKEN    = 1
AUTH_SIGNATURE = 2
AUTH_RSAPUBLICKEY = 3
```

能力位掩码（`HandshakeRequest.supportedFeatures` = 接收端想用什么；`HandshakeResponse` 尾字段同名字段 = 手机能采集什么）：

```
FEATURE_VIDEO          = 0x01
FEATURE_AUDIO          = 0x02
FEATURE_MEDIA_CONTROL  = 0x08
FEATURE_NAVIGATION     = 0x10
```

Connection methods (for `connectionMethod` in `HandshakeResponse`):

```
CONNECTION_METHOD_USB_ADB   = 0
CONNECTION_METHOD_WIFI_ADB  = 1
CONNECTION_METHOD_SHIZUKU   = 2
```

## Test-Locked Invariants

The cross-endpoint contracts above are pinned by unit tests so a "tidy-up" cannot silently move the wire. The full inventory lives in [IMPLEMENTATION_REPORT_TESTING.md](./IMPLEMENTATION_REPORT_TESTING.md); the protocol-relevant locks are:

| Invariant | Where it is locked |
|-----------|--------------------|
| ADB frame: `magic == command ^ 0xFFFFFFFF`, additive checksum, `MAX_PAYLOAD` ceiling accepted by `encode` but rejected by `parseHeader`, CRC verified on read | `protocol/.../adb/AdbProtocolTest`, `TcpAdbConnectionTest` |
| `AdbCrypto` primitives: `bigIntToLEPadded`, `SHA1_DIGEST_INFO`, NUL-terminated public-key string, SHA-1 fingerprint, `buildAuthReply` sequencing | `protocol/.../adb/AdbCryptoTest` |
| Handshake round-trips plus legacy-payload truncation for every trailing field (`dpiOverride`, `bitrate`, `vdAreaTier`, `vdWidth`/`vdHeight`) | `MessagesTest` |
| `VdDeploy` kill/stop/probe commands byte-for-byte, `[P]ipelineServer` pattern, `shellQuote`, the exact launch command line, `buildDeployPlan` | `VdDeployConstantsTest`, `VdDeployCommandLineTest` |
| `VdDeployArgs.format` layout, aspect-preserving clamp, bitrate bounds, `CAR_HOST` sanitisation, `coerceDpiOverride` | `VdDeployArgsFormatTest` |
| `H264NalParser.isKeyFrame` via `nal_type & 0x1F` on real-encoder `0x65` headers | `H264NalParserRefIdcTest` |
| `Connection` heartbeat auto-ack, watchdog kicking a silent peer, an active link not being killed, injected intervals | `ConnectionTest`, `ConnectionHeartbeatTest` |
| Truncated payloads, enum rejection, shell-metacharacter package names, version validation, 16-bit prefix guards | `AuditProtocolFixesTest`, `AdversarialDecodingTest` |
| `BlackScreenDetector` two-level reaction and injected clock | `BlackScreenDetectorTest` |
| Port / dimension / bitrate / DPI defaults resolve to the `:protocol-core` constants | per-module config tests |

## Byte Order

All multi-byte integers and floats in the DiLink-Auto frame protocol are **big-endian** (including the `NioReader` buffer). The ADB protocol (used on port 5555) is **little-endian** per the ADB specification — header fields, the peer's max-payload word in the CNXN banner, and the `ANDROID_PUBKEY` struct are all little-endian; the two are separate protocols on separate sockets.
