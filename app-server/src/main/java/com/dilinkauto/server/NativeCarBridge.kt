package com.dilinkauto.server

import android.view.Surface

/**
 * JNI bridge to the native car pipeline (libdilink-car.so).
 *
 * Replaces VideoDecoder.kt and the touch send path entirely.
 * The native pipeline handles: TCP connect → H.264 decode → Surface render
 * and touch encode → TCP send.
 *
 * Loaded once at app startup via System.loadLibrary.
 */
object NativeCarBridge {

    init {
        System.loadLibrary("dilink-car")
    }

    // ── Pipeline lifecycle ──

    /** Start the native pipeline. Listens on given ports for daemon to connect. */
    external fun nativeStart(
        videoPort: Int,
        inputPort: Int,
        surface: Surface?,
        displayWidth: Int,
        displayHeight: Int,
        encodeWidth: Int,
        encodeHeight: Int
    ): Int

    /** Stop the native pipeline. Blocks until threads exit. */
    external fun nativeStop()

    /** Switch decoder output surface (e.g., offscreen → TextureView).
     *  Must be called when MirrorScreen's TextureView surface is ready. */
    external fun nativeSetSurface(surface: Surface?): Boolean

    // ── Touch injection (called from Compose on UI thread) ──

    /** Send a touch DOWN event. Coordinates in display pixels. */
    external fun nativeTouchDown(x: Int, y: Int, pointerId: Int, pressure: Float)

    /** Send a touch MOVE event. */
    external fun nativeTouchMove(x: Int, y: Int, pointerId: Int, pressure: Float)

    /** Send a touch UP event. */
    external fun nativeTouchUp(x: Int, y: Int, pointerId: Int, pressure: Float)

    // ── State queries ──

    /** Whether the native pipeline is actively running. */
    external fun nativeIsRunning(): Boolean

    /** Whether at least one video frame has been received and decoded. */
    external fun nativeHasReceivedFrame(): Boolean
}
