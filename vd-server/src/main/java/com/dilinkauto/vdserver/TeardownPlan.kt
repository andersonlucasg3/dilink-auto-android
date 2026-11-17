package com.dilinkauto.vdserver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * issue #199：收尾（teardown）的总预算。
 *
 * 现场（2026-11-16 `.tmp/run-1116/vd-server.log`）18 步从未走完：最慢的 `apps-moved`
 * 单步 861~3079ms，两次被 3s watchdog 就地 halt。原来的 3s 只盯"总时长"，一步慢就
 * 把后面 13 步全部掐掉（连面板/IME 恢复都可能没跑）。
 *
 * 现在的契约：每一步有自己的预算（[TeardownStepPlan.budgetMs]），超时只放弃那一步并
 * 继续；[TEARDOWN_TOTAL_BUDGET_MS] 只是兜底上限。正常路径实测 ≈1~2s，**不是**靠放大
 * 3s 掩盖问题（那只会让用户多等）。
 *
 * 4.8s 的依据（PR #208 评审）：前 5 步的独立预算合计
 * `100 + 800 + 1200 + 500 + 1800 = 4400ms`，取 4.8s 才能保证「慢机上这 5 步全部吃满」
 * 之后 `panel-restored` / `ime-restored` 仍然拿到正预算被执行（否则 #199 的现象会平移到
 * "面板/IME 没恢复"）。watchdog 宽限 = 总预算 + [TEARDOWN_WATCHDOG_MARGIN_MS]。
 */
internal const val TEARDOWN_TOTAL_BUDGET_MS = 4_800L

/**
 * watchdog 宽限比编排器总预算多出的余量。
 *
 * 必须 > 0：编排器要在 watchdog 开火**之前**把"当前步 / 剩余步 / 剩余 task"打完，
 * 否则现场仍然只有一句"cleanup did not complete"（`TeardownBudgetTest` 锁住这条关系）。
 */
internal const val TEARDOWN_WATCHDOG_MARGIN_MS = 500L

/**
 * 前 5 步（`begin -> window-monitor-stopped -> letterbox-reset -> rotation-free -> apps-moved`）
 * 的独立预算（PR #208 评审要求：这几步即使各自吃满，也必须给 `panel-restored` /
 * `ime-restored` 留下正预算）。
 */
internal const val TEARDOWN_BUDGET_BEGIN_MS = 100L
internal const val TEARDOWN_BUDGET_WINDOW_MONITOR_MS = 800L
internal const val TEARDOWN_BUDGET_LETTERBOX_MS = 1_200L
internal const val TEARDOWN_BUDGET_ROTATION_MS = 500L
internal const val TEARDOWN_BUDGET_APPS_MOVED_MS = 1_800L

/**
 * 前 5 步预算之和（=4400ms）。
 *
 * 不变式：[TEARDOWN_TOTAL_BUDGET_MS] 必须 **大于** 它，否则慢机上前 5 步吃满预算后
 * `PANEL_RESTORED` / `IME_RESTORED` 会拿到 `remainingBudget<=0` 被 `SKIPPED_BUDGET` ——
 * 等于把 #199 的现象（面板/IME 没恢复）平移了一遍。`TeardownBudgetTest` 锁死这条。
 */
internal const val TEARDOWN_PRE_PANEL_BUDGETS_MS =
    TEARDOWN_BUDGET_BEGIN_MS + TEARDOWN_BUDGET_WINDOW_MONITOR_MS + TEARDOWN_BUDGET_LETTERBOX_MS +
        TEARDOWN_BUDGET_ROTATION_MS + TEARDOWN_BUDGET_APPS_MOVED_MS

/** 单步的最终状态（issue #199）。 */
internal enum class TeardownOutcome {
    /** 正常跑完。 */
    COMPLETED,
    /** 动作抛异常（已记录，后续步骤照跑）。 */
    FAILED,
    /** 超出单步预算：放弃该步并继续（见 [TeardownRunner] 类头）。 */
    TIMED_OUT,
    /** 总预算已用尽，未执行。 */
    SKIPPED_BUDGET,
    /** 它依赖的前序步骤没跑完（超时/失败），这一步没有意义且可能与前序残留线程打架。 */
    SKIPPED_DEPENDENCY
}

