# 双手机真机 E2E 运行手册（明早实跑）

> 状态：**真机验证完成**（2026-10-17）——P0 全绿（S01~S04，try3）；P1 全绿
> （S05~S16：S05 367s / S06 289s 无线断网场景末轮验证通过）；P2 全绿（S17
> SKIPPED(env) 会话中无设置入口 / S18 通过 / S19 20 轮循环通过 / S20 双端重连
> 竞态通过 / S21 @expect:fail 达成 / S22 manual 未跑）。**harness 侧失败根因**
> （黑屏误报风暴、手动连接 UI 真机不可达、帧计数重置/暂停、Eject 后不自动重连、
> op_connect 收敛窗口不足等）已逐项修复并复验。**产品侧新增发现 3 处，均已修复
> 并真机复验**：① `screen_off_timeout` 快照在会话被 kill 后丢失原值（下轮读回
> 哨兵 2147483647 回落硬编码 60000 → 用户原值被永久改小）；修复 = 原值跨进程
> 落盘 `/sdcard/DiLinkAuto/screen-timeout.snapshot` + `effectiveSnapshot()` 文件
> 优先恢复（S19 批次 C 复验：读哨兵 → `put 300000`，零 fallback）；② 断连时
> `PipelineServer` 兜底 catch 打完整 `Broken pipe` 堆栈污染日志；修复 =
> `isPeerDisconnect()` 断连类只打一行消息（S19 批次 C 复验：vdlog 无「Exception」）；
> ③ **TCP ADB 半开 socket 永久复用**（WiFi 短暂断连后 A 的 `isConnected` 恒 true
> → 部署 150ms 即失败、failures 6→9+ 无限重试）；修复 = `RemoteAdbController`
> 命令失败（-1）即置断开 + `resetAdbController()` 强制重建（S05 三跑复验：367s
> PASS，断网后自动重连回 STREAMING）。S16 无 Recents 入口、S17 无会话中设置
> 入口均为已知产品缺口，按设计兜底。
> 上位文档：`.plan/e2e-dual-device-impl.md`（总实施方案）、`.plan/e2e-harness-design.md`（harness 契约）、`.plan/e2e-risk-assessment.md`（风险与红线）。
> 本手册是实跑唯一需要的文档：一条命令 + 安全检查单 + 失败排查入口。

## 0. 拓扑速览（30 秒版）

> **角色由 `--phone/--car` 显式指定，与设备型号无关。** 当前实机分配（2026-10-16 实测，**与初版假设相反**）：

| 角色 | 设备 |  app | 连接 |
|---|---|---|---|
| **B = 发送端（phone）** | MEIZU 20 Inf（Flyme/Android 15，**无线** `192.168.3.206:5555`） | app-client | 9637 监听等车机；本机跑 vd-server 引擎；H.264 从 9638 出 |
| **A = 接收端（car）** | MI 9（AOSP 15，**USB** `9e779dea`） | app-server | 经 adb 连 B:5555 部署引擎；拉 B:9638 视频；触摸回注 B:9639 |

- 两端同 WiFi、同网段；PC 侧 A 走 USB、B 走无线 TCP。
- **B 的无线 adb 模式**：一次性 `adb tcpip 5555` 后长期稳定；手机重启 / 无线调试重开后才需重设（届时用系统「无线调试」里显示的**动态端口**先 `adb connect`，再 `adb tcpip 5555`）。Flyme 会重置 USB 调试授权：卡 `authorizing` 时在手机上点「允许」一次。
- **A 端解锁必须人工**（2026-10-16 实测）：该 ROM 锁屏系统性忽略注入触摸（swipe/motionevent/sendevent 30+ 组合全无效，SELinux 挡 sendevent）→ 跑前人工上滑+输 PIN 一次；解锁后脚本幂等跳过（`isKeyguardShowing=false`）。
- 日志断言面在 **B**：`/sdcard/DiLinkAuto/client.log`（A 的日志经 TCP 中继进去，tag=`CarLog`）+ `vd-server.log`。
- **9637 在会话中只有 ESTABLISHED**（LISTEN 是 WAITING 态瞬态）——断言按 ESTABLISHED 判，别按 LISTEN。

