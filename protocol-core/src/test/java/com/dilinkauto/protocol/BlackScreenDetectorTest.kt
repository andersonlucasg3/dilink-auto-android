package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [BlackScreenDetector] 的持续黑屏策略（车机端 `VideoDecoder` 与桌面端
 * `VideoDecodePipeline` 共用，原先只在车机侧被覆盖）。
 *
 * 这套行为在抽取之前完全没有测试，所以这些用例同时充当"抽取本该带来的回归网"。
 * 时钟是注入的：持续窗口的时序是确定性的，不依赖墙上时钟。
 */
class BlackScreenDetectorTest {

    private fun detector(
        maxBytes: Int = 2048,
        alertStreak: Int = 3,
        sustainMs: Long = 3000L
    ): BlackScreenDetector =
        BlackScreenDetector(keyframeMaxBytes = maxBytes, alertStreak = alertStreak)
            .apply { this.sustainMs = sustainMs }

    private fun tiny(size: Int = 200) = size

    @Test
    fun nonKeyFramesAreIgnoredEntirely() {
        val d = detector()
        repeat(50) { d.onFrame(isKeyFrame = false, size = tiny(), nowMs = it * 100L) }
        assertEquals(0, d.streak())
        assertFalse(d.isAlerted())
        assertFalse(d.hasFiredRecovery())
    }

    @Test
    fun normallySizedKeyframesNeverAlert() {
        val d = detector()
        repeat(100) { d.onFrame(isKeyFrame = true, size = 4096, nowMs = it * 100L) }
        assertFalse(d.isAlerted())
        assertFalse(d.hasFiredRecovery())
        assertEquals(0, d.streak())
    }

