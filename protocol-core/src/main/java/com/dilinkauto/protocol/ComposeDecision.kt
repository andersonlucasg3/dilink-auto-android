package com.dilinkauto.protocol

/**
 * 静止画面下「本拍是否要合成一帧」的纯决策（issue #153-PA）。
 *
 * 放在 protocol-core 是为了让这段节拍逻辑能纯 JVM 表驱动单测，且手机侧
 * 的 GlPipeline 与编码器保活间隔共用一份语义。决策只回答「画不画」，
 * 不碰 EGL/GL 状态、不碰编码器输出排空 —— 调用方必须保留每拍的
 * dequeueOutputBuffer、码率自适应、IDR 闸门与写线程存活检查。
 *
 * 背景（`vd-server/.../GlPipeline.kt:184-200`）：原实现里 `frameAvail`
 * 只 gate `updateTexImage()`，紧接着的 `glClear → glDrawArrays →
 * eglPresentationTimeANDROID → eglSwapBuffers` 无条件执行。画面静止时
 * `tex/cb` 冻结而 `frames` 线性增长，即每拍都白做一次合成与编码提交。
 *
 * 保活（KEEP_ALIVE）不是降帧率：帧循环仍按目标 fps 走，只是无新帧时
 * 不提交 GL 帧，每隔 [VideoConfig.KEEP_ALIVE_COMPOSE_INTERVAL_MS] 用
 * 上一帧纹理重画一次，保证车机端持续收到帧。编码器另有
 * `repeat-previous-frame-after` 兜底，两者取同一间隔以免出现空窗
 * （issue #75 的教训：两端各写一个值必然漂移）。
 */
object ComposeDecision {

    enum class Action {
        /** 本拍无新帧且未到保活时刻：跳过整段 GL 绘制与交换。 */
        SKIP,

        /** 本拍无新帧但已到保活时刻：不取新纹理，用上一帧重画一次。 */
        KEEP_ALIVE,

        /** 本拍有新帧：取新纹理并正常绘制。 */
        DRAW,
    }

    /**
     * @param hasNewFrame SurfaceTexture 本拍是否收到新帧。
     * @param nowNanos 本拍的 `System.nanoTime()`。
     * @param lastComposeNanos 上一次真正合成（DRAW 或 KEEP_ALIVE）的时刻；
     *   0 表示本会话尚未合成过，按 KEEP_ALIVE 兜底，避免启动期一帧都没有。
     * @param keepAliveIntervalNanos 保活间隔（纳秒）；<= 0 时每拍保活，
     *   用于「禁用短路」的降级路径，绝不返回 SKIP 造成断流。
     */
    fun decide(
        hasNewFrame: Boolean,
        nowNanos: Long,
        lastComposeNanos: Long,
        keepAliveIntervalNanos: Long,
    ): Action {
        if (hasNewFrame) return Action.DRAW
        if (keepAliveIntervalNanos <= 0L) return Action.KEEP_ALIVE
        if (lastComposeNanos == 0L) return Action.KEEP_ALIVE
        val elapsed = nowNanos - lastComposeNanos
        // 时钟回退（或异常大的负值）时按「未到期」处理，避免一次异常时钟
        // 把保活打飞或让 elapsed 变成看似已到期的大正数。
        if (elapsed < 0L) return Action.SKIP
        return if (elapsed >= keepAliveIntervalNanos) Action.KEEP_ALIVE else Action.SKIP
    }
}