## 0.5 一键入口（2026-10-17 起推荐）

三套件、一条命令、自动出结论，**不需要人工盯屏**：

```bash
bash scripts/e2e.sh dual       # 车机+手机（A=app-server / B=app-client）
bash scripts/e2e.sh desktop    # 电脑+手机（app-desktop + app-client）
bash scripts/e2e.sh all        # 两个都跑，出统一结论
```

它替你做的自动化：

| 环节 | 自动化行为 |
|---|---|
| 角色判定 | 包名（`com.dilinkauto.server`→A，`com.dilinkauto.client`→B）→ 传输类型兜底（TCP→B，USB→A）→ 两条都不成立才要求 `--phone/--car`。**不猜**（猜错会把 PIN 输到别的机器，failsafe 计数）。 |
| B 掉线 | `adb mdns services` 发现 `_adb._tcp` / `_adb-tls-connect._tcp` → `adb connect` 自愈（无线调试开着但 PC 侧没连的常见情形）。 |
| PIN | 环境变量优先，其次 `.e2e.local.env`（本机、`.gitignore` 已忽略；**被 git 跟踪时脚本直接拒绝运行** — 红线 8）。 |
| 失败处置 | 场景失败不中断（先冻结证据再 restore）；首轮失败场景**自动复跑一次**，结论里区分 `FAIL` / `FLAKY`（复跑通过）。 |
| 结论 | `debug-logs/e2e/run-<TS>/verdict.md`（人读表格）+ `verdict.json`（机读）+ 退出码 `0` 全绿 / `1` 有失败 / `2` 环境或前置问题。 |
| 产物布局 | 一次运行的所有东西在同一个目录：`<run>/dual/`（summary.txt、各场景、freeze-*）、`<run>/desktop/`（desktop.log、vd-server.log）、`<run>/dual-console.log`。 |

耗时量级（2026-10-17 实测口径）：`dual --scenario S01` ≈ 8.6 分钟（含首次建流的黑屏误报风暴 ×2）；`dual` 全量 22 场景 ≈ **2.5~3.5 小时**（S19 二十轮循环单项就 ~26 分钟）；`desktop` ≈ 4~5 分钟；`all` ≈ 3~4 小时。跑全量建议留足半天并保持电脑空闲（桌面端阶段要动鼠标）。

> **桌面端黑屏自愈（2026-10-17 已修）**：VD 内容本身是黑的时候（空 launcher / 手机屏熄灭），编码器持续输出极小 I 帧，旧判据必然误报「疑似持续黑屏 → 自动重连」，每会话白烧 2 轮（~40s）。现在 `BlackScreenDetector` 增加了 `decodingAliveCheck` 闸门：**黑 + 解码仍在出帧 = 内容黑，不升级**；**黑 + 解码停摆 = 管线故障，才升级**（2s 窗口判停摆）。桌面端已接线，车机端未接线（行为不变）。套件侧仍保留 15s 观察窗与「1 + 新增自愈次数」核账作为兜底，日志会写「其中黑屏自愈 ×N」。复验：MEIZU 上 `疑似持续黑屏` 日志 6 条 → **0 条**，优雅停止 4 次 → **2 次**。

常用变体：

```bash
bash scripts/e2e.sh dual --scenario S05,S06 --skip-build   # 只跑指定场景
bash scripts/e2e.sh all  --yes                             # 无人值守（桌面端跳过倒计时）
bash scripts/e2e.sh dual --retry 0                         # 关闭失败复跑
bash scripts/e2e.sh all  --dry-run                         # 只做设备发现 + 打印计划
bash scripts/e2e.sh --list                                 # 套件与场景清单
```

