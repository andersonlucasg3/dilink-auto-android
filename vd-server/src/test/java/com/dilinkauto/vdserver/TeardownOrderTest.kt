package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #165：锁定 `cleanup()` 的阶段顺序契约。
 *
 * 背景：FORTIFY（destroyed mutex）没有任何调用栈，只能靠收尾阶段二分。`PipelineServer.cleanup()`
 * 用 [TeardownStep] 打 `[teardown-step]` 行，本测试把「顺序不能退」和几条关键先后关系钉住 ——
 * 谁把 release 调到 join 之前、或把 VD 释放挪到中间，这里会先红。
 *
 * 纯 JVM：只依赖枚举与纯函数 [isTeardownStepForward]，不碰 Android/EGL/native 对象。
 */
class TeardownOrderTest {

    @Test
    fun walkingTheEnumInOrderIsAlwaysForward() {
        var current: TeardownStep? = null
        for (step in TeardownStep.entries) {
            assertTrue("step ${step.label} must be forward from $current", isTeardownStepForward(current, step))
            current = step
        }
    }

    @Test
    fun goingBackwardsOrRepeatingIsRejected() {
        assertFalse(isTeardownStepForward(TeardownStep.AUDIO_CLOSED, TeardownStep.SHELL_CLOSED))
        assertFalse(isTeardownStepForward(TeardownStep.ENCODER_RELEASED, TeardownStep.ENCODER_RELEASED))
        assertTrue("第一步没有前序，永远合法", isTeardownStepForward(null, TeardownStep.BEGIN))
    }

    @Test
    fun audioAndRenderThreadsAreJoinedBeforeNativeObjectsAreReleased() {
        // issue #165 的核心顺序：先停音频（AudioPipeline.close 内部 join 采集线程），
        // 再 join Pipeline 线程，最后才碰编码器/Surface。
        assertTrue(TeardownStep.AUDIO_CLOSED.ordinal < TeardownStep.PIPELINE_JOINED.ordinal)
        assertTrue(TeardownStep.PIPELINE_JOINED.ordinal < TeardownStep.ENCODER_RELEASED.ordinal)
        assertTrue(TeardownStep.ENCODER_RELEASED.ordinal < TeardownStep.ENCODER_SURFACE_RELEASED.ordinal)
        assertTrue(TeardownStep.ENCODER_SURFACE_RELEASED.ordinal < TeardownStep.GL_RELEASED.ordinal)
    }

    @Test
    fun panelIsRestoredWhileTheShellIsStillAlive() {
        // 既有约定（AGENTS.md §0 / 记忆）：面板恢复必须在杀 shell 之前，否则 restorePanel
        // 没人执行，手机会留在黑屏。
        assertTrue(TeardownStep.PANEL_RESTORED.ordinal < TeardownStep.SHELL_CLOSED.ordinal)
        assertTrue(TeardownStep.IME_RESTORED.ordinal < TeardownStep.SHELL_CLOSED.ordinal)
    }

    @Test
    fun virtualDisplayIsStillReleasedLast() {
        // 既有约定：virtualDisplay.release() 是最后一步；COMPLETE 只是日志终点。
        val ordered = TeardownStep.entries
        assertEquals(TeardownStep.VIRTUAL_DISPLAY_RELEASED, ordered[ordered.size - 2])
        assertEquals(TeardownStep.COMPLETE, ordered.last())
        assertEquals(TeardownStep.BEGIN, ordered.first())
    }

    @Test
    fun everyStepHasAUniqueLogLabel() {
        val labels = TeardownStep.entries.map { it.label }
        assertEquals("标签必须唯一，否则日志无法二分", labels.size, labels.toSet().size)
        labels.forEach { label ->
            assertTrue("标签用小写 kebab-case，便于 grep: $label", Regex("^[a-z][a-z0-9-]*$").matches(label))
        }
    }
}
