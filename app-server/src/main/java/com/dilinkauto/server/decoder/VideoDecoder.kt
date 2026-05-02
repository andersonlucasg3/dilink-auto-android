package com.dilinkauto.server.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.dilinkauto.protocol.VideoConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes H.264 video frames received from the phone and renders them to a Surface.
 *
 * Frames are queued from the network thread and fed to MediaCodec on a dedicated thread.
 * CONFIG frames (SPS/PPS) are cached so the decoder can be started/restarted at any time.
 */
class VideoDecoder {

    /** Log callback — set by CarConnectionService to route logs to the phone via protocol. */
    var logSink: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logSink?.invoke("[VideoDecoder] $msg")
    }

    private fun logW(msg: String) {
        Log.w(TAG, msg)
        logSink?.invoke("[VideoDecoder][W] $msg")
    }

    private fun logE(msg: String) {
        Log.e(TAG, msg)
        logSink?.invoke("[VideoDecoder][E] $msg")
    }

    private var codec: MediaCodec? = null
    private var feedThread: Thread? = null
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()
    private val frameQueue = ArrayBlockingQueue<FrameData>(15) // 500ms @ 30fps — reduced for low-end devices

    // CONFIG data is cached separately so it's never lost, even if it arrives
    // before start() is called or while the queue is full.
    @Volatile
    private var configData: ByteArray? = null

    private var frameCount = 0L
    private var receiveCount = 0L
    private var renderCount = 0L
    private var dropCount = 0L
    private var inputFailCount = 0L
    private var keyFramesReceived = 0L
    private var keyFramesFed = 0L
    private var keyFramesDropped = 0L

    /** Check if H.264 NAL data contains an IDR frame (NAL type 5) */
    private fun isKeyFrame(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = if (data[i + 2] == 1.toByte()) i + 3
                    else if (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()) i + 4
                    else { i++; continue }
                if (nalStart < data.size) {
                    if ((data[nalStart].toInt() and 0x1F) == 5) return true
                }
            }
            i++
        }
        return false
    }

    data class FrameData(val isConfig: Boolean, val isKeyFrame: Boolean, val data: ByteArray)

    /**
     * Starts the decoder, rendering to the provided Surface.
     */
    fun start(surface: Surface, width: Int, height: Int, fps: Int = VideoConfig.TARGET_FPS) {
        if (running.getAndSet(true)) {
            logW("start() called but already running")
            return
        }

        log("Starting decoder: ${width}x${height} @${fps}fps, cached config=${configData != null}, queued=${frameQueue.size}")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
        log("MediaCodec created and started")

        frameCount = 0
        renderCount = 0
        dropCount = 0
        inputFailCount = 0
        var configFed = false

        feedThread = Thread({
            val decoder = codec ?: run {
                logE("Feed thread: codec is null!")
                return@Thread
            }
            log("Feed thread started")

            // If we have cached config, feed it first before any frames.
            // Without SPS/PPS, the decoder silently fails on non-config frames.
            configData?.let { config ->
                log("Feeding cached CONFIG (${config.size} bytes)")
                feedBuffer(decoder, config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                configFed = true
            }

            // Catchup: graduated speedup based on queue depth.
            // When the queue is slightly behind, gentle 1.5x catchup.
            // When severely backed up, aggressive 3x catchup to recover quickly.
            // Always feeds keyframes — skipping them would prolong artifacts.
            //
            // Thresholds computed from actual fps (not VideoConfig.TARGET_FPS).
            // Each threshold targets a specific real-time latency budget:
            //   normal  (0-100ms)  — feed all frames
            //   gentle  (100-200ms) — skip 1 of 3 non-keyframes (1.5x catchup)
            //   medium  (200-333ms) — skip 1 of 2 non-keyframes (2x catchup)
            //   aggressive (333ms+) — skip 2 of 3 non-keyframes (3x catchup)
            val catchupGentle = (100L * fps / 1000).toInt().coerceAtLeast(3)
            val catchupMedium = (200L * fps / 1000).toInt().coerceAtLeast(6)
            val catchupAggressive = (333L * fps / 1000).toInt().coerceAtLeast(10)
            var skipCount = 0L

            while (running.get()) {
                val frame = try {
                    frameQueue.poll(1000L / fps, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    continue
                } ?: continue

                if (frame.isConfig) {
                    configData = frame.data
                    log("Feeding CONFIG (${frame.data.size} bytes)")
                    feedBuffer(decoder, frame.data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    configFed = true
                } else if (!configFed) {
                    // Skip non-config frames until we have SPS/PPS
                    if (frameCount == 0L) log("Waiting for CONFIG before feeding video frames")
                    continue
                } else {
                    val queueSize = frameQueue.size
                    val isKey = frame.isKeyFrame

                    // Graduated catchup: never skip keyframes
                    if (!isKey && queueSize > catchupGentle) {
                        val shouldSkip = when {
                            queueSize > catchupAggressive -> frameCount % 3 != 0L // 3x: feed 1 of 3
                            queueSize > catchupMedium    -> frameCount % 2 == 1L  // 2x: feed 1 of 2
                            else                         -> frameCount % 3 == 2L  // 1.5x: feed 2 of 3
                        }
                        if (shouldSkip) {
                            skipCount++
                            if (skipCount <= 3 || skipCount % 100 == 0L) {
                                log("Catchup: skip frame (queue=$queueSize gentle=$catchupGentle medium=$catchupMedium agg=$catchupAggressive total_skips=$skipCount)")
                            }
                            frameCount++
                            continue
                        }
                    }

                    if (isKey) {
                        keyFramesFed++
                        log("Feeding KEYFRAME #$keyFramesFed size=${frame.data.size} (fed=$frameCount rendered=$renderCount)")
                    }
                    feedBuffer(decoder, frame.data, 0)
                    frameCount++
                    if (frameCount % 30 == 0L) {
                        log("Fed $frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount keys_recv=$keyFramesReceived keys_fed=$keyFramesFed keys_drop=$keyFramesDropped queue=${frameQueue.size} skips=$skipCount")
                    }
                }

                // Drain all available output
                drainOutput(decoder)
            }
            log("Feed thread exiting: fed=$frameCount rendered=$renderCount drops=$dropCount")
        }, "VideoDecoderFeed").apply { start() }
    }

    private var consecutiveDrops = 0
    private val drainBufferInfo = MediaCodec.BufferInfo()

    private fun feedBuffer(decoder: MediaCodec, data: ByteArray, flags: Int) {
        try {
            val inputIndex = decoder.dequeueInputBuffer(50_000) // 50ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                inputBuffer.clear()
                inputBuffer.put(data)
                val pts = System.nanoTime() / 1000 // wall-clock microseconds
                decoder.queueInputBuffer(inputIndex, 0, data.size, pts, flags)
                consecutiveDrops = 0
            } else {
                consecutiveDrops++
                inputFailCount++
                if (consecutiveDrops <= 3 || consecutiveDrops % 10 == 0) {
                    logW("dequeueInputBuffer failed: consecutiveDrops=$consecutiveDrops, size=${data.size}, flags=$flags")
                }
                if (consecutiveDrops > 10) {
                    logW("Decoder stuck ($consecutiveDrops drops), flushing")
                    decoder.flush()
                    consecutiveDrops = 0
                    configData?.let { config ->
                        val idx = decoder.dequeueInputBuffer(50_000)
                        if (idx >= 0) {
                            val buf = decoder.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(config)
                            decoder.queueInputBuffer(idx, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            log("Re-fed CONFIG after flush")
                        } else {
                            logE("Cannot re-feed CONFIG after flush — dequeueInputBuffer failed")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE("Error feeding buffer to decoder: ${e.message}")
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val bufferInfo = drainBufferInfo
        try {
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true) // render to surface
                    renderCount++
                    if (renderCount <= 3 || renderCount % 30 == 0L) {
                        log("Rendered frame #$renderCount size=${bufferInfo.size} flags=${bufferInfo.flags}")
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Output format changed: ${decoder.outputFormat}")
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            logE("Error draining decoder output: ${e.message}")
        }
    }

    /**
     * Enqueues a received frame for decoding.
     * Called from the network thread — must not block.
     *
     * CONFIG frames are always cached, even if the decoder hasn't started yet.
     *
     * Drop strategy: never evict CONFIG or keyframes. When the queue is full:
     *  - P-frames: just drop the incoming frame (least harm)
     *  - Keyframes/CONFIG: evict the oldest P-frame from the queue to make room.
     *    Only drop a keyframe as absolute last resort (all queued frames are keyframes).
     */
    fun onFrameReceived(isConfig: Boolean, data: ByteArray) {
        receiveCount++
        val isKey = !isConfig && isKeyFrame(data)
        if (isKey) keyFramesReceived++
        if (isConfig || isKey || receiveCount <= 3 || receiveCount % 60 == 0L) {
            log("onFrameReceived #$receiveCount isConfig=$isConfig isKey=$isKey size=${data.size} running=${running.get()} queue=${frameQueue.size}")
        }
        // Always cache CONFIG — needed to bootstrap the next decoder instance
        if (isConfig) {
            configData = data
        }
        // When stopped, only cache CONFIG. Dropping frames during surface transitions
        // prevents the queue from filling up and causing a 400+ frame drop storm on restart.
        if (!running.get() && !isConfig) {
            if (isKey) keyFramesDropped++
            return
        }
        val frame = FrameData(isConfig, isKey, data)
        if (frameQueue.offer(frame)) return

        // Queue full — apply smart drop strategy
        if (!isConfig && !isKey) {
            // Incoming is a disposable P-frame: drop it, don't evict anything
            dropCount++
            if (dropCount <= 5 || dropCount % 30 == 0L) {
                logW("Queue full — dropped incoming P-frame #$dropCount (receive=$receiveCount)")
            }
            return
        }

        // Incoming is CONFIG or keyframe — try to evict a P-frame from the queue
        val iter = frameQueue.iterator()
        var evicted = false
        while (iter.hasNext()) {
            val f = iter.next()
            if (!f.isConfig && !f.isKeyFrame) {
                if (frameQueue.remove(f)) {
                    evicted = true
                    break
                }
            }
        }

        if (evicted) {
            // Made room — add the important frame
            if (frameQueue.offer(frame)) {
                dropCount++
                if (dropCount <= 5 || dropCount % 30 == 0L) {
                    logW("Queue full — evicted P-frame to keep ${if (isConfig) "CONFIG" else "KEYFRAME"} #$dropCount")
                }
            } else {
                dropCount++
                logW("Queue still full after eviction — dropped incoming ${if (isConfig) "CONFIG" else "KEYFRAME"}")
            }
        } else {
            // All queued frames are keyframes/CONFIG — drop oldest as last resort
            val dropped = frameQueue.poll()
            frameQueue.offer(frame)
            dropCount++
            if (dropped != null && !dropped.isConfig && dropped.isKeyFrame) {
                keyFramesDropped++
                logW("DROPPED KEYFRAME #$keyFramesDropped (last resort, all queued were keyframes)")
            }
            if (dropCount <= 5 || dropCount % 30 == 0L) {
                logW("Queue full — dropped oldest as last resort #$dropCount")
            }
        }
    }

    /**
     * Switches the decoder output to a new Surface without stopping/restarting.
     * Uses MediaCodec.setOutputSurface() — available since API 23 (Android 10+ BYD).
     * Eliminates the keyframe-drop storm that happens with stop()+start().
     */
    fun switchSurface(newSurface: Surface) {
        val c = codec
        if (c == null) {
            logW("switchSurface: codec is null, ignoring")
            return
        }
        try {
            c.setOutputSurface(newSurface)
            log("Switched decoder output to new Surface")
        } catch (e: Exception) {
            logE("switchSurface failed: ${e.message}")
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        log("Stopping decoder: fed=$frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount")
        frameQueue.clear()
        feedThread?.interrupt()
        try { feedThread?.join(2000) } catch (_: InterruptedException) {}
        feedThread = null
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            logW("Error stopping codec: ${e.message}")
        }
        codec = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
    }
}
