package com.dilinkauto.vdserver

import com.dilinkauto.protocol.VdDeploy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 虚拟显示内容黑屏时的自愈状态机（issue #15，Refs #87）。
 *
 * 阶梯自愈策略（每步复查，成功即返回，避免多余的 display 抖动）：
 *  1. `am start --display <id> --task <taskId> -n <cmp>`：把"已存在但没 resume"的
 *     顶层任务重新拉到本 display 前台；
 *  2. `am display move-stack <taskId> 0` → `... <id>` 往返：让 WM 重新把任务关联到该 display；
 *  3. [repairLayerTree]：WM 判据都正常但画面仍黑时才怀疑 SF 层树（Flyme DTA 孤立），
 *     复跑创建期那套旋转往返修复。
 *
 * 全程守护线程运行，绝不阻塞主消息循环；[healInFlight] 保证并发去重。
 */
internal class VdDisplayHealer(
    private val execOut: (String) -> String?,
    private val displayId: () -> Int,
    private val repairLayerTree: () -> Unit,
    private val healVerifyMs: Long = HEAL_VERIFY_MS
) {
    /** 并发去重：车机端每 15s 重试一次，叠加时不能嵌套执行。 */
    private val healInFlight = AtomicBoolean(false)

    /**
     * 触发显示自愈流程。
     */
    fun healDisplay() {
        if (!healInFlight.compareAndSet(false, true)) {
            PipeLog.log("healDisplay: already in flight, skip")
            return
        }
        daemonThread("DisplayHeal", start = true) {
            try {
                val id = displayId()
                if (id < 0) {
                    PipeLog.log("healDisplay: skipped, display id unknown")
                    return@daemonThread
                }
                val sec = ActivityDumpParser.displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                PipeLog.log("healDisplay: display=$id before='${ActivityDumpParser.summarizeDisplay(sec)}'")
                if (sec == null || !sec.contains("Task{")) {
                    PipeLog.log("healDisplay: display=$id has no task — leave the empty-stack semantics alone")
                    return@daemonThread
                }
                if (ActivityDumpParser.isTopTaskVisible(sec)) {
                    // WM 侧看起来是可见的，但车机持续报内容黑 —— 走最后的层树检查
                    PipeLog.log("healDisplay: display=$id top task visible in WM but content is black — verify layer tree")
                } else {
                    val m = ActivityDumpParser.TOP_ACTIVITY_RE.find(sec)
                    val cmp = m?.groupValues?.get(1)
                    val taskId = m?.groupValues?.get(2)?.toIntOrNull()
                    if (cmp.isNullOrBlank() || taskId == null || taskId <= 0) {
                        PipeLog.log("healDisplay: no top ActivityRecord in display=$id, falling through to layer-tree repair")
                    } else {
                        val out = execOut("am start --display $id --task $taskId -n ${VdDeploy.shellQuote(cmp)}")
                        PipeLog.log("healDisplay: step1 am start -> ${out?.trim()?.replace(Regex("\\s+"), " ")?.take(200) ?: "<no output>"}")
                        Thread.sleep(healVerifyMs)
                        val after1 = ActivityDumpParser.displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                        PipeLog.log("healDisplay: after step1='${ActivityDumpParser.summarizeDisplay(after1)}'")
                        if (after1 != null && ActivityDumpParser.isTopTaskVisible(after1)) {
                            PipeLog.log("healDisplay: recovered by step1 (re-front task $taskId)")
                            return@daemonThread
                        }
                        execOut("am display move-stack $taskId 0")
                        Thread.sleep(healVerifyMs)
                        execOut("am display move-stack $taskId $id")
                        Thread.sleep(healVerifyMs)
                        val after2 = ActivityDumpParser.displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                        PipeLog.log("healDisplay: after step2='${ActivityDumpParser.summarizeDisplay(after2)}'")
                        if (after2 != null && ActivityDumpParser.isTopTaskVisible(after2)) {
                            PipeLog.log("healDisplay: recovered by step2 (move-stack round trip)")
                            return@daemonThread
                        }
                    }
                }
                PipeLog.log("healDisplay: step3 layer-tree repair")
                repairLayerTree()
                val after3 = ActivityDumpParser.displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                PipeLog.log("healDisplay: after step3='${ActivityDumpParser.summarizeDisplay(after3)}'")
                if (after3 != null && ActivityDumpParser.isTopTaskVisible(after3)) {
                    PipeLog.log("healDisplay: recovered by step3 (layer-tree repair)")
                } else {
                    PipeLog.err("healDisplay: still black after the whole ladder — see the dumps above")
                }
            } catch (e: Exception) {
                PipeLog.err("healDisplay: ${e.message}")
            } finally {
                healInFlight.set(false)
            }
        }
    }

    companion object {
        /** 每步复查前的等待时间（毫秒）。 */
        const val HEAL_VERIFY_MS = 400L
    }
}