> 桌面端套件会**移动真实鼠标**并点击窗口，跑的时候电脑必须空闲；`--yes` 只跳过 10 秒倒计时，不改变这一点。
> 旧入口 `scripts/e2e-dual-device.sh` / `scripts/e2e-reconnect-cleanup.sh` 仍然可用——统一入口只是它们的编排层，参数语义完全一致。

## 1. 明早一条命令

```bash
export DILINK_TEST_PIN_PHONE=<B(MEIZU 20 Inf) 的解锁 PIN>
export DILINK_TEST_PIN_CAR=<A(MI 9) 的解锁 PIN>
bash scripts/e2e-dual-device.sh --phone 192.168.3.206:5555 --car 9e779dea \
     --skip-build --scenario S01
```

- `--phone` = B（发送端）adb serial（无线场景是 `IP:5555`），`--car` = A（接收端）adb serial，**必填、别自动挑**（双机在册会挑错 → PIN 输错机器 → failsafe 计数）。
- 跑前确认 **B 在线且授权**（`adb devices` 里 B 是 `device` 不是 `authorizing`）；A 的锁屏已人工解开。
- PIN 只从环境变量或 `.e2e.local.env`（本机、git 忽略）读，**不进门参数、不落任何被跟踪的文件**（`.plan/`、`scripts/` 全在 git 里）。
- APK 前一晚已构建；不放心就去掉 `--skip-build`（会先跑 `./gradlew test` + 双 APK + dex 符号校验，约数分钟）。
- **S01 单独跑通后再连跑**（先验底座）：`--scenario S01` → 全绿 → `--scenario P0`。

### 分阶段放行（建议当天顺序）

| 阶段 | 命令 | 内容 | 中止条件（命中即停，转人工） |
|---|---|---|---|
| P0 | `--scenario P0` | S01~S04 建流/触摸/收尾/旋转 | 任一场景解锁失败；连续 2 场景 restore 后中立态不过；任一端掉线；watchdog 触发 ≥2 次 |
| P0.5 | 人工 | 用 P0 冻结件校准 A 的 UI 控件 id / 授权框形态 | — |
| P1 | `--scenario P1` | S05~S16（WiFi/飞行/杀 app/杀引擎/重装/锁屏/IME/切 app） | 同 P0；另：S12 重装后 90s 不起 9637 且重装一次无效 |
| P2 | `--scenario P2` | S17~S22；**S07 需 `--physical-ops on`**（人工拔线）；**S22 排最后**且需人工恢复授权 | S22 授权当天恢复失败 → 收工 |
| P3 | `python scripts/lib/explore.py plan --mode l2 --depth 2` 预览 → `python scripts/lib/explore.py run --mode l2 --depth 2 --phone <B> --car <A>` | L2 状态机遍历（P1 退出后再上） | 连续 3 轮同一 op 失败；任一端掉线 |
| P4 | `python scripts/lib/explore.py run --mode l3 --max-ops 200 --watchdog-s 3600 --seed 42 --phone <B> --car <A>` | L3 夜间 fuzz（无人值守） | 任一红线被触发 |

- 无 SIM → S18 自动标 SKIP（不算失败）。
- 全量清单自查：`bash scripts/e2e-dual-device.sh --list`（不碰设备，随时可跑）。
- 干跑校验（只读参数/场景/注册表，不碰设备）：`bash scripts/e2e-dual-device.sh --phone x --car y --dry-run`。

## 2. 安全检查单（按时间顺序）

### A. 插机前

