# 双手机真机 E2E：场景矩阵与探索策略设计

> 状态：**已落地并进入真机验证**（2026-10-17）。P0 全绿（S01~S04）；P1 已跑到 S16（S09~S16 修复后复验中）；P2 进行中。**§1 角色表按 2026-10-16 实测修正（与初版假设相反）**。
> 配套：`scripts/e2e-dual-device.sh`（主编排）+ `scripts/lib/{devices,ops,asserts}.sh` + `scripts/scenarios/s01-s22` + `scripts/lib/explore.py`；runbook `docs/e2e-dual-device-runbook.md`（红线/排查 §5/§6）；桌面端范式来源 `scripts/e2e-reconnect-cleanup.sh`。

## 1. 拓扑与角色

引擎（vd-server）**恒跑在发送端（手机角色）本机**；接收端（车机角色）经 adb 连发送端 5555 部署/拉起引擎，从发送端 **9638** 拉 H.264 视频、往 **9639** 回注触摸，**9637** 为控制口（`protocol-core/.../Ports.kt` 实证）。

| 角色 | 设备（2026-10-16 实测） | APK | 职责 |
|---|---|---|---|
| 发送端（phone，B） | **MEIZU 20 Inf**（无线 `192.168.3.206:5555`；PIN 经 `DILINK_TEST_PIN_PHONE` 注入，**禁落仓库**） | `com.dilinkauto.client` | 9637 监听；本机跑引擎（VD + GlPipeline + 编码）；无线 adb（**无 USB transport**） |
| 接收端（car，A） | **MI 9**（USB `9e779dea`；PIN 经 `DILINK_TEST_PIN_CAR` 注入） | `com.dilinkauto.server` | adb host 连 B:5555 部署引擎；拉 B:9638 解码上屏；触摸 → B:9639 |
| 编排者 | PC | — | adb 装 APK、解锁、`input` 驱动两端 UI、screencap、logcat、pgrep 轮询、断言 |

> 初版假设（MI 9 作发送端）与实测相反——实际按上表运行；`--phone`/`--car` 均必填，禁自动挑选设备。
> 两端同一 WiFi（无 AP 隔离）；B 的无线 adb 授权对话框首次出现，PC 侧 `input tap` 自动点，授权后持久。
> **无线拓扑后果**：B 的 WiFi 一断，PC→B 的 adb 即断（offline）→「断网类 op」必须先在 B 预置 nohup 自恢复任务（见 ops.sh `op_wifi_off`/`op_airplane` 注释；`svc wifi` 在 MEIZU Android 15 不生效，须 `cmd wifi`）。

## 2. 测试策略分层

穷举所有 edge case 不可能，组合覆盖可以。三层递进，共用 §5 的同一套不变量：

| 层 | 形态 | 用途 | 载体 |
|---|---|---|---|
| L1 | 确定性场景矩阵 | 固化已知回归面，每场景可独立重跑 | `scripts/scenarios/*.sh` |
| L2 | 模型驱动（状态机遍历） | 从每个状态出发跑短操作序列全排列，专抓竞态 | `scripts/lib/explore.py` |
| L3 | 随机模糊 + 故障注入 | 夜间无人值守长跑，撞未知 edge case | `explore.py --fuzz` |

L3 任何不变量破坏 → 冻结现场 → 把操作序列回放固化为新的 L1 场景（bug 修复的回归用例）。

## 3. 会话状态机模型（L2 骨架）

### 3.1 状态枚举（代码实证）

- 车机侧 `CarConnectionService.State`：`IDLE → CONNECTING → CONNECTED → STREAMING`（CONNECTING 内两条并行轨：adb 部署轨 + 控制/生命周期轨；首帧视频到达 → STREAMING）
- 手机侧 `ConnectionService.State`：`IDLE → WAITING → CONNECTED → STREAMING`（WAITING = 9637 监听中）

### 3.2 复合状态（模型实际跟踪的向量）

`(car_state, phone_state, engine, vd, sentinel)`，其中 engine/vd ∈ {absent, present}，sentinel ∈ {off, on}。合法收敛态：

