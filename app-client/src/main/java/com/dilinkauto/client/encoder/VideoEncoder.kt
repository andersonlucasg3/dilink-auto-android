package com.dilinkauto.client.encoder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.dilinkauto.protocol.Connection
import com.dilinkauto.protocol.VideoMsg
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the phone screen via MediaProjection and encodes it as H.264.
 * Uses AUTO_MIRROR mode at the car's landscape resolution.
 *
 * The virtual display mirrors the phone screen content scaled to the car's
 * dimensions. Apps launched on the physical screen are automatically captured.
 */
class VideoEncoder(
    @Volatile var connection: Connection,
    private val projection: MediaProjection
) {
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var encoderThread: HandlerThread? = null
    private val running = AtomicBoolean(false)
    private var frameCount = 0L

    var width = DEFAULT_WIDTH
        private set
    var height = DEFAULT_HEIGHT
        private set
    private var bitrate = DEFAULT_BITRATE
    private var fps = DEFAULT_FPS

    /** The virtual display's ID. Valid after start(). */
    val displayId: Int get() = virtualDisplay?.display?.displayId ?: -1

    /**
     * Configures the encoder to match the car's display resolution.
     * Call before [start].
     */
    fun configure(displayWidth: Int, displayHeight: Int) {
        width = displayWidth.coerceAtMost(MAX_WIDTH)
        height = displayHeight.coerceAtMost(MAX_HEIGHT)

        // Ensure dimensions are even (required by H.264)
        width = width and 0x7FFE
        height = height and 0x7FFE
    }

    fun start() {
        if (running.getAndSet(true)) return

        encoderThread = HandlerThread("VideoEncoder").apply { start() }
        val handler = Handler(encoderThread!!.looper)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    if (!running.get()) return

                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        if (info.size > 0 && connection.isConnected) {
                            val data = ByteArray(info.size)
                            buffer.get(data)

                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            val msgType = if (isConfig) VideoMsg.CONFIG else VideoMsg.FRAME
                            if (frameCount++ % 60 == 0L) {
                                Log.d(TAG, "Sending ${if (isConfig) "CONFIG" else "FRAME"} size=${data.size} total=$frameCount")
                            }
                            connection.sendVideo(msgType, data)
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send video frame", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error", e)
                    stop()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, handler)

            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }

        // Android 14+ requires a callback registered before createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }, handler)

        // Mirror the phone screen at the car's landscape resolution.
        // AUTO_MIRROR captures whatever is on the physical display, scaled to our dimensions.
        // Apps launched via ADB appear on the physical screen and are automatically captured.
        //
        // TODO: Replace with OWN_CONTENT_ONLY + Shizuku for true VD-native app rendering.
        // Android 14 blocks all activity launches on MediaProjection VDs (even from shell UID).
        // Shizuku bypasses this via IActivityTaskManager internal API.
        virtualDisplay = projection.createVirtualDisplay(
            "DiLinkAutoDisplay",
            width,
            height,
            DENSITY_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            handler
        )

        codec?.start()

        val vdId = virtualDisplay?.display?.displayId ?: -1
        Log.i(TAG, "Virtual display created: ${width}x${height} @ ${DENSITY_DPI}dpi, displayId=$vdId")
        Log.i(TAG, "Encoder started: ${bitrate / 1000}kbps ${fps}fps")

        // Periodically request keyframes to keep the stream alive
        Thread({
            while (running.get()) {
                Thread.sleep(1000)
                requestKeyFrame()
            }
        }, "KeyframeRequester").start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        virtualDisplay?.release()
        virtualDisplay = null

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping codec", e)
        }
        codec = null

        inputSurface?.release()
        inputSurface = null

        encoderThread?.quitSafely()
        encoderThread = null
    }

    fun requestKeyFrame() {
        if (!running.get()) return
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VideoEncoder"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1280
        const val DEFAULT_BITRATE = 4_000_000
        const val DEFAULT_FPS = 30
        const val I_FRAME_INTERVAL = 2
        const val DENSITY_DPI = 120
    }
}
