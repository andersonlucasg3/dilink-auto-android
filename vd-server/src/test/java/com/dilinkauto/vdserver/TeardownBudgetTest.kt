package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * issue #199：锁定 teardown 步骤编排的超时策略。
 *
 * 现场（2026-11-16）的问题是「一步慢 → 整段被 3s watchdog halt」：18 步从未走完，
 * `18/18 complete` 一次都没出现。这里用假步骤（纯 JVM，不碰 Android/EGL）覆盖五条契约：
 *  1. 单步超时只放弃那一步，后续步骤照跑；
 *  2. 总预算到点会打出"剩余步 + 剩余 task id"；
 *  3. 正常路径能走到最后一步（[TeardownStep.COMPLETE]）；
 *  4. 步骤抛异常不打断后续；
 *  5. （PR #208 评审）前 5 步各吃满预算后 `panel-restored` 仍被执行，不被
 *     `SKIPPED_BUDGET`；依赖被放弃前序步的步骤记 `SKIPPED_DEPENDENCY` 并说明原因。
 */
class TeardownBudgetTest {

    private val logs = CopyOnWriteArrayList<String>()
    private val errs = CopyOnWriteArrayList<String>()

    private fun runner(
        steps: List<TeardownStepPlan>,
        totalBudgetMs: Long,
        pendingDetail: () -> String = { "" }
    ) = TeardownRunner(
        steps = steps,
        totalBudgetMs = totalBudgetMs,
        log = { logs += it },
        err = { errs += it },
        pendingDetail = pendingDetail
    )

    /** 用真实枚举的前 N 步拼一条"假"编排，动作由 [action] 提供。 */
    private fun planOf(vararg budgetAndAction: Pair<Long, () -> Unit>): List<TeardownStepPlan> {
        val steps = TeardownStep.entries
        require(budgetAndAction.size <= steps.size)
        return budgetAndAction.mapIndexed { index, (budget, action) ->
            TeardownStepPlan(steps[index], budget, action = action)
        }
    }

    @Test
    fun slowStepTimesOutAndTheFollowingStepsStillRun() {
        val ran = mutableListOf<String>()
        val report = runner(
            planOf(
                100L to { ran += "begin" },
                // 单步预算 50ms，动作睡 300ms ⇒ 必须被判定超时并放弃。
                50L to {
                    Thread.sleep(300)
                    ran += "slow"
                },
                100L to { ran += "after" }
            ),
            totalBudgetMs = 2_000
        ).run()

        assertEquals(TeardownOutcome.TIMED_OUT, report.outcomeOf(TeardownStep.WINDOW_MONITOR_STOPPED))
        assertEquals(TeardownOutcome.COMPLETED, report.outcomeOf(TeardownStep.BEGIN))
        assertEquals(
            "超时之后的那一步必须照跑（否则面板/IME 恢复会被一起吃掉）",
            TeardownOutcome.COMPLETED,
            report.outcomeOf(TeardownStep.LETTERBOX_RESET)
        )
        assertTrue("超时行要带步号、标签与剩余步骤", errs.any {
            it.contains("02/") && it.contains("TIMEOUT") && it.contains("letterbox-reset")
        })
        assertTrue(report.incomplete)
    }

    @Test
    fun totalBudgetExhaustionReportsRemainingStepsAndPendingTasks() {
        val ran = mutableListOf<String>()
        val report = runner(
            planOf(
                300L to { Thread.sleep(300) },
                300L to { ran += "should-not-run" }
            ),
            // 总预算只有 100ms：第一步就把预算吃光。
            totalBudgetMs = 100,
            pendingDetail = { "remainingTasks=[717, 729]" }
        ).run()

        assertEquals(TeardownOutcome.SKIPPED_BUDGET, report.outcomeOf(TeardownStep.WINDOW_MONITOR_STOPPED))
        assertTrue("预算用尽后不得再执行后续步骤", ran.isEmpty())
        assertTrue(
            "必须打出剩余步与剩余 task id：$errs",
            errs.any {
                it.contains("总预算") &&
                    it.contains("window-monitor-stopped") &&
                    it.contains("remainingTasks=[717, 729]")
            }
        )
        assertTrue(report.incomplete)
    }

    @Test
    fun normalPathReachesTheLastStep() {
        val ran = mutableListOf<String>()
        val steps = TeardownStep.entries.map { step ->
            TeardownStepPlan(step, 1_000L) { ran += step.label }
        }
        val report = runner(steps, totalBudgetMs = 8_000).run()

        assertFalse("正常路径不应被标记为不完整：$errs", report.incomplete)
        assertEquals("18 步必须全部走到", TeardownStep.entries.size, ran.size)
        assertEquals(TeardownStep.COMPLETE.label, ran.last())
        assertTrue(
            "末步日志要与历史格式一致（18/18 complete）",
            logs.any { it.contains("/18 complete") }
        )
    }

    @Test
    fun failingStepDoesNotStopTheRest() {
        val ran = mutableListOf<String>()
        val report = runner(
            planOf(
                100L to { throw IllegalStateException("boom") },
                100L to { ran += "second" }
            ),
            totalBudgetMs = 1_000
        ).run()

        assertEquals(TeardownOutcome.FAILED, report.outcomeOf(TeardownStep.BEGIN))
        assertTrue("异常步骤之后必须继续", ran.contains("second"))
        assertTrue(errs.any { it.contains("FAILED") && it.contains("boom") })
    }