| 复合态 | 含义 |
|---|---|
| (IDLE, IDLE, absent, absent, off) | 中立态（每轮 setup/restore 目标） |
| (STREAMING, STREAMING, present, present, on) | 健康会话 |
| (CONNECTING, WAITING, *, *, *) | 部署/握手中（允许的中间态，有超时预算） |
| (IDLE, WAITING, absent, absent, off) | 会话结束收敛（收尾断言目标） |

### 3.3 操作集 O

每个 op = 确定性命令序列，执行后有 settle 窗口（默认 15s，收尾类 30s）：

| op | 执行侧 | 命令概要 |
|---|---|---|
| `connect` | A | UI 输入 B 的 IP → 连接（uiautomator 定位按钮 + `input tap/text`） |
| `disconnect` | A | UI 断开 |
| `rotate` | B | `settings put system user_rotation` + 触发重摆（横竖互切） |
| `wifi_off` / `wifi_on` | B | `svc wifi disable/enable` |
| `airplane` | B | `settings put global airplane_mode_on` + 广播 |
| `kill_engine` | B | `pgrep -f "[P]ipelineServer"` → `kill -9` |
| `kill_client` | B | `am force-stop com.dilinkauto.client` |
| `kill_server` | A | `am force-stop com.dilinkauto.server` |
| `reinstall` | B | PC 侧 `adb install -r app-client-debug.apk` |
| `screen_off` / `screen_on` | B | `cmd display power-off 0` / `power-reset 0` |
| `switch_app` | A/B | 经 app UI 在 VD 上启动另一个 app |
| `ime_open` | A | 在 VD 上点开一个输入框（键盘完整性场景） |
| `usb_pull` | PC | 拔 B 的 USB（物理动作，留人工开关） |
| `adb_revoke` | B | 设置里撤销 USB 调试授权 |

### 3.4 遍历规则

- 每状态只保留**有意义 op 子集**（如 IDLE 态只跑 `connect`/`reinstall`/`adb_revoke`），剪掉无意义组合。
- depth-2 全排列（每状态 25~64 序列）；depth-3 按 10% 抽样。
- 每轮：`setup(中立态) → op1 → settle+中间断言 → op2 → settle → 全套不变量 → restore(中立态)`。
- 单轮预算 ≤ 90s；整轮跑完保存序列日志，供失败回放。
- 中止条件：连续 3 轮在同一 op 失败 → 暂停并上报；任一端掉线 → abort 全轮。

## 4. L1 场景库

标准结构：`setup → ops → asserts → restore`（restore 与 setup 相同，保证场景间无残留）。

### P0 最小闭环（未全绿前不跑其他场景）

| ID | 场景 | 操作序列 | 断言 |
|---|---|---|---|
| S01 | 基线建流 | connect（`reach_streaming` 内置 `wait_stream_settled`：等建流后 ~40s 黑屏误报风暴静默——VD 空 home 合法黑屏被 BlackScreenDetector 误报，2 次重建后自愈，2026-10-17 try2 实证） | 双端 STREAMING；B 的 `dumpsys display` 有 DiLinkAutoVD 且 real 尺寸 = 握手 vdWidth/vdHeight；**A 屏（解码渲染面）screencap 非纯黑为主判据**，VD 截屏非纯黑为辅助观测（VD 空 launcher 恒黑属正常，2026-10-16 v5 实证）；vd-server.log 有 `Video on :9638` |
| S02 | 触摸回传 | S01 + **先点应用磁贴进镜像面**（dump A UI → 顶部网格区第 1 个磁贴 → tap → 等 A logcat `CarShell: launchApp clicked` 证据；CarShell 初始在 HOME 应用网格，镜像面只在点开 app 后可见，Home 态下 SurfaceView INVISIBLE 不接收触摸——2026-10-17 try2 根因；该前置为**软处理**，失败不直接判败）+ A 上 tap 三个点（25/50/75% × 55%，坐标现算） | **B 的 client.log 出现 A 端 `Touch #N action=` 中继行**（CarTouchSender 前 5 次触摸经 9637 中继；2026-10-16 实证旧判据 `InputManager/inject` 恒过已废）+ 无 `Touch DROP`；流不被打断（双端仍 STREAMING）；A 屏非纯黑 |
| S03 | 优雅收尾 | S01 + disconnect | 引擎优雅退出（6.1~6.3s 内）无「强制 kill」；screen_off_timeout 恢复基线；9637 释放；`pgrep` 引擎归零 |
| S04 | 旋转重握手 | S01 + rotate | 旧 VD teardown → 新握手 → 重回 STREAMING；新 VD 尺寸正确 |

