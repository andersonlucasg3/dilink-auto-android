package com.dilinkauto.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Car-side icon cache with two layers:
 * 1. In-memory [ConcurrentHashMap] — zero disk I/O for cached icons during the app lifetime.
 * 2. Disk cache in [cacheDir] — icons survive reconnections and app restarts.
 *
 * Icons are keyed by packageName + iconHash so that app updates automatically
 * invalidate stale cached data.
 */
class CarIconCache(cacheRoot: File) {

    val cacheDir = cacheRoot.resolve("icons").also { it.mkdirs() }
    private val memCache = ConcurrentHashMap<String, Bitmap>()

    /** Returns cached bitmap if present in memory or on disk for the given hash, or null. */
    fun get(packageName: String, iconHash: String): Bitmap? {
        memCache[packageName]?.let { return it }

        if (iconHash.isEmpty()) return null
        val diskFile = File(cacheDir, "${packageName}_${iconHash}.png")
        if (!diskFile.exists()) return null

        return try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bmp = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
            if (bmp != null) memCache[packageName] = bmp
            bmp
        } catch (_: Exception) { null }
    }

    /** Decodes [iconPng] into a bitmap, caches it in memory and on disk. */
    fun put(packageName: String, iconHash: String, iconPng: ByteArray): Bitmap? {
        if (iconPng.isEmpty()) return null

        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 2
            }
            val bmp = BitmapFactory.decodeByteArray(iconPng, 0, iconPng.size, opts)
            if (bmp != null) {
                memCache[packageName] = bmp
                if (iconHash.isNotEmpty()) {
                    val diskFile = File(cacheDir, "${packageName}_${iconHash}.png")
                    try { diskFile.writeBytes(iconPng) } catch (_: Exception) {}
                }
            }
            bmp
        } catch (_: Exception) { null }
    }

    /** Returns the decoded bitmap, loading from cache or decoding [iconPng]. */
    fun getOrPut(packageName: String, iconHash: String, iconPng: ByteArray): Bitmap? {
        return get(packageName, iconHash) ?: put(packageName, iconHash, iconPng)
    }
}