- [ ] 两台锁屏 PIN 与两个环境变量一致；**人工各试输一次**，绝不让脚本首输即错（failsafe：连错 5 次锁 30 分钟）
- [ ] `adb devices`：A（MI 9，USB）与 B（MEIZU，`192.168.3.206:5555`）均 `device`（B 不是 `authorizing`/`offline`）；**A 的锁屏已人工解开**（该 ROM 注入触摸解锁无效，见 §0）
- [ ] 目检角色别搞反：**`--phone` = MEIZU 20 Inf（发送端）**、**`--car` = MI 9（接收端）**
- [ ] `git status` 干净，确认 PIN 没进 `.plan/` 或 `scripts/`
- [ ] 两端同一 WiFi（无 AP 隔离）、PC 同网段；代理/TUN 规则放行 `127.0.0.1:5037` 与 `192.168.0.0/16` 直连
- [ ] USB 线可反复插拔（S07 要用）；`debug-logs/e2e/` 磁盘空间充足
- [ ] 基线快照已知：`screen_off_timeout` 预期 300000、`user_rotation=0`、`airplane_mode_on=0`、IME 非 linkpc

### B. 开跑后、S01 之前（看脚本输出）

- [ ] 3/9 preflight 全过：双在线 / IP 取得 / 双向 ping / 9637 无占用
- [ ] 4/9 双端解锁：`isKeyguardShowing=false` **且** `mWakefulness=Awake`（只看 Awake 不算）
- [ ] 5/9 中立态六项核验通过：引擎归零 / 9637/9638/9639 无监听 / timeout=300000 / rotation free / 面板亮 / IME 正常
- [ ] 跑的时候**别动鼠标键盘**（双机模式不应有任何 win_auto 调用；S01 单跑一次再连跑）

### C. 每场景后（抽查）

- [ ] restore 后中立态逐项读回验证（脚本自动做，失败即冻结并中止后续场景）
- [ ] 双端 logcat scan：`ANR in` / `FATAL EXCEPTION` / `Force Closed` 0 命中
- [ ] 失败场景现场在 `debug-logs/e2e/<TS>/freeze-<ID>-<阶段>/`（截图 + 双端日志 + 操作序列切片 + 状态快照）
- [ ] PIN 计数器：每设备本场景最多输入 1 次

### D. 收工前

- [ ] 双端人工目检：亮屏、锁屏状态已知、输入法正常、无 letterbox 残留、`dumpsys display` 无 DiLinkAutoVD
- [ ] 双端读回：`screen_off_timeout=300000`、`user_rotation=0`、`airplane_mode_on=0`、WiFi 已连
- [ ] 9637/9638/9639 无监听、`pgrep` 无引擎；`/sdcard/DiLinkAuto/` 只剩预期文件
- [ ] 跑过 S22 → 设备已恢复授权且 `adb devices` 双 `device`
- [ ] 触发过 PIN failsafe → 记录锁定截止时间，解锁前不再输任何密码
- [ ] RUN 目录归档；`unset DILINK_TEST_PIN_*`；汇总通过/失败/SKIP 计数

## 3. 场景清单（22 个）

| ID | PRI | EXPECT | manual | budget | 内容 |
|---|---|---|---|---|---|
| S01 | P0 | pass | | 180 | 基线建流 |
| S02 | P0 | pass | | 180 | 触摸回传 |
| S03 | P0 | pass | | 180 | 优雅收尾（Eject） |
| S04 | P0 | pass | | 240 | 旋转重握手 |
| S05 | P1 | pass | | 420 | WiFi 断 5s 自动重连（无线拓扑放宽：含 adb 自愈 ~20s + 产品重连） |
| S06 | P1 | pass | | 420 | 飞行模式开关（无线拓扑放宽，同 S05） |
| S07 | P1 | pass | ✔ | 240 | 拔发送端 USB（需 `--physical-ops on`） |
| S08 | P1 | pass | | 300 | 连点重连竞态 |
| S09 | P1 | pass | | 240 | 会话中杀 client |
| S10 | P1 | pass | | 180 | 会话中杀 server |
| S11 | P1 | pass | | 240 | 杀引擎进程 |
| S12 | P1 | pass | | 300 | 重装发送端 APK |
| S13 | P1 | pass | | 240 | 锁屏/亮屏 |
| S14 | P1 | pass | | 240 | IME 完整性 |
| S15 | P1 | pass | | 240 | VD 上切 app |
| S16 | P1 | pass | | 240 | 最近任务 |
| S17 | P2 | pass | | 300 | 帧率/码率切换 |
| S18 | P2 | pass | | 300 | 来电/通知（无 SIM 自动 SKIP） |
| S19 | P2 | pass | | 1800 | 20 轮会话循环 |
| S20 | P2 | pass | | 300 | 双端同时重连竞态 |
| S21 | P2 | **fail** | | 300 | 9637 被抢占（预期失败） |
| S22 | P2 | **fail** | ✔ | 360 | 撤销调试授权（预期失败；**排最后**，需人工恢复） |

