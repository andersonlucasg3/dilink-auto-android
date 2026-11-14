package com.dilinkauto.vdserver

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.*
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import com.dilinkauto.protocol.*
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * EGL + GLES2 pipeline that renders the VD's SurfaceTexture into the
 * MediaCodec encoder's input surface.
 *
 * All EGL/GL state lives on the pipeline thread — created in
 * [initEglAndSurfaceTexture], used in [pipelineLoop], and torn down by that same
 * thread (audit S-M8: cross-thread eglDestroy* on a context made current elsewhere
 * is a crash, not a leak). [cleanup] is what the pipeline thread's finally calls. The pipeline samples the SurfaceTexture at [fps] (paced by
 * [frameIntervalNanos]) and hands each encoded frame to [VideoFrameSender]
 * (bounded queue + its own writer thread), so a slow car socket can never park
 * this loop — see issue #3. Adaptive bitrate drops bandwidth when the car's
 * socket backs up and recovers it after a sustained clean interval.
 *
 * Extracted from [PipelineServer] to isolate the GL/encode hot path
 * (THREAD_PRIORITY_URGENT_DISPLAY) from the connection lifecycle.
 */
internal class GlPipeline(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val fps: Int,
    private val frameIntervalNanos: Long,
    private val bitrate: Int
) {
    private var stTexId = 0
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var glProgram = 0
    private var glPosLoc = 0
    private var glTexLoc = 0
    private var uTexMatrixLoc = 0
    private val texMatrix = FloatArray(16)
    private var quadBuf: FloatBuffer? = null

    private var vdInputSurface: Surface? = null
    private var stTexture: android.graphics.SurfaceTexture? = null

    // ── Frame-arrival signal ──────────────────────────────────────────────
    //
    // 2026-10-10 实机缺陷（车机屏概率性全黑，~50%，MI 9 / Android 15）：
    // 帧到达回调曾注册在 pipelineLoop() 里，而 pipelineLoop 要等"客户端连接
    // + unpark"才开始 —— VD 创建 → 客户端连接之间 SurfaceFlinger 提交的所有
    // 帧（HOME 启动动画、随后的稳定画面）既不会触发回调、也不会被
    // updateTexImage 消费；buffer queue 被占满后 SF 的后续提交还会阻塞。
    // 结果 tex/cb 停在 0 或某个小值后永久静止、"最新纹理"停在某张陈旧或
    // 未初始化的黑帧上，编码器无限重编码黑帧 → 车机屏全黑。
    //
    // 现在回调在 initEglAndSurfaceTexture() 里注册（早于 VD 创建），VD 的
    // 第一帧起就有记录：pipelineLoop 启动时 flag 已累积置位，第一轮就
    // updateTexImage 取到"队列最新帧"（SF 的最后一次提交 = 当前稳定画面）。
    private val frameAvail = AtomicBoolean(false)
    private val frameCbCount = AtomicLong(0)
    private var cbThread: android.os.HandlerThread? = null

    /** S-M8: guards [cleanup] so EGL handles are destroyed at most once. */
    private val glTornDown = AtomicBoolean(false)

    /** The encoder surface, supplied by the caller before [initEglAndSurfaceTexture]. */
    var encoderSurface: Surface? = null

    /** VD input surface, ready after [initEglAndSurfaceTexture] returns. */
    fun vdSurface(): Surface? = vdInputSurface

    // Logging goes straight to the PipeLog singleton — no local alias (audit R3-DRY-22③).

    // Write-loop policy (5s deadline + 100us backoff) now comes from FrameCodec.

    /**
     * IDR 补帧请求闸门状态（issue #75 / #163）：与车机端共用 protocol-core 的 [SyncRequestGate]，
     * 滑动窗口「1s 内最多 2 次」。管线是单线程，直接持有最新不可变状态即可。
     */
    private var syncRequestGate = SyncRequestGate()

    /**
     * Initialize EGL/GLES on the current thread, create the SurfaceTexture
     * and its GL texture, and the VD input surface. Must be called from the
     * pipeline thread.
     */
    fun initEglAndSurfaceTexture() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(display, ver, 0, ver, 1)
        val cfgA = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val nc = IntArray(1)
        EGL14.eglChooseConfig(display, cfgA, 0, cfgs, 0, 1, nc, 0)
        val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreateWindowSurface(display, cfgs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surf, surf, ctx)
        eglDisplay = display; eglContext = ctx; eglSurface = surf

        Matrix.setIdentityM(texMatrix, 0)
        // GL program
        glProgram = createProgram(); GLES20.glUseProgram(glProgram)
        glPosLoc = GLES20.glGetAttribLocation(glProgram, "aPosition")
        glTexLoc = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")
        GLES20.glViewport(0, 0, encodeWidth, encodeHeight)

        // Create GL texture for SurfaceTexture (VD input)
        val texIds = IntArray(1); GLES20.glGenTextures(1, texIds, 0); stTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        stTexture = android.graphics.SurfaceTexture(stTexId)
        stTexture!!.setDefaultBufferSize(displayWidth, displayHeight)
        vdInputSurface = Surface(stTexture)

        // Register the frame-arrival listener NOW — before the VirtualDisplay
        // exists (PipelineServer creates it only after this method signals
        // ready). Must not live in pipelineLoop(): see the field comment above.
        cbThread = android.os.HandlerThread("PipeCB").apply { start() }
        stTexture!!.setOnFrameAvailableListener({
            frameCbCount.incrementAndGet()
            frameAvail.set(true)
        }, android.os.Handler(cbThread!!.looper))

        // Fullscreen quad
        val quad = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadBuf!!.put(quad).position(0)

        PipeLog.log("EGL/GL ready, VD input surface created")
    }

    /**
     * Render loop: pace at [frameIntervalNanos], drain the SurfaceTexture,
     * draw the fullscreen quad, swap, and ship each encoded frame to the car.
     * Returns when [running] turns false.
     */
    fun pipelineLoop(
        encoder: MediaCodec,
        carVideo: SocketChannel,
        running: () -> Boolean,
        consumeSyncRequest: () -> Boolean,
    ) {
        val st = stTexture ?: return
        val bufInfo = MediaCodec.BufferInfo()

        // Frame sync: the arrival listener was registered in
        // initEglAndSurfaceTexture (see the field comment there for why).
        // Frames that arrived while this loop was still parked waiting for the
        // car are already flagged — the first iteration below drains the flag,
        // so updateTexImage() picks the queue's most recent frame instead of a
        // stale/black one.
        var nextFrameNanos = System.nanoTime()
        var frameCount = 0L; var keyFrameCount = 0L; var lastLogAt = 0L
        var texUpdates = 0L; var swapFails = 0L
        var sendDrops = 0L; var lastWriteMs = 0L
        val adaptive = AdaptiveBitrate(bitrate)
        // 有界队列 + 独立写线程（issue #3）：本线程只入队，写慢不再反压 updateTexImage。
        val sender = VideoFrameSender(
            writeFrame = { FrameCodec.writeFrameToChannel(carVideo, it) },
            closeChannel = { runCatching { carVideo.close() } }
        )
        sender.start()
        PipeLog.log("Pipeline: ${encodeWidth}x${encodeHeight} ${fps}fps ${bitrate/1_000_000}Mbps")

        try {
            while (running()) {
                val waitNs = nextFrameNanos - System.nanoTime()
                if (waitNs > 0) LockSupport.parkNanos(waitNs)
                nextFrameNanos += frameIntervalNanos
                if (nextFrameNanos <= System.nanoTime()) nextFrameNanos = System.nanoTime() + frameIntervalNanos

                val hasNewFrame = frameAvail.getAndSet(false)
                if (hasNewFrame) {
                    st.updateTexImage()
                    st.getTransformMatrix(texMatrix)
                    texUpdates++
                }

                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
                val qb = quadBuf!!; qb.position(0)
                GLES20.glVertexAttribPointer(glPosLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glPosLoc)
                qb.position(2); GLES20.glVertexAttribPointer(glTexLoc, 2, GLES20.GL_FLOAT, false, 16, qb); GLES20.glEnableVertexAttribArray(glTexLoc)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                val ptsNanos = computeFramePtsNanos(hasNewFrame, st.timestamp, System.nanoTime())
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNanos)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) swapFails++

                var drained = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(bufInfo, 0)
                    if (idx < 0) break
                    if (idx >= 0) {
                        val buf = encoder.getOutputBuffer(idx)
                        if (buf != null && bufInfo.size > 0) {
                            val payload = ByteArray(bufInfo.size); buf.get(payload)
                            val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val msgType = if (isConfig) VideoMsg.CONFIG else VideoMsg.FRAME
                            val isKey = (bufInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            if (isKey) keyFrameCount++
                            // 入队即返回：写不进队列时按直播优先策略丢 P 帧，本线程不回退。
                            sender.offer(msgType, payload, isKey)
                            drained++; frameCount++
                        }
                        encoder.releaseOutputBuffer(idx, false)
                    }
                }

                // 采样写线程观测值：慢写驱动自适应码率，丢帧驱动一次（限流的）IDR。
                // 原先这两件事都由「本帧写耗时」直接触发；现在写发生在别的线程上。
                val stats = sender.sampleStats()
                if (stats.maxWriteNanos > 0) {
                    lastWriteMs = stats.maxWriteNanos / 1_000_000
                    if (adaptive.onFrameWritten(stats.maxWriteNanos)) {
                        applyBitrate(encoder, adaptive.currentBitrate)
                        if (adaptive.needsSyncFrame) { requestSyncFrameThrottled(encoder, "bitrate drop"); adaptive.onBitrateApplied() }
                    }
                }
                if (stats.dropped > 0) {
                    sendDrops += stats.dropped
                    requestSyncFrameThrottled(encoder, "sendDrops")
                }
                // 车机端报告参考链断裂（issue #9）：它那边已经在丢 P 帧等 IDR 了，
                // 这里尽快补一个同步帧，别让车机等到下一个自然 GOP 边界。
                //
                // 日志必须区分「授予」与「被限流」：issue #75 之前这一行在限流**之前**
                // 无条件打印，把被吞掉的请求也记成「car requested IDR」，导致 vd-server.log
                // 里的计数与真实 IDR 数（22 vs 车机侧 0x33=22，其中 1 次并未产生 IDR）对不上。
                if (consumeSyncRequest()) {
                    if (requestSyncFrameThrottled(encoder, "car request")) {
                        PipeLog.log("Pipeline: car requested IDR (granted)")
                    }
                }
                if (sender.failed) {
                    // 写线程 5s 无进展已退出：video 链路确实死了，结束管线交给会话层重连。
                    PipeLog.err("Pipeline: video send stalled, exiting for reconnect (frames=$frameCount sendDrops=$sendDrops)")
                    break
                }

                if (frameCount - lastLogAt >= 120) {
                    lastLogAt = frameCount
                    PipeLog.log("Pipeline: $frameCount frames ${adaptive.currentBitrate/1_000_000}Mbps keys=$keyFrameCount tex=$texUpdates cb=${frameCbCount.get()} swapFails=$swapFails q=${sender.queued} sendDrops=$sendDrops maxW=${lastWriteMs}ms ts=${st.timestamp} glErr=${GLES20.glGetError()}")
                }
            }
        } finally {
            // 先停写线程再拆 GL：close() 关 video channel 解开可能卡在 writeAll 里的写线程。
            sender.close()
            // cbThread is non-daemon; if writeFrame throws or GL faults, the
            // parked Looper would prevent a clean JVM exit. quitSafely in
            // finally — cleanup() also quits it as a fallback for the path
            // where this loop never ran (bind/accept failure).
            cbThread?.quitSafely()
        }
        PipeLog.log("Pipeline exited: $frameCount frames")
    }

    /**
     * 请求一个 IDR，但**限流到 [VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS] 窗口内最多
     * [VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW] 次**。
     *
     * 现场日志（issue #3）：丢帧/降码率后立刻索要 IDR 会在大 I 帧上再卡一次写，
     * 下一次采样又降码率——小正反馈。自然 IDR 的节奏由
     * [PipelineServer] 的 `I_FRAME_INTERVAL` 决定（issue #70 起改为 3s），这里只负责
     * 「丢过帧、参考链断了」时的尽快修复，不必每帧都发。
     *
     * 闸门是 [SyncRequestGate]（与车机端同一状态机，issue #75 / #163）：旧实现是硬性 1s
     * 间隔，会吞掉「刚恢复又断链」的二次请求，车机因此干等 3s 自然 GOP（issue #163 实测
     * 冻结 2894ms）；滑动窗口允许窗口内放行 2 次，既补二次缺口又不放开 IDR 风暴。
     *
     * @param reason 触发来源（日志用）：`car request` / `sendDrops` / `bitrate drop`。
     * @return true 表示本次**真的**调用了 encoder.setParameters（授予）；false 表示被
     *   本闸门拒绝（此时留一条节流日志，不再静默 return）。
     */
    private fun requestSyncFrameThrottled(enc: MediaCodec, reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val next = syncRequestGate.tryAcquire(now)
        if (next == null) {
            val lastGrant = syncRequestGate.grantsMs.lastOrNull()
            PipeLog.log(
                "Pipeline: IDR request throttled ($reason, window ${VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS}ms/" +
                    "${VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW} grants, last grant ${lastGrant?.let { now - it } ?: -1L}ms ago)"
            )
            return false
        }
        syncRequestGate = next
        requestSyncFrame(enc)
        return true
    }
    private fun applyBitrate(enc: MediaCodec, br: Int) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, br); enc.setParameters(p) } catch (_: Exception) {} }
    private fun requestSyncFrame(enc: MediaCodec) { try { val p = Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); enc.setParameters(p) } catch (_: Exception) {} }

    private fun createProgram(): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }
    private fun loadShader(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

    /**
     * Tear down EGL/GL resources. Idempotent.
     *
     * S-M8: must be called from the Pipeline thread — the EGL context was made current
     * there in [initEglAndSurfaceTexture], and the encoder may still be running its
     * native path. `PipelineServer.cleanup()` therefore never calls this directly; it
     * signals the pipeline thread to exit and this runs in that thread's own finally
     * (see `PipelineServer.releaseGlResources`). The guard below keeps a double call —
     * pipeline-thread finally plus a late stray caller — from destroying handles twice.
     */
    fun cleanup(
        deleteProgram: (Int) -> Unit = { if (it != 0) GLES20.glDeleteProgram(it) },
        deleteTextures: (Int) -> Unit = { if (it != 0) GLES20.glDeleteTextures(1, intArrayOf(it), 0) },
        terminateDisplay: (EGLDisplay) -> Unit = { EGL14.eglTerminate(it) }
    ) {
        if (!glTornDown.compareAndSet(false, true)) return
        val d = eglDisplay; val s = eglSurface; val c = eglContext
        if (glProgram != 0) {
            deleteProgram(glProgram)
            glProgram = 0
        }
        if (stTexId != 0) {
            deleteTextures(stTexId)
            stTexId = 0
        }
        if (d != null) EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (d != null && s != null) EGL14.eglDestroySurface(d, s)
        if (d != null && c != null) EGL14.eglDestroyContext(d, c)
        if (d != null) terminateDisplay(d)
        eglDisplay = null; eglSurface = null; eglContext = null
        // Stop the frame-callback looper BEFORE releasing the SurfaceTexture it
        // may still be posting from. Fallback for the path where pipelineLoop
        // never ran (its own finally is the primary stopper); quitSafely is
        // idempotent, so the double call is safe.
        cbThread?.quitSafely(); cbThread = null
        vdInputSurface?.release(); vdInputSurface = null
        stTexture?.release(); stTexture = null
    }

    internal fun setTestResources(program: Int, textureId: Int, display: EGLDisplay?) {
        glProgram = program
        stTexId = textureId
        eglDisplay = display
    }

    // IDR 请求闸门（窗口长度 + 窗口内次数 + 已放行时刻）都来自 :protocol-core 的
    // VideoConfig.SYNC_FRAME_REQUEST_WINDOW_MS / VideoConfig.SYNC_FRAME_REQUEST_MAX_PER_WINDOW
    // 与 SyncRequestGate，与车机端共用一份——见 GlPipeline.requestSyncFrameThrottled 与
    // issue #75 / #163。这里刻意不再保留手机端私有的间隔常量：两端各写一个私有值正是死区的成因。

    companion object {
        internal const val VERTEX_SHADER_SRC =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}"

        internal const val FRAGMENT_SHADER_SRC =
            "#extension GL_OES_EGL_image_external:require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}"

        internal fun computeFramePtsNanos(hasNewFrame: Boolean, surfaceTextureTimestamp: Long, nowNanos: Long): Long =
            if (hasNewFrame && surfaceTextureTimestamp > 0L) surfaceTextureTimestamp else nowNanos
    }
}
