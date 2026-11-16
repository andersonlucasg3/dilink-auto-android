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
 * `18/18 complete` 一次都没出现。这里用假步骤（纯 JVM，不碰 Android/EGL）覆盖四条契约：
 *  1. 单步超时只放弃那一步，后续步骤照跑；
 *  2. 总预算到点会打出"剩余步 + 剩余 task id"；
 *  3. 正常路径能走到最后一步（[TeardownStep.COMPLETE]）；
 *  4. 步骤抛异常不打断后续。
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
            TeardownStepPlan(steps[index], budget, action)
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
    fun watchdogGraceMustExceedTheTeardownBudget() {
        // 回签现场最常见的一种"修复"是把 3s 放大到 10s；这里的约束是：watchdog 必须晚于
        // 编排器总预算开火，否则编排器来不及打"卡在哪一步 / 还剩哪些 task"就被 halt。
        assertTrue(
            "watchdog 宽限 ${ProcessWatchdog.WATCHDOG_GRACE_MS}ms 必须大于总预算 ${TEARDOWN_TOTAL_BUDGET_MS}ms",
            ProcessWatchdog.WATCHDOG_GRACE_MS > TEARDOWN_TOTAL_BUDGET_MS
        )
        assertTrue(ProcessWatchdog.WATCHDOG_GRACE_MS <= 6_000L)
    }
}
