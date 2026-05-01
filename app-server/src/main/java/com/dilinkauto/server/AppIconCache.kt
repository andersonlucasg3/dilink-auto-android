package com.dilinkauto.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Car-side icon cache — the single source of truth for app icons on the car.
 *
 * The phone sends high-resolution source PNGs (192×192 for app list, 96×96 for
 * notifications). The cache stores the source and produces resized versions at
 * the exact dimensions each UI area needs. Disk persistence survives restarts.
 *
 * Usage:
 *   AppIconCache.putSource(packageName, sourcePng)   // when APP_LIST arrives
 *   val bitmap = AppIconCache.get(packageName, 96)    // 64dp on 240dpi screen
 *   val bitmap = AppIconCache.get(packageName, 60)    // 40dp on 240dpi screen
 */
class AppIconCache(private val cacheDir: File) {

    private val sourceCache = ConcurrentHashMap<String, ByteArray>()
    private val resizedCache = ConcurrentHashMap<String, Bitmap>()

    init { cacheDir.mkdirs() }

    /** Store the high-resolution source PNG received from the phone. */
    fun putSource(packageName: String, pngBytes: ByteArray) {
        if (pngBytes.isEmpty()) return
        sourceCache[packageName] = pngBytes
        // Persist so icons survive app restarts without re-receiving from phone
        try {
            File(cacheDir, "${packageName}_src.png").writeBytes(pngBytes)
        } catch (_: Exception) {}
    }

    /**
     * Returns a [Bitmap] of the icon at [sizePx]×[sizePx].
     * Checks in-memory cache first, then disk, then resizes from the source.
     */
    fun get(packageName: String, sizePx: Int): Bitmap? {
        val key = "${packageName}_$sizePx"

        // In-memory hit
        resizedCache[key]?.let { return it }

        // Disk hit for this specific size
        val sizedFile = File(cacheDir, "${packageName}_${sizePx}.png")
        if (sizedFile.exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(sizedFile.absolutePath)
                if (bmp != null) {
                    resizedCache[key] = bmp
                    return bmp
                }
            } catch (_: Exception) {}
        }

        // Load source (in-memory or disk)
        val source = sourceCache[packageName] ?: loadSourceFromDisk(packageName)
            ?: return null

        // Decode source and resize to requested size
        return try {
            val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
            val resized = Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
            resizedCache[key] = resized

            // Persist resized version to disk
            try {
                ByteArrayOutputStream().use { stream ->
                    resized.compress(Bitmap.CompressFormat.PNG, 80, stream)
                    sizedFile.writeBytes(stream.toByteArray())
                }
            } catch (_: Exception) {}

            resized
        } catch (_: Exception) {
            null
        }
    }

    private fun loadSourceFromDisk(packageName: String): ByteArray? {
        val srcFile = File(cacheDir, "${packageName}_src.png")
        if (!srcFile.exists()) return null
        return try {
            val bytes = srcFile.readBytes()
            sourceCache[packageName] = bytes
            bytes
        } catch (_: Exception) { null }
    }

    /**
     * Synchronous in-memory lookup only — no disk I/O, no decode.
     * Returns null if the icon hasn't been pre-warmed (caller should fall back
     * to [get] which does full decode+resize).
     */
    fun peek(packageName: String, sizePx: Int): Bitmap? {
        return resizedCache["${packageName}_$sizePx"]
    }

    /** Remove all cached data for a package (e.g. when app is uninstalled). */
    fun evict(packageName: String) {
        sourceCache.remove(packageName)
        resizedCache.keys.removeAll { it.startsWith("${packageName}_") }
        File(cacheDir, "${packageName}_src.png").delete()
        cacheDir.listFiles()?.filter { it.name.startsWith("${packageName}_") }?.forEach { it.delete() }
    }
}