    @Test
    fun alertFiresOnTheConfiguredStreak() {
        val d = detector(alertStreak = 3)
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 30)
        assertFalse("must not alert before the streak completes", d.isAlerted())
        d.onFrame(true, tiny(), 60)
        assertTrue(d.isAlerted())
    }

    @Test
    fun alertIsLatchedNotReFired() {
        val d = detector(alertStreak = 3, sustainMs = 3000L)
        // 20 tiny frames over 570ms: alerts, but has not yet sustained long enough.
        repeat(20) { d.onFrame(true, tiny(), it * 30L) }
        assertTrue(d.isAlerted())
        assertEquals(20, d.streak())
        assertFalse("570ms is well under the 3000ms window", d.hasFiredRecovery())
    }

    @Test
    fun aBriefBlackBurstDoesNotEscalate() {
        // The core safety property: warm-up produces a few tiny I-frames, and
        // escalating there caused the VD-leaking reconnect storm.
        var fired = 0
        val d = detector(sustainMs = 3000L).apply { onSustainedBlackScreen = { fired++ } }
        repeat(5) { d.onFrame(true, tiny(), it * 100L) }   // 400ms of black
        assertTrue("alert is expected", d.isAlerted())
        assertFalse("a short burst must NOT escalate", d.hasFiredRecovery())
        assertEquals(0, fired)
    }

    @Test
    fun sustainedBlackEscalatesExactlyOnce() {
        var fired = 0
        val d = detector(sustainMs = 3000L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 1000)
        d.onFrame(true, tiny(), 2000)
        assertEquals(0, fired)
        d.onFrame(true, tiny(), 3000)          // 3000ms sustained -> fire
        assertEquals(1, fired)
        assertTrue(d.hasFiredRecovery())
        repeat(20) { d.onFrame(true, tiny(), 3000 + it * 100L) }
        assertEquals("must never fire twice without a reset", 1, fired)
    }

    @Test
    fun escalationRequiresTheSustainWindowNotJustTheStreak() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 10_000L).apply { onSustainedBlackScreen = { fired++ } }
        repeat(50) { d.onFrame(true, tiny(), it * 300L) }   // 15s of frames, but each gap resets?
        assertTrue(d.isAlerted())
        // Frames are continuous, so blackSince is only set once at streak==1.
        assertEquals(1, fired)
    }

    @Test
    fun aNormalKeyframeClearsTheStreakAndReArms() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 1000L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 100)
        assertTrue(d.isAlerted())
        d.onFrame(true, 4096, 200)              // normal frame clears it
        assertFalse(d.isAlerted())
        assertEquals(0, d.streak())
        // Black again from scratch: must be able to alert and escalate once more.
        d.onFrame(true, tiny(), 300)
        d.onFrame(true, tiny(), 400)
        assertTrue(d.isAlerted())
        d.onFrame(true, tiny(), 1400)
        assertEquals(1, fired)
    }

    /**
     * 回归（2026-10-09 真机）：正常帧之后的**第一帧**微小关键帧不得升级。
     *
     * 原实现把正常帧分支的 `blackSinceMs` 写成 `0L` 哨兵。真实时钟从不是 0
     * 附近的小值 —— 桌面端用 `System.nanoTime()/1e6`、车机端用
     * `SystemClock.elapsedRealtime()`，都是"进程/开机后的毫秒数"，随便就是
     * 千万量级 —— 于是 `nowMs - 0 >= sustainMs` 对紧随其后的一帧立刻成立，
     * 每轮"正常帧 → 单帧微小"都误报持续黑屏。真机日志原话：
     * "连续 1 个微小关键帧且已持续 10000ms"。
     *
     * 所以本用例的关键是**大基线时钟**：`[nonKeyFramesAreIgnoredEntirely]` 等
     * 既有用例都从 0 起步，恰好掩盖了这个缺陷。
     */
    @Test
    fun singleTinyKeyframeAfterNormalFrameMustNotEscalateOnARealClock() {
        var fired = 0
        val d = detector(alertStreak = 3, sustainMs = 10_000L).apply {
            onSustainedBlackScreen = { fired++ }
        }
        // 进程已运行 4 小时 —— nanoTime / elapsedRealtime 的真实量级。
        val base = 4L * 60 * 60 * 1000
        d.onFrame(true, 8192, base)             // normal frame
        d.onFrame(true, tiny(), base + 33)      // 1st tiny frame of a new streak
        assertEquals("a single tiny keyframe right after a normal frame must not escalate", 0, fired)
        d.onFrame(true, tiny(), base + 2033)
        assertEquals("still well under the 10s window", 0, fired)
        d.onFrame(true, tiny(), base + 10_033)  // genuinely 10s of black
        assertEquals(1, fired)
    }

    /**
     * 大时钟下的完整生命周期：正常帧 → 短暂黑（不升级）→ 恢复正常 → 再黑白
     * 并真正满窗（升级一次）。修复前的 `0L` 哨兵会在第一个短暂黑处就升级。
     */
    @Test
    fun briefBlackFlashesOnARealClockOnlyEscalateWhenTheyActuallySustain() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 5000L).apply {
            onSustainedBlackScreen = { fired++ }
        }
        val base = 36L * 60 * 60 * 1000        // 36h uptime
        d.onFrame(true, 8192, base)
        // 一次 2 秒的短暂黑屏（正常帧间隔中的静止画面）。
        d.onFrame(true, tiny(), base + 100)
        d.onFrame(true, tiny(), base + 2100)
        assertEquals(0, fired)
        // 画面恢复。
        d.onFrame(true, 8192, base + 2200)
        // 又黑，这次满 5 秒。
        d.onFrame(true, tiny(), base + 2300)
        d.onFrame(true, tiny(), base + 5000)
        assertEquals(0, fired)
        d.onFrame(true, tiny(), base + 7400)   // 2300 + 5000 = 7300
        assertEquals(1, fired)
    }

    @Test
    fun resetReArmsEverythingIncludingTheEscalationLatch() {
        var fired = 0
        val d = detector(alertStreak = 1, sustainMs = 0L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        assertEquals(1, fired)
        d.reset()
        assertFalse(d.hasFiredRecovery())
        assertFalse(d.isAlerted())
        assertEquals(0, d.streak())
        d.onFrame(true, tiny(), 10)
        assertEquals("a fresh session gets a fresh chance", 2, fired)
    }

    @Test
    fun thresholdBoundaryIsExclusiveOfTheMax() {
        // size < keyframeMaxBytes counts as tiny; exactly max does not.
        val d = detector(maxBytes = 2048, alertStreak = 1, sustainMs = 0L)
        d.onFrame(true, 2048, 0)
        assertFalse("exactly at the threshold is NOT tiny", d.isAlerted())
        d.onFrame(true, 2047, 10)
        assertTrue(d.isAlerted())
    }

    @Test
    fun alertStreakOfOneFiresImmediately() {
        val d = detector(alertStreak = 1)
        d.onFrame(true, tiny(), 0)
        assertTrue(d.isAlerted())
    }

    /**
     * 默认时钟（`System.nanoTime()`）也必须可用：桌面端不注入时钟，
     * 这里确认调用默认参数不会抛异常且行为与传时钟一致。
     */
    @Test
    fun defaultClockIsUsableWithoutInjection() {
        var fired = 0
        val d = BlackScreenDetector(alertStreak = 1).apply {
            setSustainWindow(0L)
            onSustainedBlackScreen = { fired++ }
        }
        d.onFrame(isKeyFrame = true, size = 200)
        assertTrue(d.isAlerted())
        assertEquals(1, fired)
    }

    /** [BlackScreenDetector.setSustainWindow] 与直接赋值等价（会话窗口变更用）。 */
    @Test
    fun setSustainWindowReplacesPlainAssignment() {
        var fired = 0
        val d = BlackScreenDetector(alertStreak = 1).apply {
            onSustainedBlackScreen = { fired++ }
        }
        d.setSustainWindow(500L)
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 499)
        assertEquals(0, fired)
        d.onFrame(true, tiny(), 500)
        assertEquals(1, fired)
    }

    /**
     * 升级闸门关闭时：告警照常置位，但**不升级**。
     *
     * 场景（2026-10-17 MEIZU 真机）：VD 内容本身就是黑（空 launcher / 手机屏熄灭），
     * 编码器持续输出极小 I 帧，但解码在正常出帧 —— 画面黑不是故障，重连只会白烧预算。
     */
    @Test
    fun closedGateSuppressesEscalationButKeepsTheAlert() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 1000L).apply {
            onSustainedBlackScreen = { fired++ }
            decodingAliveCheck = { true }   // 解码仍在出帧
        }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 500)
        assertTrue("告警位不受闸门影响", d.isAlerted())
        d.onFrame(true, tiny(), 5000)
        assertEquals("黑 + 解码正常 = 内容黑，不得升级", 0, fired)
        assertFalse(d.hasFiredRecovery())
    }

    /**
     * 闸门只在**满窗之后**才被咨询：预热期的帧不该触发一次多余的判断。
     */
    @Test
    fun gateIsConsultedOnlyAfterTheSustainWindow() {
        var calls = 0
        val d = detector(alertStreak = 2, sustainMs = 1000L).apply {
            decodingAliveCheck = { calls++; true }
        }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 400)
        assertEquals("窗口未满时不问闸门", 0, calls)
        d.onFrame(true, tiny(), 1000)
        assertEquals("满窗那一帧问一次", 1, calls)
    }

    /**
     * 闸门先关后开：解码停摆之后必须还能升级（不能因为之前判过「内容黑」就永久闭嘴）。
     */
    @Test
    fun gateReopensAndEscalationStillHappens() {
        var fired = 0
        var alive = true
        val d = detector(alertStreak = 2, sustainMs = 1000L).apply {
            onSustainedBlackScreen = { fired++ }
            decodingAliveCheck = { alive }
        }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 2000)
        assertEquals("解码正常，不升级", 0, fired)
        alive = false                       // 解码线程卡死
        d.onFrame(true, tiny(), 2100)
        assertEquals("解码停摆，同一段黑屏立刻升级", 1, fired)
        assertTrue(d.hasFiredRecovery())
    }

    /**
     * 不设闸门时行为与抽取前完全一致（车机端未接线，不能因为桌面端的修复改变它）。
     */
    @Test
    fun absentGateKeepsLegacyEscalation() {
        var fired = 0
        val d = detector(alertStreak = 1, sustainMs = 1000L).apply {
            onSustainedBlackScreen = { fired++ }
        }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 1000)
        assertEquals(1, fired)
    }
}
