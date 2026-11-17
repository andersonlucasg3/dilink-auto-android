package com.dilinkauto.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.dilinkauto.protocol.AndroidPlatformHooks
import java.io.ByteArrayOutputStream

class ClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidPlatformHooks.install()
        createNotificationChannels()
        ShizukuManager.init(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "dilinkauto_service"

        /**
         * Byte budget for the encoded-PNG memo (audit A-M12).
         *
         * `sendAppList` re-runs on every reconnect, allowlist toggle and
         * package removal; without the memo each run re-rasterized and
         * re-encoded every allowlisted icon (≈30MB of transient native
         * allocation for 100 apps on a Snapdragon 439). Keyed by
         * package + size + change hash, the entries stay valid exactly as
         * long as `IconHashGate` would consider them sent — the car's
         * `AppIconCache` persists the same PNGs across sessions.
         */
        private const val ICON_CACHE_BYTES = 8 * 1024 * 1024

        private val iconCache = object : android.util.LruCache<String, ByteArray>(ICON_CACHE_BYTES) {
            override fun sizeOf(key: String, value: ByteArray) = value.size
        }

        /** Wire-path cache key: package + wire size + the app's change hash. */
        private fun iconCacheKey(packageName: String, size: Int, hash: String) = "$packageName|$size|$hash"

        /**
         * Loads an app icon at [size]×[size] and returns PNG bytes.
         *
         * The phone only transmits — it never decodes what it sends — but the
         * encode is memoized in a small [android.util.LruCache] (audit A-M12)
         * so a re-send of an unchanged icon is a byte-array lookup instead of
         * a fresh raster + PNG compress. Pass the app's change hash as
         * [cacheKey] to opt a call site into the memo; bypasses (null key)
         * still recycle correctly.
         */
        fun loadIconPng(pm: PackageManager, packageName: String, size: Int, cacheKey: String? = null): ByteArray {
            val key = cacheKey?.let { iconCacheKey(packageName, size, it) }
            key?.let { iconCache.get(it) }?.let { return it }
            val png = encodeIconPng(pm, packageName, size)
            if (key != null && png.isNotEmpty()) iconCache.put(key, png)
            return png
        }

        private fun encodeIconPng(pm: PackageManager, packageName: String, size: Int): ByteArray {
            return try {
                val icon = pm.getApplicationIcon(packageName)
                val bitmap = IconRenderer.toBitmap(icon, size, rescale = true)
                // rescale = true means "the transmitted PNG is exactly size
                // px", so this bitmap exists for the encode — recycle it as
                // soon as the bytes are out (audit A-M12). The identity check
                // matters: createScaledBitmap hands back the drawable's own
                // bitmap when the icon is already size×size, and recycling
                // that one would corrupt the PackageManager's drawable.
                val own = (icon as? android.graphics.drawable.BitmapDrawable)?.bitmap
                try {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                    stream.toByteArray()
                } finally {
                    if (bitmap !== own && !bitmap.isRecycled) bitmap.recycle()
                }
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
    }
}
