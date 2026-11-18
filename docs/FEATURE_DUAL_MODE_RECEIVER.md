# Feature: receiver "mode" button (car-side reverse control / proxy relay) + automatic VD→screencast degradation

Status: **design + testable core landed**; the upper-layer UI/protocol/capture wiring and real-device verification are listed as TODO (see §5).

## 1. Requirements (summarized from the user's original description)
Add a **"mode"** button to the receiving page of the "receiver server" that switches between two forms:
1. **Emulate the car head unit · reverse-control the phone**: mirror the peer onto this end and have this end reverse-control the peer, "let me act as the car head unit" — goes through **vd-server mode**.
2. **The receiver acts as a "proxy server"**: the receiving end acts as a relay and **forwards** the "phone screencast" to the other end (one tap to cast the car head unit to the phone).

Details: VD mode is usable, but **many phone brands cannot get the VirtualDisplay picture to light up**, so the capture side must **automatically degrade** to screencast mode: `projection mode → screen-off screencast → main-screen screencast`.

## 2. Code survey conclusions (current state)
- The existing pipeline is **one-directional**: `phone capture → H.264 encode → receiver (car app-server / desktop app-desktop) decode + touch re-injection`.
- Already available: vd-server capture (`vd-server/VirtualDisplayCreator`+`PipelineServer`), phone-side `VirtualDisplayClient`, car-side `CarConnectionService`, desktop `DesktopConnectionService`, ADB/Shizuku deployment.
- **Not present**: proxy/relay forwarding, reverse control, `MediaProjection` screencast capture, device-brand VD compatibility allowlist. These are what this feature must add.

## 3. Design
### 3.1 The two user-facing "modes" (receiver-side state)
```
enum class ReceiverMode {
    REVERSE_CONTROL,   // emulate the car head unit · reverse-control the peer (vd-server mode)
    PROXY_RELAY,       // this end acts as a proxy server, forwarding the peer's screencast
}
```
- **REVERSE_CONTROL**: this end triggers the peer (or a peer managed by this end) to start vd-server; the peer's VD picture is encoded and sent to this end; this end re-injects touch = reverse control of the peer.
- **PROXY_RELAY**: this end acts as a relay and forwards one end's (source) video/input/lifecycle streams as-is to the other end (destination). Source → this end → destination; this end only forwards and does not decode (low-latency relay).

### 3.2 Capture-side degradation ladder (implemented this round, platform-independent)
`protocol-core/ScreenMode.kt` + `CaptureModePolicy`:
- VD preferred (`VIRTUAL_DISPLAY`); when the device cannot light up VD, `preferred(vdLightingSupported=false)=PROJECTION_SCREEN_OFF`.
- Runtime failures degrade step by step: `VIRTUAL_DISPLAY → PROJECTION_SCREEN_OFF(screen-off screencast) → PROJECTION_MAIN(main-screen screencast) → (none)`.
- `ladder()` is for the UI to show "what will be tried in order".
- The unit test `CaptureModePolicyTest` (6 cases) already locks the contract.

## 4. Exact wiring points (TODO, by module)
| Module | File/class | Change |
|---|---|---|
| `protocol-core` | New `ReceiverMode`, degradation result frame `DataMsg.SET_CAPTURE_MODE`/`ack` | Let both ends agree on the "current capture mode" (handshake or control frame) |
| `app-client` (phone: VD/screencast source) | `service/ConnectionService`, `display/VirtualDisplayClient`, `service/PhoneDisplayRestorer` | Choose vd-server capture or a newly added `MediaProjection` capture path based on `CaptureModePolicy`; VD first-frame timeout → `fallbackFrom` |
| `vd-server` (running on the peer) | `PipelineServer`/`VirtualDisplayCreator` | Expose signals for "whether VD lit up / whether a first frame was produced", and report on failure to trigger degradation |
| `app-server` / `app-desktop` (receiver) | `CarLaunchScreen` (car receive page) / `DesktopWindow` (desktop receive page) | Add the **"mode" button** + `ReceiverMode` state; PROXY_RELAY uses the new relay channel |
| Device compatibility | New `VdBrandBlacklist` (or Build.MODEL rules) fed into `preferred()` | Skip VD directly on a matching brand/model |

## 5. Delivered this round vs TODO
**Delivered (verified on the local machine)**
- `protocol-core`: `ScreenMode` + `CaptureModePolicy` (VD preferred + screen-off→main-screen degradation ladder) + `CaptureModePolicyTest`(6).
- Repo-wide test coverage completion: 18 new test files / 139 cases all green (see `docs/IMPLEMENTATION_REPORT_TESTING.md`). §5 of the same report also records the later "refactoring for testability" seams (ADB frames/crypto, injectable `Connection` heartbeat, `AssetSource`, `CarInstaller`, reconnect backoff/icon budget pure functions, roughly +44 cases, 183 in total, all behavior unchanged).

**TODO (needs one brainstorm to settle the UX + real-device verification; blindly changing the main connection path is not recommended)**
1. Settle which end the "receiver server" actually is (`app-desktop` is pure JVM and cannot do VD/screencast; the VD/screencast degradation must live on the Android receiving end) and the UX copy/entry points for the two modes.
2. New `MediaProjection` capture channel (screen-off screencast/main-screen screencast) implementation + foreground service permission.
3. The PROXY_RELAY forwarding protocol (relaying without decoding) and session negotiation between both ends.
4. The criterion for "whether VD lit up" + per-brand degradation verification (needs a real-device matrix).
5. The "mode" button UI + `ReceiverMode` state machine wired into the existing `CarLaunchScreen` / `DesktopWindow` without breaking the existing connection/deployment main line.

## 6. Risk notes
- Both proxy forwarding and reverse control touch the existing connection/deployment main line; making blind changes without real-device verification is high risk. Recommended: follow §4 module by module, anchor on pure policies such as `CaptureModePolicy`, wire in block by block, and pair each block with regression tests.
- The device-brand VD compatibility allowlist needs calibration on a real-device matrix; for now, use the explicit switch `preferred(vdLightingSupported=false)` as a stopgap.