`@expect: fail` = 主操作**应该**失败（判向已翻转）；ledger 无 ✗ 才记 PASS(expected)。

## 4. 退出码与产物

| 退出码 | 含义 |
|---|---|
| 0 | 选中场景全绿（SKIP 计过） |
| 1 | 有失败场景（含 @expect:fail 未按预期失败） |
| 2 | 用法/环境/构建/preflight/解锁失败，或 setup/restore 损坏（中止后续场景） |

产物目录 `debug-logs/e2e/<YYYYmmdd-HHMMSS>/`：

```
summary.txt            汇总报表（ID/PRI/EXPECT/RESULT/SECONDS + 断言计数）
sequence.jsonl         全量 op 记账（explore 复盘输入）
preflight.log          preflight 明细
run-meta.txt           运行参数与起止时间
freeze-<ID>-<阶段>/    失败现场：client.log / vd-server.log / logcat-* / dumpsys-* /
                       state-snapshot.txt / sequence-slice.jsonl /
                       shot-phone.jpg + shot-car.jpg（双端物理屏）+ vdshot-<ID>.jpg（VD）
neutral-archive/       每次 op_neutral 前的双日志归档（对比用）
```

- 截图自 2026-10-16 修复 `freeze_scene` 调用签名后才真实产出（旧调用把路径塞进 label 位 → 畸形路径被 `|| true` 静默吞掉，历史 run 的 freeze 目录从未有过截图）。
- 断言计数为真实值（同日修复 ledger 记账：此前编排层覆盖 `ok/bad` 后 `E2E_*` 永不增长 → `ledger ✗=0` 判定形同虚设）。
- 判败的 `screencap-*.png` 现在**保留作证据**（仅 PNG 有效时；错误文本/空文件会删除并留 stderr 诊断）。

## 5. 失败排查入口（按症状）

