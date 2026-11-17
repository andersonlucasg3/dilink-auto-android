package com.dilinkauto.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Car-side icon cache — decodes and resizes icons once, then serves them instantly.
 *
 * The phone sends high-resolution source PNGs (192x192). When APP_LIST arrives,
 * [prepareAll] decodes and resizes every icon to the grid size in parallel on
 * background threads (8 A53 cores available). After that, [getPrepared] is an
 * O(1) ConcurrentHashMap lookup — zero coroutines, zero I/O, zero decode during scroll.
 */
class AppIconCache(private val cacheDir: File) {

    private val sourceCache = ConcurrentHashMap<String, ByteArray>()
    // Grid-size prepared bitmaps. Sole writer is [prepareAll], keyed by packageName.
    // [get] no longer mutates this map — it returns its own Bitmap at the requested
    // size without polluting the grid-size cache (which previously caused navbar/
    // notification icons rendered at 40dp to overwrite the 64dp grid bitmap).
    private val prepared = ConcurrentHashMap<String, ImageBitmap>()

    /** Incremented after each prepareAll() completes — UI observes this to recompose. */
    @Volatile var preparedVersion: Int = 0
        private set

    init { cacheDir.mkdirs() }

    /**
     * Full decode+resize path — used by NotificationScreen and NavBar (few icons).
     * Returns a Bitmap at the requested size. Does NOT write into [prepared] — the
     * grid cache is owned by [prepareAll] at the grid size only.
     */
    fun get(packageName: String, sizePx: Int): Bitmap? {
        val source = sourceCache[packageName] ?: loadSourceFromDisk(packageName) ?: return null
        return try {
            val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
            Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
        } catch (_: Exception) { null }
    }

    /** Store the high-resolution source PNG received from the phone. */
    fun putSource(packageName: String, pngBytes: ByteArray) {
        if (pngBytes.isEmpty()) return
        sourceCache[packageName] = pngBytes
        try { File(cacheDir, "${packageName}_src.png").writeBytes(pngBytes) } catch (_: Exception) {}
    }

    /**
     * Synchronous O(1) lookup — returns a ready-to-render [ImageBitmap] or null.
     * Call ONLY after [prepareAll] has finished.
     */
    fun getPrepared(packageName: String): ImageBitmap? = prepared[packageName]

    /** Number of prepared icons ready for instant rendering. */
    fun preparedCount(): Int = prepared.size

    /**
     * Decode + resize all icons in parallel (bounded concurrency 4) on a background
     * thread. Call from a background coroutine BEFORE the grid becomes visible.
     *
     * @param apps list of all apps (reads source PNG from cache/disk for each)
     * @param sizePx target icon size in pixels (e.g. 64dp in px)
     * @return how many icons were successfully prepared
     */
    suspend fun prepareAll(apps: List<com.dilinkauto.protocol.AppInfo>, sizePx: Int): Int = withContext(Dispatchers.IO) {
        val sem = Semaphore(4)  // 8 A53 cores; cap parallelism to bound native heap churn
        val results = coroutineScope {
            apps.map { app -> async {
                sem.withPermit { prepareOne(app.packageName, sizePx) }
            } }.awaitAll()
        }
        val count = results.count { it }
        if (count > 0) preparedVersion++
        count
    }

    /** Decode + scale a single icon. Returns true on success. */
    private fun prepareOne(packageName: String, sizePx: Int): Boolean {
        if (prepared.containsKey(packageName)) return true
        val source = sourceCache[packageName] ?: loadSourceFromDisk(packageName) ?: return false
        return try {
            val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return false
            val resized = if (decoded.width != sizePx || decoded.height != sizePx) {
                val scaled = Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
                if (scaled !== decoded) decoded.recycle()  // createScaledBitmap may return a new bitmap; free the 192x192 intermediate
                scaled
            } else decoded
            prepared[packageName] = resized.asImageBitmap()
            true
        } catch (_: Exception) { false }
    }

    private fun loadSourceFromDisk(packageName: String): ByteArray? {
        val f = File(cacheDir, "${packageName}_src.png")
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            sourceCache[packageName] = bytes
            bytes
        } catch (_: Exception) { null }
    }

    /** Remove all cached data for a package (e.g. when app is uninstalled). */
    fun evict(packageName: String) {
        sourceCache.remove(packageName)
        prepared.remove(packageName)
        File(cacheDir, "${packageName}_src.png").delete()
    }

    /** Clear all in-memory and on-disk icon caches. Call on disconnect — the phone
     *  resends icons on the next APP_LIST, so retaining them across sessions just
     *  wastes ~5-9MB of heap on a 4GB device and grows the eMMC cache unbounded. */
    fun clear() {
        sourceCache.clear()
        prepared.clear()
        preparedVersion++
        try {
            cacheDir.listFiles { _, name -> name.endsWith("_src.png") }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
