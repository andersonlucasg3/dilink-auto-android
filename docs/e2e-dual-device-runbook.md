# Dual-phone real-device E2E runbook (live run tomorrow morning)

> Status: **real-device verification complete** (2026-10-17) — P0 all green (S01~S04, try3); P1 all green
> (S05~S16: S05 367s / S06 289s, the wireless network-cut scenarios passed on their last verification round); P2 all green (S17
> SKIPPED(env) no settings entry during a session / S18 passed / S19 20-round loop passed / S20 simultaneous reconnect
> race on both ends passed / S21 @expect:fail achieved / S22 manual not run). **Harness-side failure root causes**
> (false black-screen report storm, manual-connect UI unreachable on real devices, frame counter reset/pause, no auto-reconnect after Eject,
> insufficient op_connect convergence window, etc.) have each been fixed and re-verified. **Three new product-side findings, all fixed
> and re-verified on real devices**: ① the `screen_off_timeout` snapshot lost its original value after the session was killed (the next round read back
> the sentinel 2147483647 and fell back to the hardcoded 60000 → the user's original value was permanently lowered); fix = persist the original value cross-process
> to `/sdcard/DiLinkAuto/screen-timeout.snapshot` + `effectiveSnapshot()` restoring from the file
> first (S19 batch C re-verification: reads the sentinel → `put 300000`, zero fallback); ② on disconnect
> `PipelineServer`'s fallback catch logged a full `Broken pipe` stack, polluting the log; fix =
> `isPeerDisconnect()` logs a single-line message for disconnect classes (S19 batch C re-verification: no "Exception" in vdlog);
> ③ **permanent reuse of a TCP ADB half-open socket** (after a brief WiFi disconnection A's `isConnected` stayed true
> → deployment failed after 150ms, failures 6→9+ with infinite retries); fix = `RemoteAdbController`
> marks itself disconnected on command failure (-1) + `resetAdbController()` forces a rebuild (S05 three-run re-verification: 367s
> PASS, auto-reconnect back to STREAMING after the network cut). S16 having no Recents entry and S17 having no in-session settings
> entry are both known product gaps, handled with design fallbacks.
> Upstream documents: `.plan/e2e-dual-device-impl.md` (overall implementation plan), `.plan/e2e-harness-design.md` (harness contract), `.plan/e2e-risk-assessment.md` (risks and red lines).
> This runbook is the only document needed for the live run: one command + a safety checklist + failure-troubleshooting entry points.

## 0. Topology at a glance (30-second version)

> **Roles are specified explicitly via `--phone/--car` and are unrelated to the device model.** Current live assignment (measured 2026-10-16, **the opposite of the initial assumption**):

| Role | Device | app | Connection |
|---|---|---|---|
| **B = sender (phone)** | MEIZU 20 Inf (Flyme/Android 15, **wireless** `192.168.3.206:5555`) | app-client | listens on 9637 waiting for the car; runs the vd-server engine locally; H.264 comes out on 9638 |
| **A = receiver (car)** | MI 9 (AOSP 15, **USB** `9e779dea`) | app-server | connects over adb to B:5555 to deploy the engine; pulls B:9638 video; touch re-injected to B:9639 |

- Both ends on the same WiFi and subnet; on the PC side, A goes over USB and B over wireless TCP.
- **B's wireless adb mode**: after a one-time `adb tcpip 5555` it stays stable long-term; it only needs re-setting after a phone reboot / re-enabling wireless debugging (in that case, first `adb connect` using the **dynamic port** shown in the system's "Wireless debugging", then `adb tcpip 5555`). Flyme resets the USB debugging authorization: when stuck at `authorizing`, tap "Allow" once on the phone.
- **Unlocking A must be manual** (measured 2026-10-16): this ROM's lock screen systematically ignores injected touch (30+ swipe/motionevent/sendevent combinations all ineffective, SELinux blocks sendevent) → before the run, swipe up and enter the PIN manually once; afterwards the script skips idempotently (`isKeyguardShowing=false`).
- The log assertion surface is on **B**: `/sdcard/DiLinkAuto/client.log` (A's logs are relayed in over TCP, tag=`CarLog`) + `vd-server.log`.
- **During a session 9637 only shows ESTABLISHED** (LISTEN is transient in the WAITING state) — assert on ESTABLISHED, not on LISTEN.

## 0.5 One-command entry point (recommended from 2026-10-17)

Three suites, one command, automatic verdict, **no need to watch the screen manually**:

```bash
bash scripts/e2e.sh dual       # car + phone (A=app-server / B=app-client)
bash scripts/e2e.sh desktop    # PC + phone (app-desktop + app-client)
bash scripts/e2e.sh all        # run both, produce a unified verdict
```

What it automates for you:

| Stage | Automated behavior |
|---|---|
| Role determination | Package name (`com.dilinkauto.server`→A, `com.dilinkauto.client`→B) → transport-type fallback (TCP→B, USB→A) → only when neither holds does it require `--phone/--car`. **No guessing** (a wrong guess enters the PIN on the wrong machine and counts against the failsafe). |
| B offline | `adb mdns services` discovers `_adb._tcp` / `_adb-tls-connect._tcp` → `adb connect` self-heals (the common case where wireless debugging is on but the PC side is not connected). |
| PIN | Environment variable first, then `.e2e.local.env` (machine-local, already ignored by `.gitignore`; **if it is tracked by git, the script refuses to run** — red line 8). |
| Failure handling | A scenario failure does not abort the run (freeze evidence first, then restore); a scenario that fails on the first pass is **automatically re-run once**, and the verdict distinguishes `FAIL` / `FLAKY` (passed on re-run). |
| Verdict | `debug-logs/e2e/run-<TS>/verdict.md` (human-readable table) + `verdict.json` (machine-readable) + exit code `0` all green / `1` failures / `2` environment or precondition problem. |
| Artifact layout | Everything from one run lives in a single directory: `<run>/dual/` (summary.txt, per-scenario files, freeze-*), `<run>/desktop/` (desktop.log, vd-server.log), `<run>/dual-console.log`. |

Time scale (measured 2026-10-17): `dual --scenario S01` ≈ 8.6 minutes (including ×2 false black-screen report storms on the first stream setup); full `dual` 22 scenarios ≈ **2.5~3.5 hours** (the S19 twenty-round loop alone is ~26 minutes); `desktop` ≈ 4~5 minutes; `all` ≈ 3~4 hours. For a full run, set aside half a day and keep the computer idle (the desktop phase moves the mouse).

> **Desktop black-screen self-healing (fixed 2026-10-17)**: when the VD content itself is black (empty launcher / phone screen off), the encoder keeps emitting extremely small I-frames, so the old criterion inevitably misfired "suspected persistent black screen → auto-reconnect", burning 2 extra rounds (~40s) per session. `BlackScreenDetector` now has a `decodingAliveCheck` gate: **black + decoding still producing frames = the content is black, do not escalate**; **black + decoding stalled = pipeline fault, only then escalate** (a 2s window determines a stall). Wired on the desktop end, not wired on the car end (behavior unchanged). The suite side still keeps the 15s observation window and the "1 + number of new self-heals" reconciliation as a backstop, and the log records 「其中黑屏自愈 ×N」 (of which black-screen self-heals ×N). Re-verification: on the MEIZU, `疑似持续黑屏` ("suspected persistent black screen") log lines went from 6 → **0**, graceful stops from 4 → **2**.

Common variants:

```bash
bash scripts/e2e.sh dual --scenario S05,S06 --skip-build   # run only the specified scenarios
bash scripts/e2e.sh all  --yes                             # unattended (desktop skips the countdown)
bash scripts/e2e.sh dual --retry 0                         # disable failure re-runs
bash scripts/e2e.sh all  --dry-run                         # device discovery + print the plan only
bash scripts/e2e.sh --list                                 # list suites and scenarios
```

> The desktop suite **moves the real mouse** and clicks windows, so the computer must be idle while it runs; `--yes` only skips the 10-second countdown and does not change this.
> The old entry points `scripts/e2e-dual-device.sh` / `scripts/e2e-reconnect-cleanup.sh` still work — the unified entry is just an orchestration layer over them, with identical argument semantics.

## 1. One command for tomorrow morning

```bash
export DILINK_TEST_PIN_PHONE=<unlock PIN of B (MEIZU 20 Inf)>
export DILINK_TEST_PIN_CAR=<unlock PIN of A (MI 9)>
bash scripts/e2e-dual-device.sh --phone 192.168.3.206:5555 --car 9e779dea \
     --skip-build --scenario S01
```

- `--phone` = B (sender) adb serial (the `IP:5555` form for wireless), `--car` = A (receiver) adb serial, **mandatory, do not let it auto-pick** (with two devices registered it picks the wrong one → PIN entered on the wrong machine → failsafe counted).
- Before running, confirm **B is online and authorized** (in `adb devices`, B shows `device`, not `authorizing`); A's lock screen has been unlocked manually.
- The PIN is read only from the environment variable or `.e2e.local.env` (machine-local, git-ignored); **it never goes into command-line arguments and never lands in any tracked file** (`.plan/` and `scripts/` are entirely in git).
- The APKs were built the night before; if unsure, drop `--skip-build` (it first runs `./gradlew test` + both APKs + dex symbol validation, a few minutes).
- **Get S01 passing on its own before chaining runs** (verify the foundation first): `--scenario S01` → all green → `--scenario P0`.

### Phased go-ahead (suggested order for the day)

| Phase | Command | Content | Abort condition (stop immediately on a hit, switch to manual) |
|---|---|---|---|
| P0 | `--scenario P0` | S01~S04 stream setup/touch/teardown/rotation | Any scenario fails to unlock; 2 consecutive scenarios fail the neutral-state check after restore; either end goes offline; the watchdog fires ≥2 times |
| P0.5 | Manual | Use the frozen P0 artifacts to calibrate A's UI widget ids / authorization dialog shape | — |
| P1 | `--scenario P1` | S05~S16 (WiFi/airplane/kill app/kill engine/reinstall/lock screen/IME/switch app) | Same as P0; plus: after the S12 reinstall, 9637 does not come up within 90s and one more reinstall does not help |
| P2 | `--scenario P2` | S17~S22; **S07 needs `--physical-ops on`** (manual cable pull); **S22 goes last** and needs manual authorization recovery | S22 authorization recovery fails the same day → stop for the day |
| P3 | `python scripts/lib/explore.py plan --mode l2 --depth 2` preview → `python scripts/lib/explore.py run --mode l2 --depth 2 --phone <B> --car <A>` | L2 state-machine traversal (only after P1 has exited) | 3 consecutive rounds failing on the same op; either end goes offline |
| P4 | `python scripts/lib/explore.py run --mode l3 --max-ops 200 --watchdog-s 3600 --seed 42 --phone <B> --car <A>` | L3 overnight fuzz (unattended) | Any red line triggered |

- No SIM → S18 is automatically marked SKIP (not counted as a failure).
- Self-check the full list: `bash scripts/e2e-dual-device.sh --list` (does not touch devices, can be run anytime).
- Dry-run validation (reads arguments/scenarios/registry only, does not touch devices): `bash scripts/e2e-dual-device.sh --phone x --car y --dry-run`.

## 2. Safety checklist (chronological)

### A. Before plugging in devices

- [ ] Both lock-screen PINs match the two environment variables; **each has been tried manually once** — never let the script's first attempt be wrong (failsafe: 5 consecutive failures lock for 30 minutes)
- [ ] `adb devices`: both A (MI 9, USB) and B (MEIZU, `192.168.3.206:5555`) show `device` (B is not `authorizing`/`offline`); **A's lock screen has been unlocked manually** (injected-touch unlock does not work on this ROM, see §0)
- [ ] Visually confirm the roles are not swapped: **`--phone` = MEIZU 20 Inf (sender)**, **`--car` = MI 9 (receiver)**
- [ ] `git status` is clean; confirm no PIN landed in `.plan/` or `scripts/`
- [ ] Both ends on the same WiFi (no AP isolation), PC on the same subnet; proxy/TUN rules allow direct connections to `127.0.0.1:5037` and `192.168.0.0/16`
- [ ] The USB cable can be plugged/unplugged repeatedly (needed by S07); enough free disk space under `debug-logs/e2e/`
- [ ] Baseline snapshot known: `screen_off_timeout` expected 300000, `user_rotation=0`, `airplane_mode_on=0`, IME is not linkpc

### B. After starting, before S01 (watch the script output)

- [ ] 3/9 preflight all pass: both online / IP obtained / bidirectional ping / 9637 unoccupied
- [ ] 4/9 both ends unlocked: `isKeyguardShowing=false` **and** `mWakefulness=Awake` (Awake alone does not count)
- [ ] 5/9 neutral-state six-item check passes: engine count zero / nothing listening on 9637/9638/9639 / timeout=300000 / rotation free / panel on / IME normal
- [ ] **Do not touch the mouse or keyboard** while it runs (dual-device mode should make no win_auto calls at all; run S01 once alone before chaining)

### C. After each scenario (spot check)

- [ ] After restore, read back and verify each neutral-state item (done automatically by the script; on failure it freezes and aborts subsequent scenarios)
- [ ] Both ends' logcat scan: 0 hits for `ANR in` / `FATAL EXCEPTION` / `Force Closed`
- [ ] Failure scenes are in `debug-logs/e2e/<TS>/freeze-<ID>-<stage>/` (screenshots + both ends' logs + operation-sequence slice + state snapshot)
- [ ] PIN counter: at most 1 entry per device in this scenario

### D. Before wrapping up

- [ ] Manual visual check of both ends: screen on, lock state known, IME normal, no letterbox residue, no DiLinkAutoVD in `dumpsys display`
- [ ] Read back on both ends: `screen_off_timeout=300000`, `user_rotation=0`, `airplane_mode_on=0`, WiFi connected
- [ ] Nothing listening on 9637/9638/9639, no engine in `pgrep`; only the expected files remain in `/sdcard/DiLinkAuto/`
- [ ] If S22 ran → authorization has been restored on the device and `adb devices` shows `device` for both
- [ ] If a PIN failsafe was triggered → record the lock expiry time and enter no further passwords before it unlocks
- [ ] Archive the RUN directory; `unset DILINK_TEST_PIN_*`; summarize pass/fail/SKIP counts

## 3. Scenario list (22)

| ID | PRI | EXPECT | manual | budget | Content |
|---|---|---|---|---|---|
| S01 | P0 | pass | | 180 | Baseline stream setup |
| S02 | P0 | pass | | 180 | Touch feedback |
| S03 | P0 | pass | | 180 | Graceful teardown (Eject) |
| S04 | P0 | pass | | 240 | Rotation re-handshake |
| S05 | P1 | pass | | 420 | WiFi down 5s auto-reconnect (relaxed for wireless topology: includes ~20s adb self-heal + product reconnect) |
| S06 | P1 | pass | | 420 | Airplane mode toggle (relaxed for wireless topology, same as S05) |
| S07 | P1 | pass | ✔ | 240 | Unplug sender USB (needs `--physical-ops on`) |
| S08 | P1 | pass | | 300 | Rapid re-connect race |
| S09 | P1 | pass | | 240 | Kill client mid-session |
| S10 | P1 | pass | | 180 | Kill server mid-session |
| S11 | P1 | pass | | 240 | Kill engine process |
| S12 | P1 | pass | | 300 | Reinstall sender APK |
| S13 | P1 | pass | | 240 | Screen off/on |
| S14 | P1 | pass | | 240 | IME integrity |
| S15 | P1 | pass | | 240 | Switch app on the VD |
| S16 | P1 | pass | | 240 | Recents |
| S17 | P2 | pass | | 300 | Frame rate/bitrate switching |
| S18 | P2 | pass | | 300 | Incoming call/notification (auto SKIP without SIM) |
| S19 | P2 | pass | | 1800 | 20-round session loop |
| S20 | P2 | pass | | 300 | Simultaneous reconnect race on both ends |
| S21 | P2 | **fail** | | 300 | 9637 occupied (expected failure) |
| S22 | P2 | **fail** | ✔ | 360 | Revoke debugging authorization (expected failure; **goes last**, needs manual recovery) |

`@expect: fail` = the main operation **should** fail (the direction is flipped); PASS(expected) is recorded only when the ledger has no ✗.

## 4. Exit codes and artifacts

| Exit code | Meaning |
|---|---|
| 0 | All selected scenarios green (SKIP counts as passed) |
| 1 | There are failing scenarios (including @expect:fail scenarios that did not fail as expected) |
| 2 | Usage/environment/build/preflight/unlock failure, or broken setup/restore (aborts subsequent scenarios) |

Artifact directory `debug-logs/e2e/<YYYYmmdd-HHMMSS>/`:

```
summary.txt            summary report (ID/PRI/EXPECT/RESULT/SECONDS + assertion counts)
sequence.jsonl         full op ledger (input for explore replay)
preflight.log          preflight details
run-meta.txt           run parameters and start/end times
freeze-<ID>-<stage>/   failure scene: client.log / vd-server.log / logcat-* / dumpsys-* /
                       state-snapshot.txt / sequence-slice.jsonl /
                       shot-phone.jpg + shot-car.jpg (both physical screens) + vdshot-<ID>.jpg (VD)
neutral-archive/       both-log archive taken before every op_neutral (for comparison)
```

- Screenshots were only actually produced after the `freeze_scene` call signature was fixed on 2026-10-16 (the old call stuffed the path into the label slot → malformed paths were silently swallowed by `|| true`, so freeze directories from historical runs never had screenshots).
- Assertion counts are real values (ledger accounting fixed the same day: previously the orchestration layer overrode `ok/bad`, so `E2E_*` never grew → the `ledger ✗=0` check was meaningless).
- Failing `screencap-*.png` files are now **kept as evidence** (only when the PNG is valid; error text/empty files are deleted and a stderr diagnostic is left).

## 5. Troubleshooting entry points (by symptom)

| Symptom | Look at first | Then check |
|---|---|---|
| 4/9 unlock failure exits immediately (exit 2) | Manually confirm the PIN is not wrong; **do not** re-run to guess the PIN | If ≥2 consecutive failures already: wait out the 30-minute lock window, then try manually |
| preflight reports 9637 occupied | On B, `pgrep -f "[P]ipelineServer"` + `kill` by pid (**`pkill -f` forbidden**) | A leftover engine from the last abnormal teardown |
| S01 setup stuck at stream setup | Last 20 lines of `freeze-S01-setup/client.log` + `vd-server.log` | Whether A connected to B:5555 (authorization dialog: B's dialog must be clicked on its behalf by the PC over USB with "Always allow"); whether both ends are on the same subnet |
| Assertion pattern not found | `.plan/e2e-observability.md` §F calibration table | P0.5 task: copy the real strings back from the frozen artifacts into asserts.sh (D4 already froze the source-code strings; log-surface differences are the first thing to check here) |
| Neutral state does not pass after restore | `state-snapshot.txt` + before/after comparison of `neutral-archive/` | Which item did not return to zero (port/timeout/rotation/panel/IME); for letterbox leakage check `dumpsys display` |
| S01 reports "A screen is pure black" (primary criterion failed) | `S01/screencap-car.png` (kept as evidence on failure) + `state-snapshot.txt` | An empty decode/render surface = broken link: check car_state / 9638 ESTABLISHED / whether A's foreground is MainActivity. Note that A's own screen timing out and turning off also shows black (the engine only holds PARTIAL_WAKE_LOCK) — run `input keyevent KEYCODE_WAKEUP` first, then re-check |
| VD screenshot auxiliary observation does not pass | **This is the expected shape; it does not fail the run** | An empty VD launcher is constantly black (stddev=0.000, verified in v5); only with an app on the VD is it non-black (28.716). For VD picture evidence see `vdshot-*.jpg` |
| `screencap 未产出有效 PNG（…，NB）` (no valid PNG produced; stderr) | The side + byte count in the diagnostic line | `-d` was passed a logical id (you must pass the large SF DisplayId, see the `vd_phys_display_id` comment in asserts.sh); or the physical screen was turned off by the engine (a screencap of display 0 is then always black) |
| Scenario exceeds budget | The SECONDS column of the summary | Local fork/exec ~1s (antivirus scanning), polling uses real `SECONDS` time; attribute to one of WiFi variance / ROM timing / a real bug |
| "forced kill" appears in the log | **This is a regression** (the 12s budget covers the real 6.1~6.3s exit) | Why the engine exit slowed down; use the `探针 ALIVE/GONE` ("probe ALIVE/GONE") lines to locate the stall point |
| S12 does not come up after reinstall | The START_STICKY restart takes ~18s; the desktop side does not auto-retry a failed dial | Restart the exe/script; if one more reinstall does not help, stop per the P1 abort condition |
| Device unauthorized after S22 | Authorization can only be restored manually on the device | The script forbids automatic re-authorization (red line 11) |

General entry points:

```bash
# replay the operation sequence of a RUN (L2/L3 use the same format)
cat debug-logs/e2e/<TS>/sequence.jsonl

# export an L1 scenario skeleton from violations.jsonl (solidify L3 findings; the skeleton must be completed manually before it can be committed)
python scripts/lib/explore.py export-scenario last --run-dir debug-logs/e2e/<TS>

# side-channel evidence capture (freeze the scene directly without touching scenario state)
python scripts/lib/explore.py freeze <tag> --run-dir debug-logs/e2e/<TS>

# registry self-check (ops-registry.json ↔ ops.sh list)
python scripts/lib/explore.py check-registry
```

## 6. Red lines at a glance (12, stop immediately on a hit)

1. No retry-guessing the PIN — at most 1 unlock attempt per device; any device accumulating ≥2 failures → global abort, switch to manual
2. No `pkill -f` — only `pgrep -f "[P]ipelineServer"` + `kill <pid>`
3. No writing to paths outside the allowlist — only `settings put` on the 4 designated keys + `/sdcard/DiLinkAuto/`
4. No exiting / advancing to the next scenario before restore passes item-by-item verification
5. No waiting without a watchdog — all polling budgets use real `SECONDS` time
6. No judging unlock by `input text`/keyevent exit codes — only `isKeyguardShowing=false` + `mWakefulness=Awake`
7. No automatic serial selection — `--phone/--car` must be explicit
8. No PIN in the repo — read from environment variables only
9. No treating SIGTERM as a graceful stop — the engine's graceful path is only the `/sdcard/DiLinkAuto/stop-request` sentinel
10. No `kill -9` of the engine without a follow-up restore
11. No continuing to run in unauthorized/offline state; S22 authorization can only be restored manually
12. No copying desktop-side assertion strings — strings like `探针 GONE @+` must be discovered from both ends' measured log surfaces before entering assertions

## 7. Mock offline replay (self-test without devices, never touches real hardware)

```bash
bash scripts/lib/mock-adb/run-mock-test.sh
```

Four layers of self-test: ① `bash -n` on everything + py_compile; ② `--list` / `--dry-run`; ③ mock adb replay of the full S01+S03 orchestration; ④ artifact assertions + zero residue.
Purpose: verify the **orchestration logic** (if it fails here it will fail on real devices; real-device failures can be reproduced with mock to isolate orchestration bugs), not product behavior.
