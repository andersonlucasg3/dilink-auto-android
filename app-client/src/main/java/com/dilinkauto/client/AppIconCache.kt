package com.dilinkauto.client

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for app icons — disk-persisted so icons survive
 * across launches and connections. In-memory cache avoids repeated disk reads.
 *
 * Both [NotificationService] (64x64) and [ConnectionService.sendAppList] (96x96)
 * draw from the same cache, eliminating duplicate icon memory.
 */
class AppIconCache(private val pm: PackageManager, cacheRoot: File) {

    private val cacheDir = cacheRoot.resolve("icons").also { it.mkdirs() }
    private val memCache = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()

    /** Returns the PNG bytes for [packageName] at [size]×[size], from cache or freshly loaded. */
    fun getOrLoad(packageName: String, size: Int): ByteArray {
        memCache[packageName]?.get(size)?.let { return it }

        return loadIconBytes(packageName, size).also { bytes ->
            memCache.getOrPut(packageName) { ConcurrentHashMap() }[size] = bytes
        }
    }

    private fun loadIconBytes(packageName: String, size: Int): ByteArray {
        val diskFile = File(cacheDir, "${packageName}_${size}.png")
        if (diskFile.exists()) return diskFile.readBytes()

        val bytes = encode(packageName, size)
        diskFile.writeBytes(bytes)
        return bytes
    }

    private fun encode(packageName: String, size: Int): ByteArray {
        return try {
            val icon = pm.getApplicationIcon(packageName)
            val bitmap = if (icon is BitmapDrawable) {
                Bitmap.createScaledBitmap(icon.bitmap, size, size, true)
            } else {
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                icon.setBounds(0, 0, size, size)
                icon.draw(canvas)
                bmp
            }
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            stream.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }
}