/**
 * 一步收尾的执行计划。
 *
 * [budgetMs] 是该步的**独立**预算；`action` 在专用线程里执行，超时只标记不改后续顺序
 * （顺序由 [TeardownStep] 枚举保证，见 [isTeardownStepForward]）。
 *
 * [dependsOn] 是**硬编码的单条依赖**（评审建议：不做依赖图）：前序步 `TIMED_OUT`/`FAILED`
 * 时本步记 `SKIPPED_DEPENDENCY` 并记录原因。目前只有
 * `DISPLAY_SNAPSHOT -> APPS_MOVED`（快照读的正是搬迁结果，搬迁没做完就没必要读，
 * 而且被放弃的搬迁线程还在用 shell/dumpsys，读了也是竞态数据）。
 * `SHELL_CLOSED` / `VIRTUAL_DISPLAY_RELEASED` **故意不挂依赖**：泄漏 shell/VD 的代价
 * 高于与残留线程竞争，它们的 IO 各自有超时与异常兜底，并在日志里显式告警。
 */
internal class TeardownStepPlan(
    val step: TeardownStep,
    val budgetMs: Long,
    val dependsOn: TeardownStep? = null,
    val action: () -> Unit
)

/** [TeardownRunner] 的产出：每步结局 + 是否干净收尾。 */
internal class TeardownReport(
    val outcomes: Map<TeardownStep, TeardownOutcome>,
    val elapsedMs: Long
) {
    /** 有任一步没正常跑完（超时 / 失败 / 被预算跳过）即视为收尾不完整。 */
    val incomplete: Boolean get() = outcomes.values.any { it != TeardownOutcome.COMPLETED }

    /** 第一个没正常跑完的步骤（用于落 `cleanup-incomplete` 标记 / 日志）。 */
    val firstBadStep: TeardownStep?
        get() = outcomes.entries.firstOrNull { it.value != TeardownOutcome.COMPLETED }?.key

    fun outcomeOf(step: TeardownStep): TeardownOutcome? = outcomes[step]
}

/**
 * issue #199：teardown 步骤编排器（纯 JVM，单测可注入假步骤驱动）。
 *
 * 设计要点：
 *  1. **每步独立超时**：动作在一次性 daemon 线程里跑，编排线程 `await(budget)`。超时
 *     只把该步标 `TIMED_OUT` 并记录，然后继续下一步 —— 原来"一步慢就整段被 watchdog
 *     halt"会把面板/IME 恢复一起吃掉，代价远大于放弃那一步。
 *  2. **总预算兜底**：剩余预算不够时逐步标记 `SKIPPED_BUDGET`，并把"剩余步 + 现场
 *     （[pendingDetail]，例如还没搬回的 task id）"打进日志，下一次现场能直接定位。
 *  3. **日志格式不变**：步完成后仍打 `[teardown-step] NN/18 label (+Xms)`，与历史
 *     日志可对账；异常行用 `[teardown-step] NN/18 label ...` 前缀 + err 通道。
 *
 * 超时被放弃的步骤线程**无法被安全杀死**（JVM 没有 Thread.stop 的安全替代）。它是
 * daemon，且步骤内部都是带超时的 shell/IO 调用；这里选择记录并继续，而不是让整段收尾
 * 被它拖死（拖死的后果是 watchdog `Runtime.halt(1)`，连日志都留不下）。
 */
