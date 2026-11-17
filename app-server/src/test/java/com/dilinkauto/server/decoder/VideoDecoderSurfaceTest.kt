package com.dilinkauto.server.decoder

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 校验 [VideoDecoder.switchSurface] 的 Surface 有效性检查与线程安全互斥屏障（issue #117）。
 */
class VideoDecoderSurfaceTest {

    private class FakeSurface(private var valid: Boolean) : Surface(SurfaceTexture(0)) {
        override fun isValid(): Boolean = valid
    }

    @Test
    fun switchSurface_invalidSurface_ignored() {
        val decoder = VideoDecoder()
        val invalidSurface = FakeSurface(valid = false)

        decoder.switchSurface(invalidSurface)
        assertFalse("无效 Surface 不得置 outputSurfaceValid 为 true", decoder.outputSurfaceValid)
    }

    @Test
    fun switchSurface_nullCodec_safe() {
        val decoder = VideoDecoder()
        val validSurface = FakeSurface(valid = true)

        // codec is null initially
        decoder.switchSurface(validSurface)
        assertFalse("codec 为 null 时不得标记有效", decoder.outputSurfaceValid)
    }

    @Test
    fun switchSurface_notRunning_safe() {
        val decoder = VideoDecoder()
        val validSurface = FakeSurface(valid = true)
        decoder.running.set(false)

        decoder.switchSurface(validSurface)
        assertFalse("未运行状态下不得标记有效", decoder.outputSurfaceValid)
    }

    @Test
    fun invalidateSurface_clearsOutputSurfaceValid() {
        val decoder = VideoDecoder()
        decoder.outputSurfaceValid = true
        assertTrue(decoder.outputSurfaceValid)

        decoder.invalidateSurface()
        assertFalse(decoder.outputSurfaceValid)
    }

    @Test
    fun switchSurface_blocksWhenCodecLockHeld() {
        val decoder = VideoDecoder()
        val validSurface = FakeSurface(valid = true)
        decoder.running.set(true)

        val lockAcquiredLatch = CountDownLatch(1)
        val releaseLockLatch = CountDownLatch(1)
        val switchCompleted = AtomicBoolean(false)

        val holderThread = Thread {
            synchronized(decoder.codecLock) {
                lockAcquiredLatch.countDown()
                releaseLockLatch.await(2, TimeUnit.SECONDS)
            }
        }
        holderThread.start()

        assertTrue(lockAcquiredLatch.await(1, TimeUnit.SECONDS))

        val switchThread = Thread {
            decoder.switchSurface(validSurface)
            switchCompleted.set(true)
        }
        switchThread.start()

        // Give switchThread time to attempt lock acquisition
        Thread.sleep(100)
        assertFalse("持有 codecLock 时 switchSurface 必须被阻塞互斥等待", switchCompleted.get())

        releaseLockLatch.countDown()
        switchThread.join(2000)
        assertTrue("释放 codecLock 后 switchSurface 必须恢复推进", switchCompleted.get())
        holderThread.join(1000)
    }
}