### P1 核心回归

| ID | 场景 | 操作序列 | 断言 |
|---|---|---|---|
| S05 | WiFi 断 5s | S01 + wifi_off + sleep5 + wifi_on | 自动重连回 STREAMING，无强制 kill。**无线拓扑适配**（2026-10-17）：断前在 B 预置 nohup 自恢复（8s/25s），断言预算放宽至 150s，恢复后先 `wait_stream_settled` 再断言 |
| S06 | 飞行模式 | S01 + airplane ×2 | 恢复后重连或按设计停留在可重连态。同 S05 无线适配（探路窗 150s + 风暴静默） |
| S07 | 拔发送端 USB | S01 + usb_pull | 会话继续 STREAMING（A↔B 走 WiFi，与 PC 无关） |
| S08 | 接收端连点重连 | S01 + disconnect + connect 快速 ×2 | 不双开引擎、不抢 9637、最终收敛 STREAMING。**重连原语 = 重启 A 的 app**（手动连接 UI 不可达；force-stop + monkey 触发启动路径自动连接，2026-10-17 微验证）。重连产生新风暴 → 断言前必须 `wait_stream_settled`（150s） |
| S09 | 会话中杀 client | S01 + kill_client | 收尾清理全过（同 S03 断言集） |
| S10 | 会话中杀 server | S01 + kill_server | B 侧状态回落 WAITING/IDLE；引擎退出；无残留 |
| S11 | 杀引擎进程 | S01 + kill_engine | 按设计检测到并恢复或干净降级；不出现静默挂死 |
| S12 | 重装发送端 APK | S01 + reinstall | START_STICKY ~18s 重启后可重新建流 |
| S13 | 锁屏/亮屏 | S01 + screen_off + screen_on | 按设计暂停/恢复；无 tex=0 静止（黑屏回归信号）。**判据 = A 屏（解码渲染面）非纯黑**——B 物理屏会话中被引擎熄掉恒黑不能判（2026-10-17 实证）；记账必须走 ok/bad（禁 `\|\| rc=1` 裸写法） |
| S14 | IME 完整性 | S01 + ime_open | VD ≥ 1368 时键盘行完整；退出后 IME 恢复。**导航栏 Home 真机 content-desc = 中文「主页」**（英文 "Home" 只在 mock fixture）——双语定位 |
| S15 | VD 上切 app | S01 + switch_app | 目标 app 上屏，无 ANR。磁贴解析 = `_grid_tile_bounds`（真机 dump 单行 XML + 父节点 text 空串，旧按行 grep 恒败）；解析失败按比例坐标兜底（x≈16%+24%×(n-1)，y≈11%） |
| S16 | 最近任务 | S01 + 开最近任务 UI | RecentsParser 路径正常，目标 app 上前台。**产品缺口**：A 的 UI 无 Recents 入口（源码实证）——命中时判画面变化，未命中走兜底路径跳过画面判据（条件化，不算失败） |

### P2 扩展（P1 全绿后）

