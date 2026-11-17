# DiLink-Auto

把手机上的应用投到汽车车机屏幕上,完整触摸交互。开源,不依赖 Google 服务。

这是 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android) 的一个 fork,面向**中国手机 ROM + BYD DiLink 车机**的使用场景做了适配,并专门针对 **DiLink 4.0 低配车机**做了性能优化,使其在弱硬件上从"幻灯片"变成可用。

实测车型:**比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**。该款车机是 DiLink 4.0 低配方案 —— 骁龙 439(8x Cortex-A53 全小核、Adreno 505)、4GB RAM、16GB eMMC、1280x800 屏、仅 2.4GHz WiFi、Android 9 / API 28、H.264 硬件解码上限 1080p。本 fork 的性能调优全部围绕这套硬件展开。

当前版本:**20261117-2247**(`versionCode 26111722`,由版本号日期派生)。

版本号采用 `YYYYMMDD-HHMM` 日期-时间格式(issue #224):日常迭代按天发布,日期时间能直接读出这一包是哪一轮。`versionCode` 由 `app.versionName` 自动派生,不需要手工维护。

原本要解决的矛盾是:手机(小米 HyperOS 国行版等)装不了 Android Auto,而车机只支持 Android Auto —— 中间没有桥。DiLink-Auto 对任何 Android 10+ 手机都通用,有没有 Google 服务都行。

---

## 这个 fork 相对 upstream 走了哪些路

下面是从 fork 点(`84df5cb`,upstream/main)到当前 `main` 的关键技术演进,按逻辑分组。每一条都对应实际 commit,不是规划。

### 1. 架构层:直连 VD 流式架构

手机端不再做视频/触摸/音频中继。VD Server 直接绑定 `9638`(视频)、`9639`(输入)、`9640`(音频，双向协商后开启)端口与接收端对话，省掉了每帧 4 次 socket + 2 次进程上下文切换，变成 2 次 socket + 0 次上下文切换。手机端退化为纯编排器：握手与能力协商、VD 生命周期(`VD_PORTS_BOUND`)、车机日志路由。涉及协议新增 `FrameCodec.readFrameBlocking()`、`MessageType.VD_PORTS_BOUND`、`AudioPayload`，以及 `VirtualDisplayClient` 简化为只管生命周期。后续一系列 commit 修了直连架构下的生命周期通道竞争、部署竞争、栈导航、EGL/GL 纹理上下文等问题，并实现了音视频全链路直连闭环。

### 2. DiLink 4.0 低配车机适配(核心)

针对车机硬件(骁龙 439,8x Cortex-A53 全小核、Adreno 505、4GB RAM、16GB eMMC、1280x800、仅 2.4GHz WiFi、H.264 硬解上限 1080p)的九项修复:

- **编码尺寸 cap 到 1920x1080**:原来手机端为了修 IME 裁剪 bug,把 VD 尺寸放大到手机物理像素(1080x2340 → 编码 2340x1658),超过 439 的硬解上限,触发软解,8x A53 跑 >1080p H.264 是个位数帧率。修法是解耦 VD 尺寸和编码尺寸 —— VD 保持放大(保住 IME 裁剪修复),编码尺寸用车机上报尺寸并 clamp 到 1920x1080。`PipelineServer` 早已支持 `EW EH` 参数分离,只需改三条 deploy 路径(phone Shizuku、car USB/TCP ADB、car TCP ADB direct)的传参。
- **码率 8 Mbps → 4 Mbps**:2.4GHz 车内环境实测持续吞吐 5-15 Mbps,8 Mbps 没留余量。自适应回退也收紧:floor 1.5 Mbps、2 秒恢复窗、0.5 Mbps 步进(原来是 2 Mbps floor、5 秒、1 Mbps 步进,会反复在 8 和 2 之间震荡)。
- **帧率 30 → 24**:每帧预算 33ms → 42ms,WiFi/GPU/分配负载降 20%。1280x800 下 24fps 对车机镜像在视觉上够用。
- **TextureView → SurfaceView**:绕开 Adreno 505 每帧全屏 GL 合成(约 2-5ms/帧)。解码器跨导航 hide/show 时用一个 `outputSurfaceValid` 门控防止往已销毁 surface 渲染。
- **线程优先级**:A53 没有大核,调度提示有用。编码/解码/socket-drain 线程设 `THREAD_PRIORITY_URGENT_DISPLAY`,`LifeWriter` 设 `THREAD_PRIORITY_BACKGROUND`。
- **日志默认行为**:release 构建里 `carLogEnabled` 默认关(原来默认开,日志帧和视频抢 2.4GHz 带宽);`ConcurrentLinkedQueue.size()`(O(n))换成 `AtomicInteger`。
- **AppIconCache 断连清理**:原来 ~80-150 个 PNG 永久累积在 16GB eMMC,断连不清理(占 5-9MB 堆)。新增 `clear()` 在 `handleDisconnect` 调用;`prepareAll` 用 `Semaphore(4)` 并行解码,中间 bitmap 及时 recycle。
- **移除 `Debug.getPss()`**:每次连接都跑的 deprecated binder 调用,在 A53 上耗时 100-500ms,移到崩溃时才采集。
- **`@Immutable AppTileData`**:`AppInfo` 带 `ByteArray` 让 `AppTile` 不可跳过,任何 `appList` 重赋值都全量重组。改用排除 `iconPng` 的不可变包装,网格可跳过重组。

### 3. API 28 车机兼容

实测 BYD 车机(`k65v1_64_bsp`,Android 9 / API 28)上 `MediaCodecInfo.isHardwareAccelerated()`(API 29+)抛 `NoSuchMethodError` 直接杀进程。修法是删掉那个 speculative 的硬件解码器 picker,回到 `createDecoderByType`(`REGULAR_CODECS` 已经把硬件解码器排在前面,在 BYD 上会选到 `OMX.qcom.video.decoder.avc`)。顺手把 app-server `minSdk` 24 → 26,protocol 也跟着升到 26,重新启用 NewApi lint gate。`am display move-stack`(API 29+)在 API 26-28 上做了 `SDK_INT` 门控并打日志,而不是把 shell 失败当成功。

### 4. UI 与交互

- **导航栏重设计**:精简为 Eject(断连)/ Home / Back 三键。通知按钮、最近任务栏、时钟、网络信息全部移除,相关死代码跨四个模块清理(车机端 `NotificationScreen`、`RecentAppsState` 删除,手机端 `NotificationService` 删除,`FOCUSED_APP` / `APP_SHORTCUTS` / `NOTIFICATION_*` 消息类型从 protocol、`ConnectionService`、`VirtualDisplayClient`、`PipelineServer` 移除)。**通知功能在这个 fork 里已移除**,upstream README 里描述的通知相关特性不再适用。
- **应用置顶(pinning)**:长按应用图标弹出菜单支持 Pin to Top / Unpin,基于 SharedPreferences。
- **应用允许列表(Allowlist)**:新增 `AllowlistScreen`,用户选择哪些应用推送到车机,未选中的在 `sendAppList` 阶段就被过滤掉,缩小 wire payload 和车机端图标解码工作量。首次运行预置常见地图应用。切换后发 `ACTION_ALLOWLIST_UPDATED` 让运行中的 service 重发列表,车机网格实时更新。
- **竖屏应用优化**:动态 DPI 计算(`VideoConfig.calculateOptimalDpi`)让竖屏应用(高德、微信)在横屏 VD 里不被压成细条;IME 在会话断开时自动恢复(原来手机会残留 linkpc IME);多指触摸注入索引修复。
- **车机端启动 DPI 覆盖**:握手新增 `dpiOverride` 字段(0 = 自动,[120, 480] = 用户指定),车机端 `startup_dpi` 设置项驱动,横竖屏重握手都带上。解决 1280x800 默认 DPI 偏小的问题。
- **颜色 token 化、类型 flooring**:重构散落的 `Color.Gray` / `0xFF888888` 到 `onSurfaceVariant`,合并重复的 error 红,标签 12sp → 14sp。重命名 "WiFi Direct Mode" → "WiFi ADB Mode",移除车机端 `adb tcpip` 文本。
- **旋转黑屏修复**:旋转时 `onCarViewportChanged` 把状态置 CONNECTING 并销毁 VD 重部署,但流式布局的门控用了 `isConnected`(重部署 2 秒内为 false),导致 `CarShell` 翻回 `CarLaunchScreen`,`TextureView` 被移出组合,新 surface 无法重启解码器。门控改为同时接受 `state == CONNECTING && appList.isNotEmpty()`,流式布局(及其 video-wait overlay)在旋转期间保持不动。

### 5. 会话稳定性

活跃会话不会被重连尝试杀掉 —— 之前 WiFi 网关重试或手动连接会杀掉正在跑的 session。重连循环在连续 3 次 ADB 失败后停止。TCP ADB 在手机 IP 变化时重连,USB 不可用时自动回退到 TCP ADB。

### 6. PipelineServer 稳定化

从 `VirtualDisplayServer`(1287 行、9 线程、2 队列)重构为 `PipelineServer`(单线程、0 队列):帧时钟 → GL 渲染 → 编码器 drain → TCP 写,自然流控(TCP 卡住就阻塞下一帧 `eglSwapBuffers`)。修了 GL 纹理在临时 EGL 上下文里创建后被销毁、pipeline 线程绑到 texture 0 的 bug;修了 `app_process` 被非交互 shell 退出的 kill(用 `shellBackground` 保持 shell 流);修了物理面板恢复顺序(原来 shell 已关才调 `input keyevent 224`)。

### 7. 日志开关

Settings → Debug 加了诊断日志开关。off 时零磁盘写入。通过 `LOG_TOGGLE` data 消息同步给车机。默认行为:debug/pre-release 构建开,release 构建关;用户显式设置后持久化。

### 8. 简体中文

client 和 server 都加了 `values-zh-rCN` / `values-zh`。这是面向中国场景的基础适配。

### 9. 代码健康

最近多轮 SRP/DRY 审计与重构 pass：跨模块共享常量(`AppPrefs`、`AppTargets`、`VdDeploy`、`VdDeployArgs`、`WifiGatewayIp`、`AppIconLimits`)、协作者提取与公共基元沉淀。
- **第三轮 SRP/DRY 落地**: `PipelineServer` 抽出 `PersistentShell`/`CarCommandRouter`/`LifecycleWriter`，`AppListBuilder` 降至 80 行。
- **第四轮架构与职责解耦（Refs #87, #92）**:
  - `protocol-core` / `protocol`：抽离 `NioWriter`（SocketChannel 自旋写与超时）、`BackoffPolicy`（参数化指数退避）、`DecodeLivenessTracker`（解码存活判定与基线管理）、`ConnectionFactory`、`ConnectionKeepAlive`、`UsbAdbKeyStore`、`Ipv4Validator` 与 `AppIconLimits`；清理 `UsbAdbConnection` 死代码。
  - `vd-server`：抽离 `AsyncSocketSender`（异步发送模板）、`AudioEncoderSelector`、`ProcessWatchdog`（停止哨兵与看门狗解耦）、`VideoEncoderSelector`、`PortAcceptor`（多端口非阻塞绑定）、`ActivityDumpParser`、`VdDisplayHealer`；`LifecycleWriter` 接入 `AsyncSocketSender`。
  - `app-client`：`ConnectionService` 瘦身，剥离 `ShizukuVdDeployer`、`ShizukuNav`、`ClientNetworkMonitor`、`CarAppInstallHost`、`AllowlistRepository`、`SessionState`；废弃 `InputInjectionService`。
  - `app-server`：`CarConnectionService` 大幅瘦身至 407 行，状态机拆分为 `CarSessionCoordinator`（9637/9638/9639/9640 多通道状态机与重握手）、`CarAdbTracker`（USB/TCP ADB 双轨跟踪）、`CarAppListReceiver`（应用列表与图标安全门限）；`VdServerDeployer` 解耦。
  - `app-desktop`：接入 `AppIconLimits`、`DecodeLivenessTracker`、`BackoffPolicy`，对齐最新协议基元。

### 10. VD 泄漏修复 + 清理幂等 + 黑屏自愈(v0.18.0-dev-13)

**根因**:每次重连都泄漏一个 VirtualDisplay。旧代码用 `pkill -9` 杀 VD 进程,跳过了 JVM shutdown hook,`PipelineServer.cleanup()` 从未执行 —— 导致 VD 不释放、物理面板保持关屏、`screen_off_timeout` 设置丢失(快照捕获的是自己写入的 `2147483647` 哨兵值)。连续几次重连后车机变黑屏。

> **2026-10-10 后续修订(实机测得两个假设不成立)**:① ART 的 `app_process` 收 SIGTERM **不跑** shutdown hook(上面的"两阶段停止"实际等价于强杀),停止改为 **stop-request 哨兵文件**(引擎 watchdog 消费后走正常退出路径跑完整 `cleanup()`,超时才 `pkill -9` 兜底);② toybox 拒收 `pkill -0`(`bad -L '0'`),两个存活探针恒报 GONE、"等旧引擎退出"从未真正等待,探针改用 `pgrep -f`。已在 MI 9 上端到端验证。

**修复(跨 12 个文件)**:

- **`VdDeploy.gracefulStopCommand`**(原 `stopCommand` 两阶段停止已废弃):写停止哨兵 `/sdcard/DiLinkAuto/stop-request`,引擎 watchdog 消费后走**正常退出路径**跑完整 `cleanup()` —— ART 上裸 SIGTERM 不跑 shutdown hook,两阶段停止等价于强杀,是 VD 泄漏的根源。
- **`VdDeploy.killCommandForce`**:兜底强杀用 `pgrep -f` 定位 + 按 pid `kill -9`(不用 `pkill -f`:内联执行时它会连发起命令的 wrapper shell 一起杀、adb 返回 137,"这一刀到底命中没有"从此读不出来)。
- **`VdDeploy.PROCESS_PATTERN`**:`[P]ipelineServer` 方括号技巧 —— **只对 `pgrep` 有效**(防自匹配),`pkill -f` 不可依赖它(2026-10-10 MI 9 实测)。
- **`VdDeploy.probeCommand` / `probeExitCodeCommand`**:`pgrep -f` 存活探测(toybox 拒绝 `pkill -0`,曾让探针恒报 GONE、"等旧引擎退出"变成空转)。
- **`ConnectionService.cleanupGuard`**:`AtomicBoolean` 幂等 guard,防止 7 个调用点重复执行 `cleanupSession()`(实测 1 次断连 → 9 次 "Force-waking physical display")。新会话开始时 `resetCleanupGuard()`。
- **`ShizukuManager.waitForVdServerExit()`**:轮询直到 VD 进程真正退出,再启动新引擎,防止两个实例争抢同一个 VirtualDisplay / DTA / 9638-9639 端口。
- **`VdServerDeployer.waitForVdServerExit()`**:车机端同样等待旧实例退出后再部署。
- **`PhoneDisplayRestorer`**:自建 process-lifetime `CoroutineScope`(不再是 Service scope,`onDestroy` 不会取消它)。`NonCancellable` 上下文,确保 `pkill` 后 `cmd display power-reset` 不会被中断。`inFlight` 单飞 guard 合并并发请求。
- **`PipelineServer.cleanup()` 重排序**:先重置全局 window/rotation 状态,再移回前台 app,再恢复面板/IME,最后释放线程/GL/编码器/VD。
- **`VirtualDisplayCreator.create()` 拆分**:`create()` 不再调用 `configureDisplayEnvironment()`,新增 `configureEnvironment()` 方法 —— 必须在 `saveCurrentIme()` 快照**之后**调用,否则快照捕获的是自己的写入值。
- **`DisplayPowerController.restoreSetting()`**:写入前验证值合理性(非空、非 null/undefined、数字 > 0),防止写入哨兵值。
- **`VideoDecoder.onSustainedBlackScreen`**:持续黑屏检测 —— 仅在连续 3 秒以上微小关键帧后才触发,3 帧的短暂黑帧(启动/重建时的正常瞬态)不会触发重连风暴。判定现由 `protocol-core` 的 `BlackScreenDetector` 承载(`BLACK_SCREEN_SUSTAIN_MS = 3000`),车机与桌面共用。
- **`CarConnectionService.rehandshakeForBlackScreen()`**:检测到持续黑屏后,通过重新握手让手机重建 VD。`blackScreenRecoveryInFlight` 闩锁确保每次会话最多触发一次。
- **`scripts/verify-blackscreen-fix.sh`**:自动化日志验证脚本,检查 VD 泄漏、cleanup 幂等、黑屏自愈、停机路径。
- **`scripts/e2e-reconnect-cleanup.sh` / `scripts/verify-reconnect-cleanup.sh`**:**「应用并重连 + 关窗」收尾清理的端到端实机回归**(2026-10-10 MI 9 全流程自动化):预检 → 构建门 → APK 内嵌 dex 符号校验 → 装包重启 → 起桌面端 → UI 驱动重连/关窗 → 设备终态断言 → 日志离线断言(探针跃迁 ALIVE→GONE、必须「引擎已优雅退出」且无「强制 kill」、耗时 < 12s 预算、`__DILINK_SYNC__` 排水回显、`Cleanup complete`、`screen_off_timeout` 恢复非哨兵、二次握手)。**已实测(2026-10-10 终轮):32 项全过 / 0 失败 / 2m48s**。

### 11. Windows 接收端 `:app-desktop`(v0.18.0-dev-13 起)

车机之外,同一套协议还支持用 **Windows 桌面程序**当接收端:手机画面镜像到 PC 窗口,鼠标即触摸。模块在 `app-desktop/`(Kotlin/JVM + Compose Desktop 1.6.2,JDK 17),协议实现复用 `protocol-core/` —— 手机侧零改动。

- **链路**:桌面端直连手机的 **9637(控制+数据)/ 9638(视频)/ 9639(输入)** 三条 TCP,与车机端完全一致,不经中继。
- **部署分流**:握手响应里的 `connectionMethod` 决定谁部署 VD server —— 手机有 Shizuku 就自己起;没有则由桌面端用本机 `adb.exe` 部署(`connect` → 杀旧引擎**并等它真的退出** → `exec app_process` 前台启动),需要 `config.json` 里 `"dev_mode": true` 显式同意(否则会终止会话并说明原因)。
- **解码**:JavaCV/FFmpeg 软解 + D3D11VA 硬解(启动失败或"起来但零帧"自动回退软解);解码器重建时重放 CONFIG 并等下一个 IDR。
- **界面**:左侧导航栏三页 —— 镜像 / 应用(启动器) / 显示(DPI 覆盖、硬解开关、「应用并重连」、本机常亮、手机物理屏开关)。视频画面用 Swing `JPanel` 承载(见 `SwingVideoView`),所以只在"镜像"页组合。
- **自愈**:持续 10 秒黑屏(编码器/VD 卡死但 TCP 仍通)会自动重连,每进程最多 2 次 —— 手机合法地停在暗色界面时不会陷入重连风暴。
- **配置与日志**:`%APPDATA%\DiLinkAuto`(`config.json` / `desktop.log`),可用环境变量 `DILINK_DESKTOP_HOME` 覆盖;`DILINK_ADB` 可指定 adb 路径。

```bash
# 免安装 app-image(自带精简 JRE):复制 build/compose/binaries/main/app/DiLinkAuto 整个目录到没装过 JDK 的机器即可双击运行
./gradlew :app-desktop:createDistributable

# 无窗口探针:只验链路(握手/VD/帧率),不解码不渲染;无 Shizuku 时需 DILINK_DEV_MODE=1 才走 ADB 部署
app-desktop --probe
```

> 状态:Shizuku 路径与车机端同源;**ADB 部署路径尚未真机验证**(待办见 `.plan/windows-client-plan.md`)。

### 12. 单元测试与安全加固(v0.18.0-dev-13 之后)

**测试覆盖**:此前两个模块完全没有测试源集(`vd-server` 无 `src/test`、`protocol` 的 `src/test` 为空)。`8157c86` 起给 6 个模块补齐 JUnit4 单元测试 —— 新增 18 个测试文件、139 个用例,覆盖此前零覆盖的最高风险纯逻辑面(协议帧、ADB 加解密基元、VD 部署参数、自适应码率、shell 引用门、版本门槛、安装状态分类、崩溃报告格式),全部离线 Gradle 实测通过。之后又落地一组"为可测性重构"(5 个提交:`1b6092c` / `6d25390` / `fa00527` / `bbd6fe0` / `5d576e8`,均行为不变)再增 44 个用例(首批 139 + seam 批次 44 = 183)。当前 `src/test` 共 84 个文件、578 个 `@Test` 用例。测试栈是 JUnit4 + kotlinx-coroutines-test,**没有引入 Robolectric / Mockito / MockK**,Android 模块靠 `isReturnDefaultValues=true` 加手写假件。清单、锁定的跨模块不变量、遗留隐患见 [docs/IMPLEMENTATION_REPORT_TESTING.md](./docs/IMPLEMENTATION_REPORT_TESTING.md)。

**协议层对端加固**(`a7d2b72`):此前每个 socket peer 都被当作自己人 —— 一个 6 字节的畸形帧就能杀死手机进程或 shell UID 的 VD server。现在五个解码器的裸 `getX()` 前都有 `require()` 预检;reader 协程改为捕获 `Throwable`、记录原因后断开,而不是让进程死掉;`MAX_PAYLOAD_SIZE` 从 128MB 降到 16MB,`NioReader` 缓冲有硬上限并在帧边界回收。

**VD 通道鉴权**(`8601fb1`):VD server 的 9638/9639 原先对任意主机开放,同 WiFi 下的任何人都能以 shell UID 驱动触摸注入和 `am start` / `pm uninstall`。现在只接受正在服务的接收端发起的连接,被拒绝的对端会被关闭并继续等待真正的对端。

**shell 引用**(`5eae3e7`):`VdDeploy` 命令行统一走 `shellQuote`,对端可控字符串不再裸进 `sh`。

**桌面端**(`1f6836a`):把握手响应里的 `adbPort` / `jarPath` 直接用于部署(等于让任意对端执行任意代码)改为先校验;adb 子进程不再泄漏。

**车机会话**(`bc2634c` / `35d7821`):会话改用 generation 闩锁,`cleanupGuard` 不再被"网络变化"路径提前消耗;黑屏自愈不再把解码器 `stop()`(停掉后没人重启它,恢复路径必然永久黑屏)。

**发布供应链**:release keystore 曾入库,已改为只引用不提交、签名密钥走 CI secret(git 历史待清理);CI 不再在 self-hosted runner 上跑 PR 代码(`9517205`),也不再产出 unsigned / 被过度裁剪的 APK 与孤儿产物(`efde81f`)。

---

## 当前功能状态

**已可用:**
- H.264 视频镜像,24fps、4Mbps CBR、Main profile(针对 DiLink 4.0 低配车机调校;高配车机也兼容)
- 音频流传输：AAC-LC 48kHz 双声道立体声、128kbps、9640 独立 TCP 连接、150ms 抖动缓冲、46.875 fps 恒定静音补帧与统计、手机/车机双向 `FEATURE_AUDIO` 能力位协商、客户端音频开关持久化
- 完整触摸输入(多点触控、双指缩放)
- 应用启动器:搜索、字母排序、64dp 图标、长按菜单(卸载、应用信息、置顶)
- 应用允许列表:只把选中的应用推到车机
- 车机端 DPI 覆盖:解决 1280x800 默认 DPI 偏小
- 横竖屏跟随:车机旋转时 VD 跟着重建,竖屏应用不再被压成细条
- Shizuku 支持:免 USB ADB 部署 VD server,`pm install` 静默自更新
- 车机应用自更新:手机检测到车机应用版本旧(versionCode 比对)就走 WiFi ADB 更新
- 流式传输时手机屏幕关闭(省电),断连后物理面板恢复
- 引导式权限授权
- 简体中文 + 8 种其他语言(英、葡、俄、白俄、法、哈、乌、乌兹)
- 直连 VD 架构:手机不做中继,视频/触摸/音频直通
- VD 泄漏修复:停止哨兵优雅停机(引擎跑完整 cleanup,超时兜底强杀)、清理幂等 guard、VD 退出等待 —— 每次重连不再泄漏 VirtualDisplay
- 黑屏自愈:车机端检测到持续 3 秒以上黑屏后触发重建 VD(重握手);持续黑屏判定已上移到 `protocol-core`,Windows 接收端用同一套判据(10 秒窗口)。恢复路径原先会 `stop()` 解码器却没人重启它,反而把屏幕永久留在黑屏 —— 已修(`35d7821`)
- Windows 接收端(`app-desktop`):镜像窗口 + 鼠标触摸 + 应用启动器 + DPI/硬解面板 + adb 部署,`jpackage` 出免安装 app-image

**已移除(相对 upstream):**
- 通知转发(手机通知 → 车机)—— 导航栏通知按钮、通知列表、相关协议消息全部删除
- 最近任务栏、时钟、网络信息

**未实现:**
- 媒体控制
- 导航小部件

**已知限制:**
- Samsung Auto Blocker 必须关掉才能用 USB ADB(设置 → 安全 → Auto Blocker → 关)。
- Samsung 电池管理需要显式豁免。
- Samsung Knox 首次访问虚拟显示器时可能弹安全提示。
- 手机热点需手动开启(Android 16 限制)。
- 偶发花屏 —— 解码器重启竞争,下一个关键帧(~1s)恢复。
- 流式延迟在负载下约 100-200ms。
- 部分应用不铺满屏幕(信箱化/仅竖屏)。DiLink-Auto 把横屏虚拟显示器镜像到车机,不支持横屏方向的应用会出现黑边 —— 这由应用自身决定,镜像侧无法解决。
- API 26-28 车机上,若 DisplayControl 反射失败,`cmd display power-reset`/`power-off` shell 回退也是 API 29+(无 `power-on` 子命令),物理面板可能无法恢复(会打日志,不再静默)。
- 安装进度在中文等非英文界面语言下不显示 —— `InstallStatus.parse` 只按英文子串分类安装状态串,而状态串本身是本地化文案;中文界面下全部落入 `IDLE`,阶段勾选与错误颜色都不出现。已用测试固定现状,未修(`docs/IMPLEMENTATION_REPORT_TESTING.md` C-6)。
- 车机应用首装在极端版本号下可能被跳过 —— 车机未安装时 `readInstalledVersion` 返回 `"0"`,若内嵌 `versionName` 也是 `0` / `0.0` / `0.0.0` 这种形式,比较结果会被判"已最新"而跳过首装。当前 `versionName` 为 `1.0.0`,不触发(同上 C-5)。

---

## 工作原理

1. **开热点** —— 手机开 WiFi 热点,车机连上。
2. **插 USB** —— 手机插车机 USB 口(或同 WiFi 网络)。
3. **自动安装** —— 手机通过 WiFi ADB 把车机应用装到车机(首次,一键)。
4. **自动连接** —— 四条 WiFi TCP 流：控制(9637)、视频(9638)、触摸(9639)、音频(9640, 协商开启)。
5. **用应用** —— 从车机启动器点应用,在手机上跑,投到车机,触摸回传。

手机在虚拟显示器上跑应用,编码成 H.264 推到车机;车机屏的触摸事件回传到手机,作为真实触摸事件注入。手机物理屏保持关闭(省电),可独立使用。

完整架构、连接流程、设计决策见 [docs/architecture.md](./docs/architecture.md)。docs 下文档已与本 fork 同步更新。

---

## 系统要求

**手机:**
- Android 10+(`minSdk = 29`)
- 已开启 USB 调试(开发者选项)
- All Files Access 权限(首次启动时引导授权)

**车机:**
- BYD DiLink 3.0+,已在 **比亚迪 秦PLUS DM-i 2023款 冠军版 55KM 领先型**(DiLink 4.0 低配,骁龙 439 / Android 9 / 1280x800)上实测
- Android 8.0+(`minSdk = 26`)
- 一个空闲 USB-A 口(或同 WiFi)

**手机热点必须开启** —— 车机连手机热点。无需配对码、无需 Google 账号。

**不需要联网。** DiLink-Auto 通过手机热点本地传输,车机和手机直接对话。联网只是给手机上跑的应用(导航、音乐)用,DiLink-Auto 本身不需要。

---

## 安装

下载 [Releases](https://github.com/ID-VerNe/dilink-auto-android/releases/latest) 里的 APK,或自行构建:

1. **构建:** `./gradlew :app-client:assembleDebug`
2. **装手机:** `app-client/build/outputs/apk/debug/app-client-debug.apk`
3. **开 USB 调试**(设置 → 开发者选项)
4. **打开 DiLink-Auto**,按引导授权 All Files Access
5. **开热点,插车机 USB** —— 车机应用首次运行时通过 WiFi ADB 自动安装

车机 APK 和 VD server JAR 都打包在手机 APK 内部 —— 不需要单独给车机装东西,安装车机应用也不需要联网。手机插车机 USB,点"Install on Car"即可。

---

## 项目结构

手机 APK(`app-client`)内嵌车机 APK(`app-server`)和 VD server JAR(`vd-server`)。装手机应用时一切就绪。接收端可替换:同一份协议既能跑在车机上(`app-server`),也能跑在 Windows 上(`app-desktop`)。

```
DiLink-Auto/
├── protocol-core/  纯 JVM 共享库(帧/消息/协议常量/VD 部署序列,三端共用,无 Android 依赖)
├── protocol/       共享库(framing、消息、发现、USB/TCP ADB)
├── app-client/     手机 APK —— 编排、VD 部署、车机自更新、应用允许列表
├── app-server/     车机 APK —— UI、连接状态机、视频解码器
├── app-desktop/    Windows 接收端 —— 镜像窗口、FFmpeg 解码、adb 部署、jpackage 打包
├── vd-server/      VirtualDisplay server(编译成 JAR,手机部署)
├── scripts/        脚本(黑屏修复日志验证等)
├── docs/           文档(多语言入口)
└── gradle/         构建系统
```

| 模块 | 角色 | minSdk |
|------|------|--------|
| `protocol-core` | 纯 JVM 共享协议库(Connection/NioWriter/NioReader/FrameCodec/Messages/AudioPayload/VdDeploy/BackoffPolicy/DecodeLivenessTracker/BlackScreenDetector) | — |
| `protocol` | 共享协议库(UsbAdbConnection、AdbProtocol、VideoConfig、ConnectionFactory、ConnectionKeepAlive、UsbAdbKeyStore) | 26 |
| `app-client` | 手机应用(ConnectionService、ShizukuVdDeployer、ClientNetworkMonitor、CarAppInstallHost、AllowlistRepository、FileLog) | 29 |
| `app-server` | 车机应用(CarSessionCoordinator、CarAdbTracker、CarAppListReceiver、AudioPlayer、VideoDecoder、CarShell、HomeScreen、PersistentNavBar) | 26 |
| `app-desktop` | Windows 接收端(DesktopApp、DesktopConnectionService、VideoDecodePipeline、SwingVideoView) | — |
| `vd-server` | Shell 权限进程(PipelineServer、AudioPipeline、AudioEncoderSelector、PortAcceptor、TouchInjector、GlPipeline、DisplayPowerController) | — |

---

## 构建

```bash
# 构建手机 APK(自动跑 buildVdServer + embedServerApk,把 vd-server.jar 和 app-server.apk 塞进 client assets)
./gradlew :app-client:assembleDebug

# APK 位置:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (手机端,内嵌车机 APK)

# 单元测试(6 个模块,JUnit4 + kotlinx-coroutines-test,无 Robolectric/Mockito/MockK)
./gradlew :protocol-core:test :protocol:test :vd-server:test :app-desktop:test
./gradlew :app-client:testDebugUnitTest :app-server:testDebugUnitTest
```

要求:JDK 17、Android SDK 34。release 构建需要 `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_PASSWORD` 环境变量(`RELEASE_KEYSTORE_FILE` / `RELEASE_KEY_ALIAS` 可选);缺任一必需变量都会在配置阶段直接失败,不再像过去那样静默产出装不上、也覆盖不了正式版的 unsigned APK。keystore 本身不入库,只以引用方式使用。

> **签名升级提醒**:从 debug 签名的旧包换成本仓库的正式签名包时,新旧证书不同,**必须先卸载旧版再安装**,否则会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。完整发布与签名规则见 [docs/release.md](./docs/release.md)。

---

## 文档

完整文档(setup、architecture、protocol、client、server、progress)入口在 [docs/README.md](./docs/README.md),该索引与全部文档均与本 fork 同步更新。语言分工:核心六篇(Setup / Architecture / Protocol / Client / Server / Progress)是英文,`IMPLEMENTATION_REPORT_TESTING` 测试实施报告与功能提案是中文;docs/README.md 本身是中文的开发者索引,与本节互补。

---

## 贡献

PR 欢迎。本 fork 在 `main` 分支上开发(不再使用 upstream 的 git-flow develop 分支模型)。提交前请确认上面的 `./gradlew :app-client:assembleDebug` 与单元测试命令均通过。

---

## License

MIT,见 [LICENSE](./LICENSE)。本项目 fork 自 [andersonlucasg3/dilink-auto-android](https://github.com/andersonlucasg3/dilink-auto-android),感谢原作者的工作。