| 症状 | 先看 | 再查 |
|---|---|---|
| 4/9 解锁失败即退出（exit 2） | 人工确认 PIN 未错；**不要**重跑猜 PIN | 若连错已 ≥2 次：等 30 分钟锁定窗口过再人工试 |
| preflight 报 9637 占用 | B 上 `pgrep -f "[P]ipelineServer"` + 按 pid `kill`（**禁 `pkill -f`**） | 上一次非正常收尾的残留引擎 |
| S01 setup 卡在建流 | `freeze-S01-setup/client.log` 末 20 行 + `vd-server.log` | A 是否连上 B:5555（授权框：B 弹框需 PC 经 USB 代点「一律允许」）；两端是否同网段 |
| 断言 pattern not found | `.plan/e2e-observability.md` §F 校准表 | P0.5 任务：从冻结件抄真实串回 asserts.sh（D4 已固化源码串，日志面差异优先在这里) |
| restore 后中立态不过 | `state-snapshot.txt` + `neutral-archive/` 前后对比 | 哪一项没归零（端口/timeout/rotation/面板/IME）；letterbox 泄漏看 `dumpsys display` |
| S01 报「A 屏纯黑」（主判据失败） | `S01/screencap-car.png`（判败留证）+ `state-snapshot.txt` | 解码渲染面空 = 链路断：查 car_state / 9638 ESTABLISHED / A 前台是否 MainActivity。注意 A 屏自身超时熄灭也会黑（引擎只持 PARTIAL_WAKE_LOCK）——先 `input keyevent KEYCODE_WAKEUP` 再复核 |
| VD 截屏辅助观测未过 | **属预期形态，不判败** | VD 空 launcher 恒黑（stddev=0.000，v5 实证）；VD 上有 app 时才非黑（28.716）。VD 画面证据看 `vdshot-*.jpg` |
| `screencap 未产出有效 PNG（…，NB）`（stderr） | 诊断行里的 side + 字节数 | `-d` 传了 logical id（须传 SF DisplayId 大数，见 asserts.sh `vd_phys_display_id` 注释）；或物理屏被引擎熄掉（对 display 0 截图恒黑） |
| 场景超预算 | summary 的 SECONDS 列 | 本机 fork/exec ~1s（杀软扫描），轮询按 `SECONDS` 真实时间；WiFi 方差 / ROM 时序 / 真 bug 三类归因 |
| 「强制 kill」出现在日志 | **这是回归**（budget 12s 覆盖真实退出 6.1~6.3s） | 引擎退出为何变慢；`探针 ALIVE/GONE` 行定位卡点 |
| S12 重装后不起 | START_STICKY 重启约 18s；桌面端拨号失败不自动重试 | 重启 exe/脚本；重装一次无效即按 P1 中止条件停 |
| S22 之后设备 unauthorized | 只能人工在设备上恢复授权 | 脚本禁止自动重新授权（红线 11） |

通用入口：

```bash
# 某个 RUN 的操作序列复盘（L2/L3 同格式）
cat debug-logs/e2e/<TS>/sequence.jsonl

# 从 violations.jsonl 导出 L1 场景骨架（L3 发现固化；骨架须人工补全后才可提交）
python scripts/lib/explore.py export-scenario last --run-dir debug-logs/e2e/<TS>

# 旁路取证（不动场景状态，直接冻结现场）
python scripts/lib/explore.py freeze <tag> --run-dir debug-logs/e2e/<TS>

# 注册表自检（ops-registry.json ↔ ops.sh list）
python scripts/lib/explore.py check-registry
```

## 6. 红线速查（12 条，命中即停）

1. 禁重试猜 PIN——单次解锁每设备最多 1 次；任一设备累计失败 ≥2 → 全局 abort 转人工
2. 禁 `pkill -f`——只准 `pgrep -f "[P]ipelineServer"` + `kill <pid>`
3. 禁写白名单外路径——仅 `settings put` 指定 4 键 + `/sdcard/DiLinkAuto/`
4. 禁 restore 未逐项验证通过就退出/进下一场景
5. 禁无看门狗等待——所有轮询按 `SECONDS` 真实时间预算
6. 禁用 `input text`/keyevent 退出码判断解锁——只认 `isKeyguardShowing=false` + `mWakefulness=Awake`
7. 禁自动挑 serial——必须显式 `--phone/--car`
8. 禁 PIN 落仓库——只读环境变量
9. 禁把 SIGTERM 当优雅停止——引擎优雅路径只有 `/sdcard/DiLinkAuto/stop-request` 哨兵
10. 禁 `kill -9` 引擎后不补 restore
11. 禁在 unauthorized/offline 状态继续跑；S22 只能人工恢复授权
12. 禁照搬桌面端断言串——`探针 GONE @+` 等串须从双端实测日志面摸出后再入断言

## 7. mock 离线回放（无设备自测，不碰真机）

```bash
bash scripts/lib/mock-adb/run-mock-test.sh
```

四层自测：① bash -n 全部 + py_compile；② `--list` / `--dry-run`；③ mock adb 回放 S01+S03 全编排；④ 产物断言 + 零残留。
用途：验证**编排逻辑**（不过 ≈ 真机不过；真机不过可用 mock 复现编排 bug），不验证产品行为。