| ID | 场景 | 说明 |
|---|---|---|
| S17 | 帧率/码率切换 | 若手机端 UI 暴露该设置；否则挪到桌面端 E2E。**实测（2026-10-17）**：会话中双端 UI 均无入口（该设置只在 A 启动屏 `CarLaunchScreen.kt` 暴露，会话中被 CarShell 流媒 UI 取代）→ SKIPPED(env)，未执行产品断言 |
| S18 | 来电/通知 | 需真机 SIM 或 `adb` 模拟，ROM 差异大，标注可跳过 |
| S19 | 20 轮会话循环 | 引擎 RSS/句柄无单调增长（首跑定阈值，宁缺勿假绿）；vd-server.log 无异常堆栈；末轮干净收尾。**2026-10-17 修正**：每轮重连原语 = `reconnect` op（重启 A 的 app——Eject 后 userDisconnected 持久化不自动重连 + 手动框不可达，首跑实证）。**第六轮暴露 2 处产品缺陷并修复**：① `screen_off_timeout` 被 kill 路径丢失恢复（原值落盘 `TimeoutSnapshotFile` + `effectiveSnapshot` 修正）；② 断连 Broken pipe 打完整堆栈（`isPeerDisconnect` 判定后不打） |
| S20 | 双端同时重连竞态 | A、B 在 2s 内同时触发重连：`kill_client &`（B 拆）+ `reconnect &`（A 重启重连）并发，wait 后补 B 侧恢复（monkey 拉起 client 进 WAITING——force-stop 后不自启，原场景缺的一半）；断言前 `wait_stream_settled` 防重连风暴误判 |
| S21 | 9637 被抢占 | 会话外用其他进程占 9637 → 建流应按设计失败并给出明确状态（**@expect: fail**——判向翻转，主操作应该失败）。**第六轮修正 ×2**：① `op_occupy_9637` 改 `nc -Lp`（`-l` 单连接即退 → A 首次连接耗掉占用者 → 首跑 UNEXPECTED-PASS 根因；`-L` 持续监听，真机冒烟两次连接后仍 LISTENING）；② 主操作改 `reconnect` 原语（批次 C 实证：9637 被占时 A 的失败尝试不产生 client.log 状态行 → `car_state=UNKNOWN` → 原 `op_connect` 走手动 UI 兜底而 ManualConnectBox 真机不可达 → 误判「ops 失败」FAIL；`reconnect` rc 恒 0 语义纯净，失败判向交由断言层的「未收敛」判定） |
| S22 | 撤销调试授权 | adb_revoke 后重连 → 按设计引导重新授权，不静默失败 |
| S23 | 音频镜像（含熄屏后持续出帧） | S01 + screen_off + sleep10 + screen_on。五条音频判据：① vd 帧计数 >0 且窗口内增长 + 速率下限（非严格线性）② 播放端 decoded>0 / starved==0 / underrun 不增长 ③ 静音补帧计数 >0（降观测项）④ 熄屏 10s 内帧仍增长（R7）⑤ 音频降级时视频轨存活。**覆盖面**：兜传输/解码链路断裂与「帧完全不出」；**不兜源侧静音**（漏 startRecording，需人工听觉回签）。Refs #13/#79 |
| S25 | VD 连续切换 4 次（4~5 个应用） | S01 + `switch_app` ×4（磁贴 1~4：bilibili/小红书/番茄/百度地图；初始应用可能已在其中之一）。判据：① `[Window]` ≥4 行，末条 `n≥4`、顶层 `visReq=true`、旧应用 `visReq=false` ≥3；② 4 个被切换包名都在 `[Window]` 出现；③ 所有 `[Pipeline]` 统计行 `swapFails=0 sendDrops=0 glErr=0`（`q` 允许瞬时 1）且无 `tex=0`，末条 `tex` > 首条；④ 无 `[BLACK] decode still alive`。**不覆盖** #55/#58 的真实长视频/1 小时导航。Refs #15/#106 |

## 5. 不变量监控规格（每轮必查）