internal class TeardownRunner(
    private val steps: List<TeardownStepPlan>,
    private val totalBudgetMs: Long = TEARDOWN_TOTAL_BUDGET_MS,
    private val log: (String) -> Unit = { PipeLog.log(it) },
    private val err: (String) -> Unit = { PipeLog.err(it) },
    private val nanoTime: () -> Long = { System.nanoTime() },
    /** 超时 / 预算用尽时附带的现场（剩余 task id 等），由调用方注入。 */
    private val pendingDetail: () -> String = { "" },
    /** 每一步开跑前的回调（步号 1-based、总步数、步骤）：给 watchdog 侧的可观测进度用。 */
    private val onStepStart: (Int, Int, TeardownStep) -> Unit = { _, _, _ -> },
    /** 每一步的结局回调：调用方据此对"被放弃的步"做后续保护（见 [TeardownStepPlan.dependsOn]）。 */
    private val onStepFinished: (TeardownStep, TeardownOutcome) -> Unit = { _, _ -> },
    /** 步骤执行线程工厂：默认一条步骤一个 daemon 线程（join 有界）。 */
    private val startStepThread: (String, () -> Unit) -> Thread = { name, block ->
        Thread(block, name).apply { isDaemon = true; start() }
    }
) {

    fun run(): TeardownReport {
        val startedAtNanos = nanoTime()
        val outcomes = LinkedHashMap<TeardownStep, TeardownOutcome>()
        var budgetExhausted = false
        var current: TeardownStep? = null

        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(nanoTime() - startedAtNanos)
        fun number(index: Int): String = "${(index + 1).toString().padStart(2, '0')}/${steps.size}"
        fun remainingFrom(index: Int): String =
            steps.drop(index).joinToString(prefix = "[", postfix = "]") { "${it.step.label}" }
        fun detailSuffix(): String = pendingDetail().takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""

        for ((index, plan) in steps.withIndex()) {
            val num = number(index)
            if (!isTeardownStepForward(current, plan.step)) {
                err("[teardown-step] order violation: ${current?.label} -> ${plan.step.label}")
            }
            // 依赖检查放在预算之前：原因比"没预算"更有诊断价值，也让日志能直接指向前序步。
            val dep = plan.dependsOn
            val depOutcome = dep?.let { outcomes[it] }
            if (dep != null && depOutcome != TeardownOutcome.COMPLETED) {
                outcomes[plan.step] = TeardownOutcome.SKIPPED_DEPENDENCY
                err(
                    "[teardown-step] $num ${plan.step.label} SKIPPED — 依赖 ${dep.label} 未完成 " +
                        "(${depOutcome ?: "<未执行>"})${detailSuffix()}"
                )
                onStepFinished(plan.step, TeardownOutcome.SKIPPED_DEPENDENCY)
                current = plan.step
                continue
            }
            val remainingBudget = totalBudgetMs - elapsedMs()
            if (budgetExhausted || remainingBudget <= 0L) {
                budgetExhausted = true
                outcomes[plan.step] = TeardownOutcome.SKIPPED_BUDGET
                if (outcomes.values.count { it == TeardownOutcome.SKIPPED_BUDGET } == 1) {
                    err(
                        "[teardown-step] 总预算 ${totalBudgetMs}ms 用尽于 $num ${plan.step.label} — " +
                            "跳过剩余步骤 ${remainingFrom(index)}${detailSuffix()}"
                    )
                }
                onStepFinished(plan.step, TeardownOutcome.SKIPPED_BUDGET)
                current = plan.step
                continue
            }
            val budget = minOf(plan.budgetMs, remainingBudget)
            onStepStart(index + 1, steps.size, plan.step)
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            startStepThread("Teardown-${plan.step.label}") {
                try {
                    plan.action()
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    done.countDown()
                }
            }
            val finished = try {
                done.await(budget, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            when {
                !finished -> {
                    outcomes[plan.step] = TeardownOutcome.TIMED_OUT
                    err(
                        "[teardown-step] $num ${plan.step.label} TIMEOUT (>${budget}ms) — " +
                            "放弃该步并继续，剩余步骤 ${remainingFrom(index + 1)}${detailSuffix()}"
                    )
                }
                failure.get() != null -> {
                    outcomes[plan.step] = TeardownOutcome.FAILED
                    err(
                        "[teardown-step] $num ${plan.step.label} FAILED (+${elapsedMs()}ms): " +
                            "${failure.get()?.describe()}"
                    )
                }
                else -> {
                    outcomes[plan.step] = TeardownOutcome.COMPLETED
                    log("[teardown-step] $num ${plan.step.label} (+${elapsedMs()}ms)")
                }
            }
            onStepFinished(plan.step, outcomes.getValue(plan.step))
            current = plan.step
        }

        val elapsed = elapsedMs()
        if (outcomes.values.any { it != TeardownOutcome.COMPLETED }) {
            err(
                "[teardown-step] cleanup incomplete（总耗时 ${elapsed}ms）：" +
                    outcomes.entries.filter { it.value != TeardownOutcome.COMPLETED }
                        .joinToString(prefix = "[", postfix = "]") { "${it.key.label}=${it.value}" } +
                    detailSuffix()
            )
        }
        return TeardownReport(outcomes, elapsed)
    }
}