    @Test
    fun firstFiveBudgetsMustFitInsideTheTotalBudget() {
        // PR #208 评审 [阻塞]：前 5 步预算合计 4400ms 一旦 >= 总预算，慢机上这 5 步吃满后
        // panel-restored / ime-restored 会拿到 remainingBudget<=0 直接 SKIPPED_BUDGET，
        // 等于把 #199（面板/IME 没恢复）平移了一遍。
        assertTrue(
            "前 5 步预算之和 ${TEARDOWN_PRE_PANEL_BUDGETS_MS}ms 必须小于总预算 " +
                "${TEARDOWN_TOTAL_BUDGET_MS}ms",
            TEARDOWN_PRE_PANEL_BUDGETS_MS < TEARDOWN_TOTAL_BUDGET_MS
        )
    }

    @Test
    fun panelRestoreStillRunsAfterTheFirstFiveStepsEatTheirBudgets() {
        // 用注入的假时钟让前 5 步**精确**吃满各自预算（不靠 sleep，避免 Windows 上
        // Thread.sleep 抖动导致的假红）：4400ms 之后 panel-restored 必须仍然拿到正预算执行。
        val clockNanos = java.util.concurrent.atomic.AtomicLong(0L)
        val panelRan = java.util.concurrent.atomic.AtomicBoolean(false)
        fun eat(budgetMs: Long): () -> Unit = { clockNanos.addAndGet(budgetMs * 1_000_000) }

        val steps = listOf(
            TeardownStepPlan(
                TeardownStep.BEGIN, TEARDOWN_BUDGET_BEGIN_MS,
                action = eat(TEARDOWN_BUDGET_BEGIN_MS)
            ),
            TeardownStepPlan(
                TeardownStep.WINDOW_MONITOR_STOPPED, TEARDOWN_BUDGET_WINDOW_MONITOR_MS,
                action = eat(TEARDOWN_BUDGET_WINDOW_MONITOR_MS)
            ),
            TeardownStepPlan(
                TeardownStep.LETTERBOX_RESET, TEARDOWN_BUDGET_LETTERBOX_MS,
                action = eat(TEARDOWN_BUDGET_LETTERBOX_MS)
            ),
            TeardownStepPlan(
                TeardownStep.ROTATION_FREE, TEARDOWN_BUDGET_ROTATION_MS,
                action = eat(TEARDOWN_BUDGET_ROTATION_MS)
            ),
            TeardownStepPlan(
                TeardownStep.APPS_MOVED, TEARDOWN_BUDGET_APPS_MOVED_MS,
                action = eat(TEARDOWN_BUDGET_APPS_MOVED_MS)
            ),
            TeardownStepPlan(TeardownStep.PANEL_RESTORED, 1_500L) { panelRan.set(true) }
        )
        val report = TeardownRunner(
            steps = steps,
            totalBudgetMs = TEARDOWN_TOTAL_BUDGET_MS,
            log = { logs += it },
            err = { errs += it },
            nanoTime = { clockNanos.get() }
        ).run()

        assertTrue("前 5 步吃满预算后，面板恢复必须仍然执行（$errs）", panelRan.get())
        assertEquals(
            TeardownOutcome.COMPLETED,
            report.outcomeOf(TeardownStep.PANEL_RESTORED)
        )
    }

    @Test
    fun stepDependentOnATimedOutStepIsSkippedWithReason() {
        // PR #208 评审（建议）：apps-moved 被放弃后，DISPLAY_SNAPSHOT 读到的只是竞态数据，
        // 必须跳过并记录原因；而释放类步骤（VD）不挂依赖，泄漏比竞争更糟，必须照跑。
        val ran = CopyOnWriteArrayList<String>()
        val steps = listOf(
            TeardownStepPlan(TeardownStep.APPS_MOVED, 50L) { Thread.sleep(300) },
            TeardownStepPlan(
                TeardownStep.DISPLAY_SNAPSHOT,
                200L,
                dependsOn = TeardownStep.APPS_MOVED
            ) { ran += "snapshot" },
            TeardownStepPlan(TeardownStep.VIRTUAL_DISPLAY_RELEASED, 200L) { ran += "vd" }
        )

        val report = runner(steps, totalBudgetMs = 5_000).run()

        assertEquals(TeardownOutcome.TIMED_OUT, report.outcomeOf(TeardownStep.APPS_MOVED))
        assertEquals(
            TeardownOutcome.SKIPPED_DEPENDENCY,
            report.outcomeOf(TeardownStep.DISPLAY_SNAPSHOT)
        )
        assertFalse("依赖未完成时不得执行快照：$ran", ran.contains("snapshot"))
        assertTrue(
            "跳过原因要指向前序步：$errs",
            errs.any { it.contains("SKIPPED") && it.contains("apps-moved") && it.contains("TIMED_OUT") }
        )
        assertTrue("释放类步骤不挂依赖，必须照跑：$ran", ran.contains("vd"))
    }

    @Test
    fun watchdogGraceMustExceedTheTeardownBudget() {
        assertTrue(
            "watchdog 宽限 ${ProcessWatchdog.WATCHDOG_GRACE_MS}ms 必须大于总预算 ${TEARDOWN_TOTAL_BUDGET_MS}ms",
            ProcessWatchdog.WATCHDOG_GRACE_MS > TEARDOWN_TOTAL_BUDGET_MS
        )
        assertTrue(ProcessWatchdog.WATCHDOG_GRACE_MS <= 6_000L)
    }
}