| 不变量 | 采集命令 | 通过判据 |
|---|---|---|
| 引擎进程 | B: `pgrep -f "[P]ipelineServer"` | 收尾后为空；会话中恰 1 个 |
| 9637 监听 | B: `ss -ltn \| grep :9637` | 会话中有；收尾后无 |
| 视频流存活 | vd-server.log 帧计数 / `Video on :9638` | 会话中持续增长 |
| VD 点亮 | B: `dumpsys display`（DiLinkAutoVD real）+ A 屏 screencap | real 尺寸 = 握手下发值；**A 屏（解码渲染面）像素方差 > 0**（主判据）。VD 直截需 SF DisplayId 大数（`dumpsys SurfaceFlinger` 的 `Virtual Display <id>` 段；logical id 报 Status:-2），且空 launcher 恒黑——仅作辅助观测 |
| 黑屏回归 | vd-server.log GlPipeline 帧日志 | 无 `tex=0` 或小值静止 |
| ANR / 崩溃 | 双端 logcat scan `ANR in\|FATAL EXCEPTION\|Force Closed` | 0 命中 |
| 屏幕超时哨兵 | B: `settings get system screen_off_timeout` | 会话中 2147483647；收尾后恢复基线（300000） |
| 优雅退出 | vd-server.log 退出耗时 + 「强制 kill」字样 | 12s 预算内收敛；「强制 kill」出现即回归 |
| 会话状态一致 | A 的 UI 状态 vs B 的 FileLog 状态 | 收敛到同一复合态（§3.2） |

> 注意：断言串以双端**实测日志面**为准。`引擎已优雅退出`/`探针 GONE @+` 等串目前只在桌面端 desktop.log 出现过；A（app-server）与 B（FileLog）的等价串必须在 P0.5 摸出来后固化，不得照搬桌面端字符串。

## 6. L3 故障注入目录

`kill -9` 随机进程（引擎/client/server）、`svc wifi`、飞行模式、`screen_off/on` 随机插入、会话中 `reinstall`。权重：常规 op 70% / 故障 op 30%。每 5 op 跑轻量不变量（引擎/端口/流存活），每 20 op 跑全套。物理 USB 拔插无法纯软件化，留人工开关。

## 7. 基础设施缺口与落地顺序

| 阶段 | 内容 | 产出 |
|---|---|---|
| P0 | 修 `e2e-reconnect-cleanup.sh:109-113` serial 自动挑选（双机插入会挑错设备，PIN 会输错机器）；提交 `devices.sh`；补 `screencap_car` | 可用的双设备底座 |
| P0.5 | 日志面勘察：B 的 FileLog 路径与关键行、A 的 logcat tag、A 的连接 UI 控件定位（uiautomator） | 断言串清单 |
| P1 | `scripts/lib/asserts.sh`（不变量库）+ `scripts/e2e-dual-device.sh` 主编排 + S01~S04 | 双机最小闭环跑通 |
| P2 | 补齐 S05~S16，每场景独立可重跑 | 核心回归网 |
| P3 | `scripts/lib/explore.py`：L2 状态机遍历 | 竞态猎手 |
| P4 | L3 fuzz + 夜间长稳 + triage 固化回路 | 无人值守 |

目录规划：`scripts/lib/asserts.sh`、`scripts/scenarios/*.sh`（每场景一文件，source asserts.sh）、`scripts/lib/explore.py`（python 编排 bash 场景，管序列/预算/现场冻结）。

## 8. 风险与 ROM 差异

- **解锁**：MIUI 直输 PIN 成；Flyme 需上滑 + tap 聚焦（devices.sh 已双路径）。`input text` 失败静默 → 必须硬断言 `isKeyguardShowing=false`；禁重试循环（failsafe 5 次锁 30 min）。
- **VD 点亮因机型而异**：实测 **MEIZU 20 Inf 作发送端（B）**，VD lighting 已随 S01 全绿实证（2026-10-16）；MI 9 作发送端未验证，若启用需先单独验证。
- **Android 15 BAL**：MEIZU 20 Inf 上后台拉 Activity 路径受限（已知观察：`PhoneDisplayRestorer` Layer 2 无效）——涉及"后台拉起"的场景在 A 为 Android 15 时按已知限制调整预期，不算新 bug。
- **A 的 UI 驱动**依赖 uiautomator dump 找连接按钮，ROM 差异需在 P0.5 校准坐标/控件 id。
- **本机 fork/exec ~1s**：所有轮询按真实经过时间（SECONDS）设预算，禁「迭代次数 × sleep」。
- **跑时电脑鼠标空闲**仅约束桌面端 E2E；双机方案全走 `adb shell input`，可无人值守——这是它相对桌面 E2E 的核心运维优势。
