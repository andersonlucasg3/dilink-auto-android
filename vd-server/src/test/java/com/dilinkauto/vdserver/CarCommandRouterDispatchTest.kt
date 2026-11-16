package com.dilinkauto.vdserver

import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarCommandRouter.dispatch —— 车机可控命令的路由与 shell 引用（S-02）。
 *
 * 全 lambda 注入，无需 Android。这是"对端可控字符串进 shell-UID 的 am/pm/input"的调用点，
 * 此前零覆盖：shell 引用、resolve 输出校验、SET_DISPLAY_POWER 载荷语义都在这里。
 */
class CarCommandRouterDispatchTest {

    private fun control(type: Byte, payload: ByteArray) = FrameCodec.Frame(Channel.CONTROL, type, payload)

    private class Rig(
        /** issue #21：部署端下传的白名单；空 = 不过滤（车机/桌面 ADB 路径）。 */
        allowedPackages: Set<String> = emptySet(),
        /** issue #48：JVM 单测里 Build.VERSION.SDK_INT 是 0，需显式注入 29 才能走到 moveTopApp。 */
        sdkInt: () -> Int = { 29 },
    ) {
        // 线程安全列表：quickSwitch / fillLaunchedApp 在后台线程里追加，
        // 测试线程同时读取（轮询断言），普通 ArrayList 会偶发 CME。
        val execs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val execOuts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val powers = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val syncRequests = java.util.concurrent.atomic.AtomicInteger(0)
        val layerRepairs = java.util.concurrent.atomic.AtomicInteger(0)
        var displayId = 7
        var launchResolve: String? = null
        var appInfoResolve: String? = null
        var recentsDump: String? = null
        var activitiesDump: String? = null
        var windowDisplaysDump: String? = null
        /** 需要区分「移动前 / 移动后」两次 dump 时按序取用（issue #48）。 */
        var activitiesDumpSequence: java.util.concurrent.ConcurrentLinkedQueue<String>? = null
        /** issue #199：给 dumpsys 加人为延迟，用来驱动「单步预算到点」分支。 */
        var activitiesDumpDelayMs: Long = 0

        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity recents") -> recentsDump
                    cmd.startsWith("dumpsys activity activities") -> {
                        if (activitiesDumpDelayMs > 0) Thread.sleep(activitiesDumpDelayMs)
                        activitiesDumpSequence?.poll() ?: activitiesDump
                    }
                    cmd.startsWith("dumpsys window displays") -> windowDisplaysDump
                    cmd.startsWith("wm size") -> "Physical size: 2340x1316"
                    cmd.contains("resolve-activity") && cmd.contains("android.intent.action.MAIN") -> launchResolve
                    cmd.contains("resolve-activity") && cmd.contains("APPLICATION_DETAILS_SETTINGS") -> appInfoResolve
                    cmd.startsWith("am start") -> "Starting: Intent { ... }"
                    else -> null // 其余 dumpsys 等
                }
            },
            displayId = { displayId },
            onStackEmpty = { },
            setDisplayPower = { powers += it },
            requestSyncFrame = { syncRequests.incrementAndGet() },
            repairLayerTree = { layerRepairs.incrementAndGet() },
            allowedPackages = allowedPackages,
            sdkInt = sdkInt,
        )
    }

    @Test
    fun `go home emits keyevent three on the virtual display`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.GO_HOME, ByteArray(0)))
        assertTrue(r.execs.contains("input -d 7 keyevent 3"))
    }

    @Test
    fun `go back emits keyevent four`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.GO_BACK, ByteArray(0)))
        assertTrue(r.execs.contains("input -d 7 keyevent 4"))
    }

    @Test
    fun `go recent quick switches to the most recent other task via am start task`() {
        // 「最近」的语义（2026-10-10 实机拍板）：快速切换到上一个任务。
        // 不再注入 APP_SWITCH（keyevent 187）—— 它硬编码默认显示器，实测只会
        // toggle 物理屏的 recents，对 VD 无效（见 CarCommandRouter.quickSwitch 注释）。
        // 实机样本：twitter(2048) 是当前 VD 顶层、控制端(2040) 必须跳过、
        // 小红书(2047) 是切换目标。
        val r = Rig()
        r.recentsDump = """
  * RecentTaskInfo #0: 
    id=2048 userId=0 hasTask=true lastActiveTime=849748141
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.twitter.android/com.x.android.main.MainActivity }
  * RecentTaskInfo #1: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #2: 
    id=2047 userId=0 hasTask=true lastActiveTime=848968398
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.xingin.xhs/.index.v2.IndexActivityV2 }
"""
        r.activitiesDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
"""
        r.router.dispatch(control(ControlMsg.GO_RECENT, ByteArray(0)))

        // quickSwitch 异步执行（两个 dumpsys 各几百毫秒，不能阻塞 TouchReader）——
        // 有界等待目标命令落地。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.none { it.startsWith("am start --display 7 --task") }
        ) Thread.sleep(10)

        assertTrue(
            r.execOuts.contains(
                "am start --display 7 --task 2047 -n 'com.xingin.xhs/.index.v2.IndexActivityV2'"
            )
        )
        // 旧行为的回归钉子：不得再往（物理屏硬编码的）APP_SWITCH 注入任何 keyevent。
        assertTrue("GO_RECENT 不得再注入 keyevent 187", r.execs.isEmpty())
    }

    @Test
    fun `go recent issues nothing when every candidate is filtered out`() {
        // 只剩自家控制端与 launcher → 无可切换目标 → 什么都不发（只记日志）。
        val r = Rig()
        r.recentsDump = """
  * RecentTaskInfo #0: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #1: 
    id=3000 userId=0 hasTask=true lastActiveTime=849625000
    baseIntent=Intent { cmp=com.google.android.apps.nexuslauncher/.NexusLauncherActivity }
"""
        r.activitiesDump = "Display #7 (activities from top to bottom):\n"
        r.router.dispatch(control(ControlMsg.GO_RECENT, ByteArray(0)))

        // 等两个 dumpsys 都跑过（= 选择逻辑已走完），再给潜在命令一个落地窗口。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.count { it.startsWith("dumpsys activity") } < 2
        ) Thread.sleep(10)
        Thread.sleep(100)

        assertTrue("无可切换目标时不得发 am start", r.execOuts.none { it.startsWith("am start") })
    }

    @Test
    fun `display id is read at dispatch time not cached`() {
        val r = Rig(); r.displayId = -1
        r.router.dispatch(control(ControlMsg.GO_HOME, ByteArray(0)))
        assertTrue(r.execs.contains("input -d -1 keyevent 3"))
    }

    @Test
    fun `app uninstall shell quotes the package`() {
        val r = Rig()
        r.router.dispatch(control(ControlMsg.APP_UNINSTALL, "com.foo.bar".toByteArray()))
        assertTrue(r.execs.contains("pm uninstall 'com.foo.bar'"))
    }

    @Test
    fun `set display power payload semantics`() {
        val one = Rig(); one.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(1)))
        assertEquals(listOf(true), one.powers)

        val zero = Rig(); zero.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(0)))
        assertEquals(listOf(false), zero.powers)

        val empty = Rig(); empty.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, ByteArray(0)))
        assertEquals(listOf(false), empty.powers)

        val two = Rig(); two.router.dispatch(control(ControlMsg.SET_DISPLAY_POWER, byteArrayOf(2)))
        assertEquals(listOf(true), two.powers)
    }

    @Test
    fun `request sync frame asks the pipeline for an IDR`() {
        // issue #9：车机端参考链断裂 → 手机端在 Pipeline 线程索要 IDR。
        // 空 payload 也必须生效（车机端不需要携带参数）。
        val r = Rig()
        r.router.dispatch(control(ControlMsg.REQUEST_SYNC_FRAME, ByteArray(0)))
        assertEquals(1, r.syncRequests.get())
        // 不得误触发屏电源开关（两条命令相邻，编号错位会立刻被这条钉住）
        assertTrue(r.powers.isEmpty())
    }

    @Test
    fun `launch app with resolvable component uses explicit intent and shell quotes both`() {
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        // resolve 调用把 pkg 引起来
        assertTrue(r.execOuts.any { it.contains("android.intent.category.LAUNCHER 'com.foo.bar'") })
        // am start -n 用组件名并整体引用
        assertTrue(r.execOuts.any { it == "am start --display 7 -n 'com.foo.bar/.MainActivity'" })
    }

    @Test
    fun `launch app falls back to implicit intent when resolve emits non component text`() {
        val r = Rig()
        r.launchResolve = "No activity found"
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        assertTrue(r.execOuts.any {
            it.startsWith("am start --display 7 -a android.intent.action.MAIN") &&
                it.contains("android.intent.category.LAUNCHER 'com.foo.bar'") && !it.contains(" -n ")
        })
    }

    @Test
    fun `launch app resizes the launched task to fill the virtual display`() {
        // 2026-10-10 实机：VD 上 app 默认落成 freeform 小窗（853,51-1487,1220），
        // 启动参数 --windowingMode 1 被框架忽略；铺满 = 启动后
        // `am task resize <taskId> 0 0 <W> <H>`（实测铺满 0,0-2340,1316）。
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.activitiesDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #77 type=standard A=1000:com.foo.bar}
    * ActivityRecord{751a1f5 u0 com.foo.bar/.MainActivity t77}
"""
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))

        // 铺满是异步的（后台线程）——有界等待目标命令落地。
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            r.execOuts.none { it.startsWith("am task resize") }
        ) Thread.sleep(10)

        assertTrue(r.execOuts.contains("am task resize 77 0 0 2340 1316"))
    }

    // ─── issue #19：fill 之后的层树复查 ───

    /** fill 能找到顶层任务时的 dump（实车形态）。 */
    private val filledDisplayDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #77 type=standard A=1000:com.foo.bar}
    * ActivityRecord{751a1f5 u0 com.foo.bar/.MainActivity t77}
