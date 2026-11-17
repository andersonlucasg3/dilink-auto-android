package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #153 分节 PB 的 **base-red 墓碑**。
 *
 * 故意只引用 PB 之前就存在的 3 参 [VdDimensions.compute]，不引用 [VdAreaTier]
 * 或任何本分节新增的 API，因此这个文件可以原样拷进未打补丁的 pristine base
 * worktree 编译运行：
 *
 * - base（`main @ ee04ebf`）：失败，签名 `expected:<1926> but was:<2560>`，
 *   与 22:03 追评「合成面积 3.59MP 未降」一致；
 * - patch：通过，默认档把实车 VD 压到 1926x1054（2.03MP）。
 *
 * 其余档位契约在 [VdDimensionsTest]；本文件单独保留是为了满足
 * `AGENTS.md` §2「修红门禁先做基线」的 base-red 形态。
 */
class VdAreaTierBaselineTest {

    @Test
    fun defaultComputeShrinksRealCarCompositeArea() {
        val request = HandshakeRequest(
            deviceName = "test-car",
            screenWidth = 1204,
            screenHeight = 660,
            screenDpi = 160,
            appVersionCode = 1,
        )
        val (w, h, _) = VdDimensions.compute(request, 1368, 3192)
        assertEquals(1926, w)
        assertEquals(1054, h)
    }
}
