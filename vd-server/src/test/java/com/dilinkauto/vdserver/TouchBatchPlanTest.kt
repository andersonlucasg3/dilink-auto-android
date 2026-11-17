package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #60：批量 MOVE 里「没有对应的 DOWN」的指针必须被识别出来，供注入层补按下。
 *
 * 症状是「多指手势时某一根手指整段失效」；规则本身与 Android 无关，故在 JVM 上钉住。
 */
class TouchBatchPlanTest {

    @Test
    fun `已按下的指针不需要补 DOWN`() {
        val missing = TouchBatchPlan.pointersMissingDown(
            active = setOf(0, 1),
            moving = listOf(0, 1),
            maxPointers = 10,
        )
        assertTrue("都已按下，不该补任何 DOWN: $missing", missing.isEmpty())
    }

    @Test
    fun `缺少 DOWN 的指针按报文顺序列出`() {
        val missing = TouchBatchPlan.pointersMissingDown(
            active = setOf(0),
            moving = listOf(0, 3, 5),
            maxPointers = 10,
        )
        assertEquals(listOf(3, 5), missing)
    }

    @Test
    fun `重复出现的同一个 id 只补一次`() {
        val missing = TouchBatchPlan.pointersMissingDown(
            active = emptySet(),
            moving = listOf(2, 2, 2),
            maxPointers = 10,
        )
        assertEquals(listOf(2), missing)
    }

    @Test
    fun `越界 pointerId 被忽略`() {
        val missing = TouchBatchPlan.pointersMissingDown(
            active = emptySet(),
            moving = listOf(-1, 0, 10, 11),
            maxPointers = 10,
        )
        assertEquals(listOf(0), missing)
    }
}
