# DiLink-Auto

把手机上的应用镜像到汽车自带屏幕上，支持完整触摸交互。开源，不依赖 Google 服务。

本仓库是 DiLink-Auto 的**原始仓库**（[andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android)）。2026 年 11 月，它把长期维护的 fork [ID-VerNe/dilink-auto-android](https://github.com/ID-VerNe/dilink-auto-android) 的 252 个提交合并回来 —— 在上游沉寂期间，正是这个 fork 让项目继续存活。开发继续聚焦于**中国手机 ROM + BYD DiLink 车机**，并针对**入门级 DiLink 4.0** 做了专门调优，使其在弱硬件上从“幻灯片”变为可用。

**Documentation / Documentação / 文档 / Документация / Documentation / Құжаттама / Документація / Hujjatlar**

GitHub 不会自动按你的语言显示文档。请选择一种：

| 语言 | README | 安装 | 架构 | 客户端 | 服务端 | 协议 | 进度 |
|----------|--------|-------|--------------|--------|--------|----------|----------|
| English | [README](../../README.md) | [安装](../setup.md) | [架构](../architecture.md) | [客户端](../client.md) | [服务端](../server.md) | [协议](../protocol.md) | [进度](../progress.md) |
| Português (BR) | [README](../pt-BR/README.md) | [安装](../pt-BR/setup.md) | [架构](../pt-BR/architecture.md) | [客户端](../pt-BR/client.md) | [服务端](../pt-BR/server.md) | [协议](../pt-BR/protocol.md) | [进度](../pt-BR/progress.md) |
| 简体中文 | **本页** | — | — | — | — | — | — |
| Français | [README](../fr/README.md) | — | — | — | — | — | — |
| Русский | [README](../ru/README.md) | — | — | — | — | — | — |
| Беларуская | [README](../be/README.md) | — | — | — | — | — | — |
| Қазақша | [README](../kk/README.md) | — | — | — | — | — | — |
| Українська | [README](../uk/README.md) | — | — | — | — | — | — |
| Oʻzbekcha | [README](../uz/README.md) | — | — | — | — | — | — |

破折号表示这些深入指南尚未翻译；**英文版是权威版本**，任何译文页面与英文原文不一致时以英文原文为准。

流程指南也已翻译成两种语言：

| 语言 | Issue 指南 | PR 指南 | 发布指南 |
|----------|-------------|----------|---------------|
| English | [Issue](../ISSUE_GUIDE.md) | [PR](../PR_GUIDE.md) | [Release](../release.md) |
| Português (BR) | [Issue](../pt-BR/ISSUE_GUIDE.md) | [PR](../pt-BR/PR_GUIDE.md) | [Release](../pt-BR/release.md) |
| 简体中文 | [Issue](./ISSUE_GUIDE.md) | [PR](./PR_GUIDE.md) | [Release](./release.md) |

简体中文另有测试覆盖报告（[IMPLEMENTATION_REPORT_TESTING](./IMPLEMENTATION_REPORT_TESTING.md)）、双模式接收端提案（[FEATURE_DUAL_MODE_RECEIVER](./FEATURE_DUAL_MODE_RECEIVER.md)）以及两份双手机端到端（E2E）文档（[runbook](./e2e-dual-device-runbook.md)、[scenarios](./e2e-scenarios.md)）。

当前版本：**0.19.0**（`versionCode 26111724`）。下载：[Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest)。

版本号遵循本仓库的语义化版本方案：`app.versionName` 为 `MAJOR.MINOR.PATCH`（预发布追加 `-dev-N`），`app.versionCode` 是手工维护的单调递增整数。tag 形如 `v0.19.0`（正式版）与 `v0.19.1-dev-3`（预发布）。

它最初要解决的矛盾是：搭载中国 ROM 的手机（小米 HyperOS 等）装不了 Android Auto，而车机只支持 Android Auto —— 中间没有桥。DiLink-Auto 适用于任何 Android 10+ 手机，有没有 Google 服务都行。

---

## 实测硬件

**比亚迪 秦 PLUS DM-i 2023 款 冠军版 55KM 领先型。** 它的车机是入门级 DiLink 4.0 —— 骁龙 439（8 个 Cortex-A53，全为小核）、Adreno 505、4 GB RAM、16 GB eMMC、1280x800 屏幕、仅 2.4 GHz Wi-Fi、Android 9 / API 28、H.264 硬件解码上限 1080p。所有性能调优都围绕这套硬件展开。

---

## v0.18.0-dev 以来的变化

从 fork 点（`84df5cb`，upstream/main）到当前 `main` 的演进简述。每一条都对应真实提交，而不是计划；详细时间线见 [docs/progress.md](../progress.md)。

### 1. 直连 VD 流式架构

手机端不再中继视频、触摸或音频。VD server 绑定 `9638`（视频）、`9639`（输入）和 `9640`（音频，双向协商后开启）端口，直接与接收端对话。这样每帧省掉了 4 个 socket + 2 次进程上下文切换，只剩 2 个 socket + 0 次上下文切换。手机端变成纯粹的编排器：握手与能力协商、VD 生命周期（`VD_PORTS_BOUND`）以及车机日志路由。协议新增了 `FrameCodec.readFrameBlocking()`、`MessageType.VD_PORTS_BOUND` 和 `AudioPayload`，`VirtualDisplayClient` 则被缩减为只管生命周期。

### 2. 入门级 DiLink 4.0 调优（核心工作）

针对上面的硬件：

- **编码尺寸上限 1920x1080。** 手机端过去为了避免 IME 裁剪，会把 VD 放大到物理像素（1080x2340 → 编码 2340x1658）；这超过了 439 的硬件解码上限，触发软件解码，8 个 A53 跑 1080p 以上的 H.264 只有个位数帧率。修法是解耦 VD 尺寸与编码尺寸 —— VD 保持放大（保住 IME 修复），编码尺寸跟随车机上报的视口并限制在 1920x1080。
- **码率 8 Mbps → 4 Mbps。** 2.4 GHz 车内环境实测持续吞吐为 5-15 Mbps，8 Mbps 没有留下余量。自适应回退也收紧了：下限 1.5 Mbps、2 秒恢复窗口、0.5 Mbps 步进（过去会在 8 和 2 Mbps 之间反复震荡）。
- **帧率 30 → 24 → 20。** 现在的默认值是 20 fps（每帧 50 ms）。见 issue #71：2.5 小时导航让手机发烫，而降低帧率是继 VD 放大上限之后的第二个泄压阀。
- **TextureView → SurfaceView。** 跳过 Adreno 505 每帧的全屏 GL 合成（约 2-5 ms/帧）。一个 `outputSurfaceValid` 门控可防止在解码器随导航隐藏/显示时向已销毁的 surface 渲染。
- **线程优先级。** A53 没有大核，所以调度提示很重要：编码/解码/socket-drain 线程使用 `THREAD_PRIORITY_URGENT_DISPLAY`，`LifeWriter` 使用 `THREAD_PRIORITY_BACKGROUND`。
- **日志默认行为。** release 构建中 `carLogEnabled` 默认关闭（过去默认开启，日志帧会和视频争抢 2.4 GHz 链路）；`ConcurrentLinkedQueue.size()`（O(n)）改成了 `AtomicInteger`。
- **断连时清理 AppIconCache。** 过去约 80-150 个 PNG 会永久累积在 16 GB eMMC 上（占用 5-9 MB 堆内存）。新增的 `clear()` 在 `handleDisconnect` 中调用，`prepareAll` 用 `Semaphore(4)` 并行解码并回收中间 bitmap。
- **移除 `Debug.getPss()`。** 这个已废弃的 binder 调用过去每次连接都会执行，在 A53 上耗时 100-500 ms；现在只在崩溃时采集。
- **`@Immutable AppTileData`。** `AppInfo` 带一个 `ByteArray`，使 `AppTile` 不可跳过，任何 `appList` 重新赋值都会触发全量重组。改用排除 `iconPng` 的不可变包装后，网格可以跳过重组。

### 3. API 28 车机兼容

在真实的 BYD 车机（`k65v1_64_bsp`，Android 9 / API 28）上，`MediaCodecInfo.isHardwareAccelerated()`（API 29+）会抛 `NoSuchMethodError` 并杀死进程。删掉了那个推测性的硬件解码器选择逻辑，恢复使用 `createDecoderByType`（`REGULAR_CODECS` 本来就会把硬件解码器排在前面；在 BYD 上它会选到 `OMX.qcom.video.decoder.avc`）。`app-server` 和 `protocol` 的 `minSdk` 从 24 升到 26，NewApi lint 门禁重新启用。`am display move-stack`（API 29+）现在对 API 26-28 做了 `SDK_INT` 门控并打日志，而不是把 shell 失败当作成功。

### 4. UI 与交互

- **导航栏重设计：** 精简为 Eject（断连）/ Home / Back。通知按钮、最近任务栏、时钟和网络信息被移除，同时清理了跨四个模块的死代码（车机端 `NotificationScreen`、`RecentAppsState`；手机端 `NotificationService`；`FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 消息类型）。**本代码库中不存在通知转发。**
- **应用置顶：** 长按应用图标可 Pin to Top / Unpin，状态持久化在 SharedPreferences 中。
- **应用允许列表：** 新增的 `AllowlistScreen` 让用户选择哪些应用推送到车机；未选中的应用在 `sendAppList` 阶段就被过滤，从而缩小 wire payload 和车机的图标解码工作量。首次运行时常见导航应用会被预选。切换后会发送 `ACTION_ALLOWLIST_UPDATED`，让运行中的 service 重新发送列表，车机网格随之实时更新。
- **竖屏应用支持：** 动态 DPI 计算（`VideoConfig.calculateOptimalDpi`）让竖屏应用（高德、微信）不会在横屏 VD 里被压成细条；会话结束时恢复 IME（手机过去会残留 linkpc IME）；多指触摸注入索引已修复。
- **车机端启动 DPI 覆盖：** 握手新增 `dpiOverride` 字段（0 = 自动，[120, 480] = 用户值），由车机的 `startup_dpi` 设置驱动，横竖屏都会发送。修复了 1280x800 上默认 DPI 偏小的问题。
- **颜色 token 与字号 flooring：** 散落的 `Color.Gray` / `0xFF888888` 迁移到 `onSurfaceVariant`，重复的 error 红合并，标签 12sp → 14sp。“WiFi Direct Mode”重命名为“WiFi ADB Mode”；车机端的 `adb tcpip` 文案被移除。
- **旋转黑屏修复：** `onCarViewportChanged` 把状态置为 CONNECTING 并在旋转时重新部署 VD，但流式布局的门控用的是 `isConnected`（重新部署的约 2 秒内为 false），于是 `CarShell` 退回 `CarLaunchScreen`，`TextureView` 离开组合，新 surface 无法重启解码器。门控现在也接受 `state == CONNECTING && appList.isNotEmpty()`，让流式布局（及其 video-wait overlay）在旋转期间保持不动。

### 5. 会话稳定性

活跃会话不会被重连尝试杀死 —— 过去 Wi-Fi 网关重试或手动连接可能杀掉正在运行的会话。重连循环在连续 3 次 ADB 失败后停止。TCP ADB 在手机 IP 变化时重连，USB 不可用时自动回退到 TCP ADB。

### 6. PipelineServer 稳定化

`VirtualDisplayServer`（1287 行、9 个线程、2 个队列）变成 `PipelineServer`（单线程、0 队列）：帧时钟 → GL 渲染 → 编码器 drain → TCP 写，并带自然流控（TCP 写卡住就会阻塞下一次 `eglSwapBuffers`）。它还修复了：GL 纹理在临时 EGL 上下文创建后又被销毁（pipeline 线程绑定了 texture 0）；非交互式 shell 退出时 `app_process` 被杀（用 `shellBackground` 保持 shell 流附着）；以及物理面板的恢复顺序（过去在 shell 已关闭之后才调用 `input keyevent 224`）。

### 7. 诊断日志开关

Settings → Debug 新增了诊断日志开关。关闭时零磁盘写入。该状态通过 `LOG_TOGGLE` 数据消息传播到车机，并且自 issue #72 起在每次握手后自动同步，让车机日志跟随手机开关，而不是在重连时被静默重置。默认值：debug/预发布构建开启，release 构建关闭；用户显式选择后会持久化。

### 8. 简体中文 UI

client 和 server 都带有 `values-zh-rCN` / `values-zh`。应用字符串覆盖英语、葡萄牙语（巴西）、法语、俄语、白俄罗斯语、哈萨克语、乌克兰语、乌兹别克语和中文。

### 9. 代码健康

多轮 SRP/DRY 审计与重构：跨模块共享常量（`AppPrefs`、`AppTargets`、`VdDeploy`、`VdDeployArgs`、`WifiGatewayIp`、`AppIconLimits`）以及提取出的协作者。

- **`protocol-core` / `protocol`：** `NioWriter`（带超时的 socket channel 自旋写）、`BackoffPolicy`（参数化指数退避）、`DecodeLivenessTracker`、`ConnectionFactory`、`ConnectionKeepAlive`、`UsbAdbKeyStore`、`Ipv4Validator`、`AppIconLimits`；移除了 `UsbAdbConnection` 的死代码。
- **`vd-server`：** `AsyncSocketSender`、`AudioEncoderSelector`、`ProcessWatchdog`、`VideoEncoderSelector`、`PortAcceptor`、`ActivityDumpParser`、`VdDisplayHealer`。
- **`app-client`：** `ConnectionService` 瘦身；提取出 `ShizukuVdDeployer`、`ShizukuNav`、`ClientNetworkMonitor`、`CarAppInstallHost`、`AllowlistRepository`、`SessionState`；废弃 `InputInjectionService`。
- **`app-server`：** `CarConnectionService` 缩减到约 407 行；状态机拆分为 `CarSessionCoordinator`（9637/9638/9639/9640 多通道状态机与重握手）、`CarAdbTracker`（USB/TCP ADB 跟踪）和 `CarAppListReceiver`（应用列表与图标安全限制）。
- **`app-desktop`：** 使用 `AppIconLimits`、`DecodeLivenessTracker`、`BackoffPolicy`，与最新的协议基元对齐。

### 10. VD 泄漏修复、清理幂等、黑屏自愈

**根因：** 每次重连都会泄漏一个 VirtualDisplay。旧代码使用 `pkill -9`，跳过了 JVM shutdown hook，于是 `PipelineServer.cleanup()` 从未执行 —— VD 未释放，物理面板保持关闭，`screen_off_timeout` 设置丢失（快照捕获的是代码自己写入的哨兵值 `2147483647`）。几次重连之后车机屏幕就变黑了。

**修复（12 个文件）：** 一个停止请求哨兵（`/sdcard/DiLinkAuto/stop-request`），由引擎 watchdog 消费，使引擎走正常退出路径并执行完整的 `cleanup()`（ART 在裸 SIGTERM 下不会运行 shutdown hook）；一个基于 `pgrep -f` 的存活探测（toybox 拒绝 `pkill -0`）；一个用 `pgrep -f` 定位 pid 的 `kill -9` 兜底（而不是 `pkill -f`，后者会连 wrapper shell 一起杀掉，导致 ADB 结果无法读取）；一个 `AtomicBoolean` 的 `cleanupGuard`，让 7 个调用点不会重复执行 `cleanupSession()`（过去一次断连会触发 9 次“Force-waking physical display”）；两端都加上的 `waitForVdServerExit()`，使两个引擎不会争抢同一个 VD 和端口；`PhoneDisplayRestorer` 使用独立的 process-lifetime 作用域并带 `NonCancellable`；重排后的 `cleanup()`；`DisplayPowerController.restoreSetting()` 的值校验；以及移入 `protocol-core` 的 `BlackScreenDetector`（`BLACK_SCREEN_SUSTAIN_MS = 3000`），让车机与桌面端共用，`rehandshakeForBlackScreen()` 则被闩锁为每次会话最多尝试一次。

回归自动化位于 `scripts/`：`verify-blackscreen-fix.sh`、`e2e-reconnect-cleanup.sh`、`verify-reconnect-cleanup.sh`（通过真实桌面 UI 驱动的 32 项检查；最近一次完整运行 32/32，耗时 2 分 48 秒）。

### 11. Windows 接收端 `:app-desktop`

除车机之外，同一套协议还支持用 **Windows 桌面程序**作为接收端：手机屏幕镜像到 PC 窗口，鼠标即触摸。模块位于 `app-desktop/`（Kotlin/JVM + Compose Desktop 1.6.2，JDK 17），复用 `protocol-core` —— 手机侧零改动。

- **传输：** 三条 TCP 连接直连手机 —— 9637（控制 + 数据）、9638（视频）、9639（输入）—— 与车机一致，不经过中继。
- **部署分流：** 握手响应中的 `connectionMethod` 决定由谁部署 VD server。使用 Shizuku 时由手机部署；否则由桌面端用本机 `adb.exe` 部署（`connect` → 杀掉旧引擎**并等待它真正退出** → `exec app_process`），这要求 `config.json` 中 `"dev_mode": true`。
- **解码：** JavaCV/FFmpeg 软件解码加 D3D11VA 硬件解码，硬件在启动时失败或产出零帧时自动回退。
- **界面：** 三个导航页 —— 镜像 / 应用（启动器）/ 显示（DPI 覆盖、硬件解码开关、“应用并重连”、保持唤醒、手机物理屏）。
- **自愈：** 持续黑屏 10 秒会自动重连，每个进程最多两次。
- **配置与日志：** `%APPDATA%\DiLinkAuto`（`config.json` / `desktop.log`），可用 `DILINK_DESKTOP_HOME` 覆盖；`DILINK_ADB` 设置 adb 路径。

```bash
# 自包含 app-image（自带 JRE）：复制 build/compose/binaries/main/app/DiLinkAuto
./gradlew :app-desktop:createDistributable

# 无窗口探针：只验证握手/VD/fps；没有 Shizuku 时设置 DILINK_DEV_MODE=1 走 ADB 部署
app-desktop --probe
```

> 状态：Shizuku 路径与车机端一致；**ADB 部署路径尚未在真机上验证**。该模块不属于发布资产（它的 JavaCV/FFmpeg 依赖是 GPL，而本项目是 MIT）。

### 12. 单元测试与安全加固

**测试覆盖：** 有两个模块完全没有测试源集（`vd-server` 没有 `src/test`，`protocol` 的是空的）。从 `8157c86` 开始，全部 6 个模块都补上了 JUnit4 单元测试，覆盖此前未测的最高风险逻辑（协议分帧、ADB 加密基元、VD 部署参数、自适应码率、shell 引用门、版本门控、安装状态分类、崩溃报告格式）。随后一轮保持行为的“可测性 seam”又增加了 44 个用例。如今仓库在 6 个模块中共有 1,200+ 个 `@Test` 用例（JUnit4 + kotlinx-coroutines-test，没有 Robolectric/Mockito/MockK；Android 模块使用 `isReturnDefaultValues=true` 加手写假件）。清单、锁定的跨模块不变量和未决问题见 [docs/IMPLEMENTATION_REPORT_TESTING.md](../IMPLEMENTATION_REPORT_TESTING.md)。

**对端加固（`a7d2b72`）：** 过去每个 socket 对端都被视为可信 —— 一个 6 字节的畸形帧就能杀死手机进程或 shell UID 的 VD server。现在全部五个解码器在裸 `getX()` 之前都会用 `require()` 预检；reader 协程捕获 `Throwable`、记录原因并断开，而不是直接死掉；`MAX_PAYLOAD_SIZE` 从 128 MB 降到 16 MB，`NioReader` 的缓冲有硬上限并在帧边界回收。

**VD 通道鉴权（`8601fb1`）：** 9638/9639 端口过去接受同一 Wi-Fi 下的任意主机，让任何人都能以 shell 身份注入触摸并执行 `am start` / `pm uninstall`。现在它们只接受来自当前正在服务的接收端的连接；被拒绝的对端会被关闭，服务端继续等待。

**Shell 引用（`5eae3e7`）：** `VdDeploy` 命令行统一经过 `shellQuote`，对端可控字符串不会裸着进入 `sh`。

**桌面端（`1f6836a`）：** 握手响应中的 `adbPort` / `jarPath` 在使用前会先校验（过去会被直接传入部署流程，等于让任意对端执行任意代码）；adb 子进程不再泄漏。

**车机会话（`bc2634c` / `35d7821`）：** 会话使用 generation 闩锁，使 `cleanupGuard` 不会被网络变化路径提前消耗；黑屏恢复不再对解码器调用 `stop()`（没有人重启它，恢复路径反而保证了永久黑屏）。

**发布供应链：** release keystore 曾经入库；现在只做引用，签名密钥放在 CI 中（git 历史仍需清理）。CI 不再在 self-hosted runner 上运行 PR 代码（`9517205`），也不再产出 unsigned / 被过度裁剪的 APK 和孤儿产物（`efde81f`）。所有 workflow 都运行在 GitHub 托管的 `ubuntu-latest` 上。

---

## 当前功能状态

**已可用：**

- H.264 视频镜像，默认 20 fps、4 Mbps CBR、Main profile（针对入门级 DiLink 4.0 调优；高配车机同样可用）
- 音频镜像：AAC-LC 48 kHz 立体声，128 kbps，走独立 TCP 连接（9640），150 ms 抖动缓冲，以 46.875 fps 恒定静音补帧，双向 `FEATURE_AUDIO` 能力协商，客户端音频开关持久化
- 完整触摸输入（多点触控、双指缩放）
- 应用启动器：搜索、字母排序、64dp 图标、长按菜单（卸载、应用信息、置顶）
- 应用允许列表：只把选中的应用推送到车机
- 车机端启动 DPI 覆盖（修复 1280x800 上默认 DPI 偏小）
- 方向跟随：车机旋转时重建 VD，竖屏应用不再被压成细条
- Shizuku 支持：无需 USB ADB 即可部署 VD server，并用静默 `pm install` 自更新车机应用
- 车机应用自更新：手机比较 `versionCode` 并通过 Wi-Fi ADB 更新车机应用
- 流式传输时关闭手机屏幕（省电）；断连时恢复物理面板
- 引导式权限授权
- 简体中文 UI 以及另外 8 种 UI 语言
- 直连 VD 架构：手机不做中继；视频、触摸和音频直通
- VD 泄漏修复：基于哨兵的优雅停止（完整执行 `cleanup()`，仅在超时时用 `kill -9` 兜底）、幂等清理 guard、VD 退出等待
- 黑屏自愈：车机在持续黑屏 3 秒后重建 VD（重握手）；Windows 接收端使用同一个检测器，窗口为 10 秒
- Windows 接收端（`app-desktop`）：镜像窗口、鼠标触摸、应用启动器、DPI/硬解面板、`jpackage` app-image；ADB 部署路径已实现但**尚未在真机上验证**

**已移除（相对合并前的上游）：**

- 通知转发（手机通知 → 车机屏幕）：导航栏通知按钮、通知列表以及 `FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 协议消息均已移除
- 最近任务栏、时钟、网络信息

**未实现：**

- 媒体控制
- 导航小部件（音频镜像已完成）

**已知限制：**

- Samsung Auto Blocker 必须关闭才能使用 USB ADB（设置 → 安全 → Auto Blocker → 关闭）。
- Samsung 电池管理需要显式豁免。
- Samsung Knox 首次访问虚拟显示器时可能弹出安全提示。
- 手机热点必须手动开启（Android 16 限制）。
- 偶发花屏，源自解码器重启竞争；下一个关键帧（约 1 秒）即可恢复。
- 负载下流式延迟约为 100-200 ms。
- 部分应用不会铺满屏幕（信箱化/仅竖屏）。DiLink-Auto 镜像的是横屏虚拟显示器，因此不支持横屏的应用会出现黑边 —— 这由应用自身决定。
- 在 API 26-28 车机上，如果 `DisplayControl` 反射失败，`cmd display power-reset` / `power-off` 这个 shell 回退也是 API 29+，所以物理面板可能无法恢复（它会打日志，而不是静默失败）。
- 非英语 UI 语言下不显示安装进度：`InstallStatus.parse` 按英文子串对本地化状态字符串分类，所以中文（及其他语言）会落入 `IDLE`。已用测试固定，未修复（[C-6](../IMPLEMENTATION_REPORT_TESTING.md)）。
- 车机应用首次安装可能在极端版本号下被跳过：车机应用不存在时 `readInstalledVersion` 返回 `"0"`，所以内嵌的 `versionName` 为 `0` / `0.0` / `0.0.0` 会被判为“已是最新”。当前 `versionName` 是 `0.19.0`，因此不会触发（[C-5](../IMPLEMENTATION_REPORT_TESTING.md)）。

---

## 工作原理

1. **开启热点** —— 打开手机的 Wi-Fi 热点，车机连接到它。
2. **插上** —— 把手机连接到车机的 USB 口（或加入同一个 Wi-Fi 网络）。
3. **自动安装** —— 手机通过 Wi-Fi ADB 安装车机应用（仅首次，点一下即可）。
4. **自动连接** —— 四条 Wi-Fi TCP 流：控制（9637）、视频（9638）、触摸（9639）、音频（9640，协商后开启）。
5. **使用你的应用** —— 从车机启动器启动任意应用；它运行在手机上，显示在车机屏幕上并接收触摸。

手机在虚拟显示器上运行应用并编码为 H.264，推送到车机。车机屏幕上的触摸事件会作为真实触摸事件注入回手机。手机的物理屏幕保持关闭以省电，并且仍可独立使用。

架构、连接流程与设计决策：[docs/architecture.md](../architecture.md)。

---

## 系统要求

**手机：**

- Android 10+（`minSdk = 29`）
- 已开启 USB 调试（开发者选项）
- All Files Access 权限（首次启动时引导授权）

**车机：**

- BYD DiLink 3.0+，已在 **比亚迪 秦 PLUS DM-i 2023 款 冠军版 55KM 领先型**（入门级 DiLink 4.0，骁龙 439 / Android 9 / 1280x800）上验证
- Android 8.0+（`minSdk = 26`）
- 一个空闲的 USB-A 口（或同一个 Wi-Fi 网络）

**手机热点必须开启** —— 车机连接到它。无需配对码，无需 Google 账号。

**不需要互联网连接。** DiLink-Auto 通过手机热点在本地流式传输；手机和车机直接通信。只有手机上运行的应用才需要互联网。

---

## 安装

从 [Releases](https://github.com/andersonlucasg3/dilink-auto-android/releases/latest) 下载 APK，或自行构建：

1. **构建：** `./gradlew :app-client:assembleDebug`
2. **安装到手机：** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **开启 USB 调试**（设置 → 开发者选项）
4. **打开 DiLink-Auto** 并按引导授权 All Files Access
5. **开启热点并插入车机** —— 首次运行时车机应用会通过 Wi-Fi ADB 自动安装

车机 APK 和 VD server JAR 都打包在手机 APK 内部，因此无需单独在车机上安装任何东西，安装车机应用也不需要互联网。

> **签名升级提醒：** 从 debug 签名的构建切换为正式签名构建会改变证书 —— **请先卸载旧包**，否则安装会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失败。完整的发布与签名规则：[docs/release.md](../release.md)。

---

## 项目结构

手机 APK（`app-client`）内嵌车机 APK（`app-server`）和 VD server JAR（`vd-server`）。接收端是可替换的：同一套协议既能跑在车机上（`app-server`），也能跑在 Windows 上（`app-desktop`）。

```
DiLink-Auto/
├── protocol-core/  纯 JVM 共享库（帧、消息、常量、VD 部署序列；不依赖 Android）
├── protocol/       平台适配（ADB、Android hooks、USB/TCP 连接）
├── app-client/     手机 APK —— 编排、VD 部署、车机自更新、应用允许列表
├── app-server/     车机 APK —— UI、连接状态机、视频解码器
├── app-desktop/    Windows 接收端 —— 镜像窗口、FFmpeg 解码、adb 部署、jpackage
├── vd-server/      VirtualDisplay server（编译成 JAR，由手机部署）
├── scripts/        自动化（日志验证、端到端重连/清理）
├── docs/           文档（多语言入口）
└── gradle/         构建系统
```

| 模块 | 角色 | minSdk |
|--------|------|--------|
| `protocol-core` | 纯 JVM 共享协议库（Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector） | — |
| `protocol` | 共享协议库（UsbAdbConnection、AdbProtocol、VideoConfig、ConnectionFactory、ConnectionKeepAlive、UsbAdbKeyStore） | 26 |
| `app-client` | 手机应用（ConnectionService、ShizukuVdDeployer、ClientNetworkMonitor、CarAppInstallHost、AllowlistRepository、FileLog） | 29 |
| `app-server` | 车机应用（CarSessionCoordinator、CarAdbTracker、CarAppListReceiver、AudioPlayer、VideoDecoder、CarShell、HomeScreen、PersistentNavBar） | 26 |
| `app-desktop` | Windows 接收端（DesktopApp、DesktopConnectionService、VideoDecodePipeline、SwingVideoView） | — |
| `vd-server` | Shell UID 进程（PipelineServer、AudioPipeline、AudioEncoderSelector、PortAcceptor、TouchInjector、GlPipeline、DisplayPowerController） | 29 |

---

## 构建

```bash
# 构建手机 APK（会运行 buildVdServer + embedServerApk，把 vd-server.jar 和 app-server.apk 打进 client assets）
./gradlew :app-client:assembleDebug

# APK 位置：
# app-client/build/outputs/apk/debug/app-client-debug.apk  （手机端，内嵌车机 APK）

# 单元测试（6 个模块，JUnit4 + kotlinx-coroutines-test，无 Robolectric/Mockito/MockK）
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

需要 JDK 17 和 Android SDK（compileSdk 34）。release 构建需要 `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD`（`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` 可选）；缺少必需变量会在配置阶段失败，而不是静默产出一个无法覆盖正式包的 unsigned APK。keystore 本身永不入库。

在 Windows 上，等价命令使用 `.\gradlew.bat`。

---

## 文档

完整文档（安装、架构、协议、客户端、服务端、进度）入口在 [docs/README.md](../README.md)。流程规范见 [AGENTS.md](../../AGENTS.md)、[docs/ISSUE_GUIDE.md](../ISSUE_GUIDE.md)、[docs/PR_GUIDE.md](../PR_GUIDE.md) 和 [docs/release.md](../release.md)。

---

## 贡献

欢迎提交 Pull Request。开发在 `main` 和 `develop` 上进行（git-flow：功能分支从 `develop` 拉出，发布合并进 `main` 并回灌）。提交 PR 前，请确认上面的 `./gradlew :app-client:assembleDebug` 与单元测试命令均通过。

---

## License

MIT，见 [LICENSE](../../LICENSE)。最初由 [andersonlucasg3](https://github.com/andersonlucasg3) 创建；感谢原作者，也感谢所有在 fork 中让项目延续下去的人。
