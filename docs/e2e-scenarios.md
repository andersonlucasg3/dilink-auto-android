# Dual-phone real-device E2E: scenario matrix and exploration strategy design

> Status: **landed and in real-device verification** (2026-10-17). P0 all green (S01~S04); P1 has run through S16 (S09~S16 being re-verified after fixes); P2 in progress. **The §1 role table was corrected per the 2026-10-16 measurements (the opposite of the initial assumption)**.
> Companion pieces: `scripts/e2e-dual-device.sh` (main orchestration) + `scripts/lib/{devices,ops,asserts}.sh` + `scripts/scenarios/s01-s22` + `scripts/lib/explore.py`; runbook `docs/e2e-dual-device-runbook.md` (red lines/troubleshooting §5/§6); desktop-side paradigm source `scripts/e2e-reconnect-cleanup.sh`.

## 1. Topology and roles

The engine (vd-server) **always runs on the sender machine itself (phone role)**; the receiver (car role) connects over adb to the sender's 5555 to deploy/start the engine, pulls H.264 video from the sender's **9638**, injects touch back to **9639**, and **9637** is the control port (verified in `protocol-core/.../Ports.kt`).

| Role | Device (measured 2026-10-16) | APK | Responsibility |
|---|---|---|---|
| Sender (phone, B) | **MEIZU 20 Inf** (wireless `192.168.3.206:5555`; PIN injected via `DILINK_TEST_PIN_PHONE`, **must never be committed to the repo**) | `com.dilinkauto.client` | Listens on 9637; runs the engine locally (VD + GlPipeline + encoding); wireless adb (**no USB transport**) |
| Receiver (car, A) | **MI 9** (USB `9e779dea`; PIN injected via `DILINK_TEST_PIN_CAR`) | `com.dilinkauto.server` | adb host connects to B:5555 to deploy the engine; pulls B:9638 and decodes it to screen; touch → B:9639 |
| Orchestrator | PC | — | adb installs APKs, unlocks, drives both UIs with `input`, screencap, logcat, pgrep polling, assertions |

> The initial assumption (MI 9 as sender) is the opposite of the measurements — the run actually follows the table above; `--phone`/`--car` are both mandatory and automatic device selection is forbidden.
> Both ends on the same WiFi (no AP isolation); B's wireless adb authorization dialog appears the first time and is clicked automatically by the PC side with `input tap`; the authorization persists afterwards.
> **Wireless-topology consequence**: as soon as B's WiFi drops, the PC→B adb link drops too (offline) → "network-cutting ops" must first pre-stage a nohup self-recovery job on B (see the `op_wifi_off`/`op_airplane` comments in ops.sh; `svc wifi` does not take effect on MEIZU Android 15 — `cmd wifi` is required).

## 2. Test strategy layers

Exhaustively enumerating every edge case is impossible; combinatorial coverage is not. Three progressive layers, sharing the same invariant set from §5:

| Layer | Form | Purpose | Carrier |
|---|---|---|---|
| L1 | Deterministic scenario matrix | Freeze the known regression surface; every scenario can be re-run independently | `scripts/scenarios/*.sh` |
| L2 | Model-driven (state-machine traversal) | From every state, run all permutations of short operation sequences, specifically hunting races | `scripts/lib/explore.py` |
| L3 | Random fuzzing + fault injection | Unattended overnight long runs to hit unknown edge cases | `explore.py --fuzz` |

Any invariant violation in L3 → freeze the scene → replay the operation sequence and solidify it as a new L1 scenario (the regression case for the bug fix).

## 3. Session state machine model (L2 skeleton)

### 3.1 State enumerations (verified in code)

- Car side `CarConnectionService.State`: `IDLE → CONNECTING → CONNECTED → STREAMING` (inside CONNECTING there are two parallel tracks: the adb deployment track + the control/lifecycle track; the first video frame arriving → STREAMING)
- Phone side `ConnectionService.State`: `IDLE → WAITING → CONNECTED → STREAMING` (WAITING = listening on 9637)

### 3.2 Composite state (the vector the model actually tracks)