"""

    @Test
    fun `launch app rechecks the layer tree after filling the task`() {
        // issue #19：DTA 脱同步发生在 `am task resize … fill` 之后约 1.6s
        // （创建期那次检查必然读到健康），所以 fill 完成后必须再查一次层树。
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.activitiesDump = filledDisplayDump
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))

        waitFor(6_000) { r.layerRepairs.get() > 0 }
        assertEquals("fill 之后必须复查一次层树（且只一次）", 1, r.layerRepairs.get())
    }

    @Test
    fun `fill that never finds the launched task does not recheck the layer tree`() {
        // 没铺满就没有那次导致脱同步的 resize 事务，复查纯属空跑 —— 不应发生。
        val r = Rig()
        r.launchResolve = "com.foo.bar/.MainActivity"
        r.activitiesDump = "Display #7 (activities from top to bottom):\n"
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))

        // 等 fill 的轮询预算（8 × 300ms）走完，再多给一点落地窗口。
        waitFor(6_000) { r.execOuts.count { it.startsWith("dumpsys activity activities") } >= 8 }
        Thread.sleep(200)
        assertEquals("未发生 fill 时不得复查层树", 0, r.layerRepairs.get())
    }

    @Test
    fun `launch app rejects resolve output that is not a valid component`() {
        val r = Rig()
        r.launchResolve = "com.foo.bar/-weird" // 有 '/' 无空格 → 被当成组件候选，但 requireComponentName 拒绝
        r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo.bar".toByteArray()))
        assertTrue("非法组件名不得触发 am start", r.execOuts.none { it.startsWith("am start") })
    }

    @Test
    fun `app info uses resolved activity and quotes the data uri`() {
        val r = Rig()
        r.appInfoResolve = "com.android.settings/.Settings"
        r.router.dispatch(control(ControlMsg.APP_INFO, "com.foo.bar".toByteArray()))
        // -n 不引用（$s 来自系统 resolve），-d 引用
        assertTrue(r.execs.contains("am start --display 7 -n com.android.settings/.Settings -d 'package:com.foo.bar'"))
    }

    @Test
    fun `app info falls back to detail settings action when resolve is empty`() {
        val r = Rig()
        r.appInfoResolve = ""
        r.router.dispatch(control(ControlMsg.APP_INFO, "com.foo.bar".toByteArray()))
        assertTrue(r.execs.contains("am start --display 7 -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:com.foo.bar'"))
    }

    @Test
    fun `a malicious launch app payload throws and nothing is issued`() {
        // 现状：LAUNCH_APP 的 decode 未在 dispatch 内保护，恶意包名直接抛出去（APP_INFO 有 try/catch）。
        // 本测试只锁定"抛异常且没有 am start"的当前行为，作为 hazard 文档。
        val r = Rig()
        var threw = false
        try {
            r.router.dispatch(control(ControlMsg.LAUNCH_APP, "com.foo;x".toByteArray()))
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(r.execOuts.none { it.startsWith("am start") })
        assertTrue(r.execs.isEmpty())
    }

    // ─── issue #15：VD 内容持续纯黑时的显示自愈阶梯 ───

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(10)
    }

    /** 实车原话（issue #15 证据链）：可见时带 `visibleRequested=true` + `topResumedActivity=`。 */
    private val visibleDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #77 type=standard A=1000:com.foo.bar U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    topResumedActivity=ActivityRecord{751a1f5 u0 com.foo.bar/.MainActivity t77}
    * ActivityRecord{751a1f5 u0 com.foo.bar/.MainActivity t77}
"""

    /** 黑屏时的同一行：`visible=true` 但 `visibleRequested=false`，且没有 topResumedActivity。 */
    private val invisibleDump = """
Display #7 (activities from top to bottom):
  * Task{e27 #34033 type=standard A=10265:com.autonavi.minimap U=0 visible=true visibleRequested=false mode=fullscreen translucent=true sz=1}
    * ActivityRecord{b68 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34033}
"""

    @Test
    fun `display heal skips task surgery but still verifies the layer tree when WM says visible`() {
        // WM 侧可见 ≠ SF 一定合成得出画面（issue #15 现场：会话结束 dump 已恢复
        // visibleRequested=true，车机仍是黑）→ 不能直接返回，要走一次层树检查；
        // 但不得再动任务（am start / move-stack 会白抖一次画面）。
        val r = Rig(); r.activitiesDump = visibleDump
        r.router.dispatch(control(ControlMsg.REQUEST_DISPLAY_HEAL, ByteArray(0)))

        waitFor { r.layerRepairs.get() > 0 }
        assertTrue("已可见时不得再动任务", r.execOuts.none {
            it.startsWith("am start") || it.startsWith("am display move-stack")
        })
        assertEquals(1, r.layerRepairs.get())
        // 回归钉子：该 ROM 上 keyevent 3/4 对 VD 无效，不得被当作恢复手段。
        assertTrue(r.execs.isEmpty())
    }

    @Test
    fun `display heal re-fronts an invisible top task`() {
        val r = Rig(); r.activitiesDump = invisibleDump
        r.router.dispatch(control(ControlMsg.REQUEST_DISPLAY_HEAL, ByteArray(0)))

        waitFor { r.execOuts.any { it.startsWith("am start --display 7 --task") } }
        assertTrue(
            r.execOuts.contains(
                "am start --display 7 --task 34033 -n 'com.autonavi.minimap/com.autonavi.map.activity.SplashActivity'"
            )
        )
        assertTrue("不得注入 keyevent 3", r.execs.isEmpty())
    }

    @Test
    fun `display heal escalates to move stack round trip and layer tree repair`() {
        // 每步复查都读到同一份"仍不可见"的 dump → 阶梯必须走到底：
        // 1) am start --task  2) move-stack 0 → 7 往返  3) 层树修复。
        val r = Rig(); r.activitiesDump = invisibleDump
        r.router.dispatch(control(ControlMsg.REQUEST_DISPLAY_HEAL, ByteArray(0)))

        waitFor(8_000) { r.layerRepairs.get() > 0 }
        assertTrue(r.execOuts.contains("am display move-stack 34033 0"))
        assertTrue(r.execOuts.contains("am display move-stack 34033 7"))
        assertEquals(1, r.layerRepairs.get())
    }

    // ─── issue #17：会话建立后 VD 里必须有窗口 ───

    private val emptyDisplayDump = "Display #7 (activities from top to bottom):\n"

    /** 实车样本（`dumpsys activity recents`）：自家控制端必须被排除。 */
    private val recentsSample = """
  * RecentTaskInfo #0: 
    id=2039 userId=0 hasTask=true lastActiveTime=849748141
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.autonavi.minimap/com.autonavi.map.activity.SplashActivity }
  * RecentTaskInfo #1: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.dilinkauto.client/.MainActivity }
"""

    @Test
    fun `ensure content leaves a display that already shows a window alone`() {
        // 已有可见 task（例如 moveTopApp 刚把前台应用搬进来）→ 不得再动任务，
        // 也不该再去读 recents（白抖一次画面）。
        val r = Rig(); r.activitiesDump = visibleDump
        r.router.ensureDisplayHasContent(7)

        assertTrue("已有窗口时不得发 am 命令", r.execOuts.none { it.startsWith("am start") })
        assertTrue("已有窗口时不得读 recents", r.execOuts.none { it.startsWith("dumpsys activity recents") })
    }

    @Test
    fun `ensure content restores the most recent app when the display is empty`() {
        // 实车缺陷（issue #17）：新建 VD 后 display 段里没有 Task{ }，编码器只能出
        // 纯黑帧。这里锁住补救动作 —— 把最近一次被镜像的应用（排除自家控制端）
        // 用 am start --task 迁进 VD。
        val r = Rig()
        r.activitiesDump = emptyDisplayDump
        r.recentsDump = recentsSample
        r.router.ensureDisplayHasContent(7)

        assertTrue(
            r.execOuts.contains(
                "am start --display 7 --task 2039 -n 'com.autonavi.minimap/com.autonavi.map.activity.SplashActivity'"
            )
        )
        assertTrue("恢复后必须复查 display 段", r.execOuts.count { it.startsWith("dumpsys activity activities") } >= 2)
    }

    @Test
    fun `ensure content issues nothing when recents only has self and launcher`() {
        // 没有可恢复的应用（只剩自家控制端/launcher）→ 只记日志，不发 am 命令；
        // 该场景首次帧仍是纯黑，留给后续 issue。
        val r = Rig()
        r.activitiesDump = emptyDisplayDump
        r.recentsDump = """
  * RecentTaskInfo #0: 
    id=2040 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { cmp=com.dilinkauto.client/.MainActivity }
  * RecentTaskInfo #1: 
    id=3000 userId=0 hasTask=true lastActiveTime=849625000
    baseIntent=Intent { cmp=com.meizu.flyme.launcher/com.android.launcher3.uioverrides.QuickstepLauncher }
"""
        r.router.ensureDisplayHasContent(7)

        assertTrue("无可恢复目标时不得发 am start", r.execOuts.none { it.startsWith("am start") })
    }

    // ─── issue #21：建屏恢复不得绕过车机白名单 ───

    /** 实车日志里的证据样本：Shizuku 管理器、淘宝都不在白名单（7/120）内。 */
    private val recentsOutsideAllowlist = """
  * RecentTaskInfo #0: 
    id=33952 userId=0 hasTask=true lastActiveTime=849748141
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity }
  * RecentTaskInfo #1: 
    id=34024 userId=0 hasTask=true lastActiveTime=849625054
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.taobao.taobao/com.taobao.tao.welcome.Welcome }
"""

    @Test
    fun `ensure content never pulls an app outside the car allowlist into the vd`() {
        // issue #21：5/5 次建屏都把 Shizuku 管理器 / 淘宝拉到了车机屏。白名单下传后，
        // recents 里只有白名单外应用时必须什么都不做（首次帧仍是纯黑，等用户从车机
        // App 列表启动），隐私优先级高于"首帧非黑"。
        val r = Rig(allowedPackages = setOf("com.autonavi.minimap", "com.baidu.BaiduMap"))
        r.activitiesDump = emptyDisplayDump
        r.recentsDump = recentsOutsideAllowlist
        r.router.ensureDisplayHasContent(7)

        assertTrue(
            "白名单外的应用不得被拉进 VD: ${r.execOuts.filter { it.startsWith("am start") }}",
            r.execOuts.none { it.startsWith("am start") }
        )
    }

    @Test
    fun `ensure content picks the allowlisted app even when it is not the most recent`() {
        // 白名单内的应用即使不是 recents 第一条，也要被选中（按 allowlist 挑选，
        // 而不是"最近用过的那个"）。
        val r = Rig(allowedPackages = setOf("com.autonavi.minimap"))
        r.activitiesDump = emptyDisplayDump
        r.recentsDump = recentsOutsideAllowlist + """
  * RecentTaskInfo #2: 
    id=34033 userId=0 hasTask=true lastActiveTime=849600000
    baseIntent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 cmp=com.autonavi.minimap/com.autonavi.map.activity.SplashActivity }
"""
        r.router.ensureDisplayHasContent(7)

        assertTrue(
            "应按白名单选中高德（Task 34033）: ${r.execOuts.filter { it.startsWith("am start") }}",
            r.execOuts.contains(
                "am start --display 7 --task 34033 -n 'com.autonavi.minimap/com.autonavi.map.activity.SplashActivity'"
            )
        )
    }

    // ─── issue #48：断开清理只加取证日志，不改行为 ───

    /** 捕获 stdout（[PipeLog.log] 直接写 System.out），用于断言新增的取证日志。 */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `teardown move keeps the move stack command sequence and logs the moved task config`() {
        // issue #48：断开时 moveTopApp 仍然只发同一条 `am display move-stack`；
        // 新增的只是移动后回读目标 task 配置的 `[teardown-move]` 日志。
        val r = Rig()
        r.activitiesDumpSequence = java.util.concurrent.ConcurrentLinkedQueue(
            listOf(
                """
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
""",
                """
Display #0 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    bounds=[0,0][1368,3192]
    mWindowingMode=fullscreen
    densityDpi=560
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
"""
            )
        )

        val log = captureStdout { r.router.moveTopApp(7, 0) }

        assertEquals("移动命令序列必须与修复前一致", listOf("am display move-stack 2048 0"), r.execs.toList())
        assertTrue("必须仍先读源 display 段", r.execOuts.first().startsWith("dumpsys activity activities"))
        assertTrue(log.contains("[teardown-move] task=2048 moved-to=0 displayId=0"))
        assertTrue(log.contains("bounds=[0,0][1368,3192]"))
        assertTrue(log.contains("compat=fullscreen"))
        assertTrue(log.contains("densityDpi=560"))
    }

    @Test
    fun `teardown moves every residual vd task and resizes each to the physical display`() {
        // issue #48：S25 后 xhs/bilibili 等历史任务仍在 VD 段；cleanup 不能只搬顶层。
        val r = Rig()
        r.activitiesDump = """
Display #0 (activities from top to bottom):
  * Task{a11 #0 type=standard A=10216:com.dilinkauto.client}
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
  * Task{b22 #2047 type=standard A=10235:com.xingin.xhs U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    * ActivityRecord{751a1f6 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t2047}
"""
        val log = captureStdout { r.router.moveAllTasksToDisplay(7, 0) }

        assertEquals(
            "每个 VD task 都必须搬回物理屏",
            listOf("am display move-stack 2048 0", "am display move-stack 2047 0"),
            r.execs.filter { it.startsWith("am display move-stack") },
        )
        assertEquals(
            "每个搬回的 task 都必须按物理屏尺寸 resize",
            listOf("am task resize 2048 0 0 2340 1316", "am task resize 2047 0 0 2340 1316"),
            r.execOuts.filter { it.startsWith("am task resize") },
        )
        assertTrue(log.contains("[teardown-move-all] from=7 to=0 tasks=[2048, 2047]"))
    }

    @Test
    fun `move all tasks bails out at the budget and reports remaining task ids`() {
        // issue #199：单步预算到点必须放弃剩余动作并把「还没搬回的 task」交回去，
        // 否则现场只知道 apps-moved 超时、不知道欠了谁。
        val r = Rig()
        r.activitiesDumpDelayMs = 10
        r.activitiesDump = """
Display #0 (activities from top to bottom):
  * Task{a11 #0 type=standard A=10216:com.dilinkauto.client}
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
  * Task{b22 #2047 type=standard A=10235:com.xingin.xhs U=0 visible=false}
    * ActivityRecord{751a1f6 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t2047}
"""
        val pending = java.util.concurrent.CopyOnWriteArrayList<List<Int>>()

        // 预算 5ms，而首份 dumpsys 被拖了 10ms ⇒ 必然在 settle 之前就放弃。
        val ok = r.router.moveAllTasksToDisplay(7, 0, budgetMs = 5) { pending += it }

        assertTrue("预算到点必须返回 false", !ok)
        assertEquals("必须交回全部还没搬回的 task", listOf(2048, 2047), pending.last())
        assertTrue(
            "批量路径只读一次源 dumpsys（原来每 task 一次回读）",
            r.execOuts.count { it.startsWith("dumpsys activity activities") } == 1
        )
    }

    @Test
    fun `move all tasks is a no-op on pre api 29`() {
        // 与 moveTopApp 同一门槛：API 26-28 没有 am display move-stack，不能静默发命令。
        val r = Rig(sdkInt = { 28 })
        r.activitiesDump = """
Display #7 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
"""
        r.router.moveAllTasksToDisplay(7, 0)

        assertTrue(r.execs.none { it.startsWith("am display move-stack") })
        assertTrue(r.execs.none { it.startsWith("am task resize") })
    }

    @Test
    fun `teardown display snapshot logs physical init cur and residual vd tasks`() {
        // issue #48：cleanup 的取证行要能直接对比物理屏 init/cur，并列出没被搬回
        // 物理屏、仍挂在 VD 上的任务。
        val r = Rig()
        r.windowDisplaysDump = """
Display: mDisplayId=0
  init=1368x3192 560dpi cur=1368x3192 app=1368x3192 560dpi
Display: mDisplayId=7
  init=2340x1316 240dpi cur=2340x1316 app=2340x1316 240dpi
"""
        r.activitiesDump = """
Display #0 (activities from top to bottom):
  * Task{a11 #2048 type=standard A=10216:com.twitter.android U=0 visible=true}
    * ActivityRecord{751a1f5 u0 com.twitter.android/com.x.android.main.MainActivity t2048}
Display #7 (activities from top to bottom):
  * Task{e27 #34033 type=standard A=10265:com.autonavi.minimap U=0 visible=true}
    * ActivityRecord{b68 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t34033}
"""

        val log = captureStdout { r.router.logTeardownDisplaySnapshot(7) }

        assertTrue(log.contains("[teardown-display] display#0 init=1368x3192 560dpi cur=1368x3192"))
        assertTrue(log.contains("vd=7 residualTasks=[34033]"))
    }
}