`(car_state, phone_state, engine, vd, sentinel)`, where engine/vd ∈ {absent, present} and sentinel ∈ {off, on}. Legal converged states:

| Composite state | Meaning |
|---|---|
| (IDLE, IDLE, absent, absent, off) | Neutral state (target of every round's setup/restore) |
| (STREAMING, STREAMING, present, present, on) | Healthy session |
| (CONNECTING, WAITING, *, *, *) | Deploying/handshaking (allowed intermediate state, with a timeout budget) |
| (IDLE, WAITING, absent, absent, off) | Session-end convergence (target of the teardown assertion) |

### 3.3 Operation set O

Each op = a deterministic command sequence; after execution there is a settle window (15s by default, 30s for teardown-type ops):

| op | Executing side | Command summary |
|---|---|---|
| `connect` | A | Enter B's IP in the UI → connect (uiautomator locates the button + `input tap/text`) |
| `disconnect` | A | Disconnect in the UI |
| `rotate` | B | `settings put system user_rotation` + trigger a re-layout (toggle landscape/portrait) |
| `wifi_off` / `wifi_on` | B | `svc wifi disable/enable` |
| `airplane` | B | `settings put global airplane_mode_on` + broadcast |
| `kill_engine` | B | `pgrep -f "[P]ipelineServer"` → `kill -9` |
| `kill_client` | B | `am force-stop com.dilinkauto.client` |
| `kill_server` | A | `am force-stop com.dilinkauto.server` |
| `reinstall` | B | PC side `adb install -r app-client-debug.apk` |
| `screen_off` / `screen_on` | B | `cmd display power-off 0` / `power-reset 0` |
| `switch_app` | A/B | Launch another app on the VD through the app UI |
| `ime_open` | A | Tap open an input field on the VD (keyboard integrity scenario) |
| `usb_pull` | PC | Unplug B's USB (physical action, left as a manual switch) |
| `adb_revoke` | B | Revoke USB debugging authorization in settings |

### 3.4 Traversal rules

- Each state keeps only a **subset of meaningful ops** (e.g. in IDLE only `connect`/`reinstall`/`adb_revoke` run), pruning meaningless combinations.
- depth-2 full permutations (25~64 sequences per state); depth-3 sampled at 10%.
- Each round: `setup(neutral state) → op1 → settle + intermediate assertions → op2 → settle → full invariant set → restore(neutral state)`.
- Per-round budget ≤ 90s; sequence logs are saved after each full round for failure replay.
- Abort conditions: 3 consecutive rounds failing on the same op → pause and report; either end going offline → abort the whole round.

## 4. L1 scenario library

Standard structure: `setup → ops → asserts → restore` (restore is identical to setup, guaranteeing no residue between scenarios).

### P0 minimum closed loop (no other scenarios run until this is fully green)

| ID | Scenario | Operation sequence | Assertions |
|---|---|---|---|
| S01 | Baseline stream setup | connect (`reach_streaming` has a built-in `wait_stream_settled`: wait ~40s after stream setup for the false black-screen report storm to go silent — a legitimately black empty VD home screen is misreported by BlackScreenDetector, self-healing after 2 rebuilds, verified 2026-10-17 try2) | Both ends STREAMING; B's `dumpsys display` contains DiLinkAutoVD and the real size = the handshake vdWidth/vdHeight; **A's screen (decode/render surface) not being pure black in a screencap is the primary criterion**, a non-pure-black VD screenshot is auxiliary observation (an empty VD launcher is constantly black by nature, verified 2026-10-16 v5); vd-server.log contains `Video on :9638` |
| S02 | Touch feedback | S01 + **first tap an app tile to enter the mirror surface** (dump the A UI → first tile in the top grid area → tap → wait for evidence `CarShell: launchApp clicked` in A's logcat; CarShell starts on the HOME app grid, the mirror surface is only visible after an app is opened, and in Home state the SurfaceView is INVISIBLE and does not receive touch — the 2026-10-17 try2 root cause; this precondition is **handled softly**, failing it does not directly fail the scenario) + tap three points on A (25/50/75% × 55%, coordinates computed on the fly) | **A-side `Touch #N action=` relay lines appear in B's client.log** (the first 5 touches from CarTouchSender are relayed via 9637; the old criterion `InputManager/inject`, which always passed, was proven obsolete on 2026-10-16) + no `Touch DROP`; the stream is not interrupted (both ends still STREAMING); A's screen is not pure black |
| S03 | Graceful teardown | S01 + disconnect | The engine exits gracefully (within 6.1~6.3s) with no "forced kill"; screen_off_timeout restored to baseline; 9637 released; `pgrep` shows zero engines |
| S04 | Rotation re-handshake | S01 + rotate | Old VD teardown → new handshake → back to STREAMING; new VD size correct |

### P1 core regressions

| ID | Scenario | Operation sequence | Assertions |
|---|---|---|---|
| S05 | WiFi down for 5s | S01 + wifi_off + sleep5 + wifi_on | Automatically reconnects back to STREAMING, no forced kill. **Wireless topology adaptation** (2026-10-17): before cutting, pre-stage a nohup self-recovery job on B (8s/25s), relax the assertion budget to 150s, and after recovery run `wait_stream_settled` before asserting |
| S06 | Airplane mode | S01 + airplane ×2 | Reconnects after recovery, or stays in a re-connectable state by design. Same wireless adaptation as S05 (150s probe window + storm silence) |
| S07 | Unplug sender USB | S01 + usb_pull | The session keeps STREAMING (A↔B goes over WiFi, independent of the PC) |
| S08 | Receiver rapid re-connect | S01 + disconnect + connect quickly ×2 | No duplicate engine, no fight over 9637, finally converges to STREAMING. **The reconnect primitive = restarting A's app** (the manual-connect UI is unreachable; force-stop + monkey triggers the launch path's automatic connect, micro-verified 2026-10-17). Reconnecting produces a new storm → `wait_stream_settled` (150s) is mandatory before asserting |
| S09 | Kill client mid-session | S01 + kill_client | Teardown cleanup fully passes (same assertion set as S03) |
| S10 | Kill server mid-session | S01 + kill_server | B-side state falls back to WAITING/IDLE; the engine exits; no residue |
| S11 | Kill engine process | S01 + kill_engine | Detected and recovered, or cleanly degraded, per design; no silent hang |
| S12 | Reinstall sender APK | S01 + reinstall | After the START_STICKY restart (~18s) a stream can be established again |
| S13 | Screen off/on | S01 + screen_off + screen_on | Pause/resume per design; no `tex=0` static state (black-screen regression signal). **Criterion = A's screen (decode/render surface) is not pure black** — B's physical screen is turned off by the engine during the session and is constantly black, so it cannot be used (verified 2026-10-17); accounting must go through ok/bad (bare `\|\| rc=1` is forbidden) |
| S14 | IME integrity | S01 + ime_open | With VD ≥ 1368 the keyboard rows are complete; after exit the IME is restored. **On real devices the navigation bar Home content-desc is the Chinese 「主页」** (English "Home" only in the mock fixture) — bilingual location |
| S15 | Switch apps on the VD | S01 + switch_app | The target app goes on screen, no ANR. Tile parsing = `_grid_tile_bounds` (the real-device dump is single-line XML + the parent node's text is an empty string, so the old per-line grep always failed); on parse failure fall back to proportional coordinates (x≈16%+24%×(n-1), y≈11%) |
| S16 | Recents | S01 + open the recents UI | The RecentsParser path works and the target app goes to the foreground. **Product gap**: A's UI has no Recents entry (verified in source) — on a hit, assert the picture changed; on a miss, take the fallback path and skip the picture criterion (conditional, not counted as a failure) |

### P2 extensions (after P1 is fully green)

| ID | Scenario | Notes |
|---|---|---|
| S17 | Frame rate/bitrate switching | If the phone-side UI exposes the setting; otherwise move it to the desktop E2E. **Measured (2026-10-17)**: neither end's UI has an entry during a session (the setting is only exposed on A's launch screen `CarLaunchScreen.kt`, and mid-session it is replaced by the CarShell streaming UI) → SKIPPED(env), no product assertions executed |
| S18 | Incoming call/notification | Needs a physical SIM or `adb` simulation, with large ROM variance; marked as skippable |
| S19 | 20-round session loop | Engine RSS/handles show no monotonic growth (threshold set on the first run; better missing than a false green); vd-server.log has no abnormal stacks; the last round tears down cleanly. **2026-10-17 correction**: the per-round reconnect primitive = the `reconnect` op (restart A's app — after Eject, userDisconnected is persisted and does not auto-reconnect, and the manual box is unreachable, verified on the first run). **The sixth round exposed 2 product defects, both fixed**: ① `screen_off_timeout` lost its restore on the kill path (original value persisted to `TimeoutSnapshotFile` + corrected by `effectiveSnapshot`); ② disconnect Broken pipe logged a full stack (after the `isPeerDisconnect` check it is no longer logged) |
| S20 | Simultaneous reconnect race on both ends | A and B trigger reconnection within 2s: `kill_client &` (B tears down) + `reconnect &` (A restarts and reconnects) concurrently, then after wait, add B-side recovery (monkey launches the client into WAITING — it does not auto-start after force-stop, the missing half of the original scenario); `wait_stream_settled` before asserting to prevent false judgments from the reconnect storm |
| S21 | 9637 occupied | Outside a session, another process occupies 9637 → stream setup should fail by design and report a clear state (**@expect: fail** — the direction is flipped: the main operation should fail). **Sixth-round corrections ×2**: ① `op_occupy_9637` changed to `nc -Lp` (`-l` exits after a single connection → A's first connection consumed the occupier → the root cause of the first run's UNEXPECTED-PASS; `-L` keeps listening, and on a real device it was still LISTENING after two smoke-test connections); ② the main operation changed to the `reconnect` primitive (batch C evidence: when 9637 is occupied, A's failed attempt produces no client.log status line → `car_state=UNKNOWN` → the original `op_connect` fell back to the manual UI, and ManualConnectBox is unreachable on real devices → misfired as an "ops failed" FAIL; `reconnect` always returns rc 0 with clean semantics, leaving the failure direction to the assertion layer's "not converged" judgment) |
| S22 | Revoke debugging authorization | Reconnect after adb_revoke → re-authorization is prompted per design, no silent failure |
| S23 | Audio mirroring (including continued frame output after screen-off) | S01 + screen_off + sleep10 + screen_on. Five audio criteria: ① vd frame count >0 and growing within the window + a rate floor (not strictly linear) ② playback side decoded>0 / starved==0 / underrun not growing ③ silence padding frame count >0 (downgraded to an observation item) ④ frames still growing within 10s of screen-off (R7) ⑤ the video track survives when audio is degraded. **Coverage**: catches transport/decode-chain breakage and "no frames at all"; **does not catch source-side silence** (missing startRecording, requires manual listening sign-off). Refs #13/#79 |
| S25 | 4 consecutive VD switches (4~5 apps) | S01 + `switch_app` ×4 (tiles 1~4: bilibili/Xiaohongshu/Fanqie/Baidu Maps; the initial app may already be one of them). Criteria: ① `[Window]` ≥4 lines, last line `n≥4`, top layer `visReq=true`, old apps `visReq=false` ≥3; ② all 4 switched package names appear in `[Window]`; ③ all `[Pipeline]` stat lines have `swapFails=0 sendDrops=0 glErr=0` (`q` may momentarily be 1) and no `tex=0`, with the last `tex` > the first; ④ no `[BLACK] decode still alive`. **Does not cover** the real long-video/1-hour-navigation cases of #55/#58. Refs #15/#106 |

## 5. Invariant monitoring spec (checked every round)

| Invariant | Collection command | Pass criterion |
|---|---|---|
| Engine process | B: `pgrep -f "[P]ipelineServer"` | Empty after teardown; exactly 1 during a session |
| 9637 listening | B: `ss -ltn \| grep :9637` | Present during a session; absent after teardown |
| Video stream alive | vd-server.log frame count / `Video on :9638` | Continuously growing during a session |
| VD lit up | B: `dumpsys display` (DiLinkAutoVD real) + A-screen screencap | real size = the value sent in the handshake; **A screen (decode/render surface) pixel variance > 0** (primary criterion). Capturing the VD directly requires the large SF DisplayId (the `Virtual Display <id>` section of `dumpsys SurfaceFlinger`; passing the logical id reports Status:-2), and an empty launcher is constantly black — auxiliary observation only |
| Black-screen regression | vd-server.log GlPipeline frame log | No `tex=0` or stalling at a small value |
| ANR / crash | Both ends' logcat scan `ANR in\|FATAL EXCEPTION\|Force Closed` | 0 hits |
| Screen timeout sentinel | B: `settings get system screen_off_timeout` | 2147483647 during a session; baseline restored (300000) after teardown |
| Graceful exit | vd-server.log exit duration + the "forced kill" string | Converges within the 12s budget; any occurrence of "forced kill" is a regression |
| Session state consistency | A's UI state vs B's FileLog state | Converges to the same composite state (§3.2) |

> Note: assertion strings are based on the **measured log surface** of both ends. Strings such as `引擎已优雅退出`/`探针 GONE @+` have so far only appeared in the desktop-side desktop.log; the equivalent strings for A (app-server) and B (FileLog) must be discovered in P0.5 and then frozen — desktop-side strings must not be copied over.

## 6. L3 fault injection catalog

`kill -9` on a random process (engine/client/server), `svc wifi`, airplane mode, random `screen_off/on` insertion, `reinstall` mid-session. Weighting: normal ops 70% / fault ops 30%. Every 5 ops run lightweight invariants (engine/port/stream alive), every 20 ops run the full set. Physical USB plug/unplug cannot be fully software-driven and is left as a manual switch.

## 7. Infrastructure gaps and landing order

| Phase | Content | Output |
|---|---|---|
| P0 | Fix the automatic serial selection in `e2e-reconnect-cleanup.sh:109-113` (with two devices plugged in it picks the wrong one, so the PIN is entered on the wrong machine); commit `devices.sh`; add `screencap_car` | A usable two-device foundation |
| P0.5 | Log-surface survey: B's FileLog path and key lines, A's logcat tags, A's connect-UI widget locations (uiautomator) | Assertion string list |
| P1 | `scripts/lib/asserts.sh` (invariant library) + `scripts/e2e-dual-device.sh` main orchestration + S01~S04 | Two-device minimum closed loop passing |
| P2 | Complete S05~S16, each scenario independently re-runnable | Core regression net |
| P3 | `scripts/lib/explore.py`: L2 state-machine traversal | Race hunter |
| P4 | L3 fuzz + overnight long-run stability + triage solidification loop | Unattended |

Directory plan: `scripts/lib/asserts.sh`, `scripts/scenarios/*.sh` (one file per scenario, sourcing asserts.sh), `scripts/lib/explore.py` (python orchestrating the bash scenarios, managing sequences/budgets/scene freezing).

## 8. Risks and ROM differences

- **Unlocking**: on MIUI a direct PIN entry works; Flyme needs a swipe-up + tap to focus (devices.sh already has both paths). `input text` failures are silent → a hard assertion on `isKeyguardShowing=false` is mandatory; no retry loops (failsafe: 5 failures lock for 30 min).
- **VD lighting varies by model**: measured with the **MEIZU 20 Inf as sender (B)**; VD lighting was proven by S01 going fully green (2026-10-16); MI 9 as sender is unverified and must be validated separately before being enabled.
- **Android 15 BAL**: on the MEIZU 20 Inf the background-activity-launch path is restricted (known observation: `PhoneDisplayRestorer` Layer 2 has no effect) — scenarios involving "background launch" should adjust expectations to the known limitations when A runs Android 15; this does not count as a new bug.
- **A's UI driving** relies on a uiautomator dump to find the connect button; ROM differences require calibrating coordinates/widget ids in P0.5.
- **Local fork/exec ~1s**: all polling budgets are based on real elapsed time (`SECONDS`); "iteration count × sleep" is forbidden.
- **An idle PC mouse during the run** only constrains the desktop E2E; the dual-device approach goes entirely through `adb shell input` and can run unattended — that is its core operational advantage over the desktop E2E.
