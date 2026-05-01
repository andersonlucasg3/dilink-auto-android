package com.dilinkauto.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import com.dilinkauto.client.service.UpdateManager
import java.io.ByteArrayOutputStream

class ClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        UpdateManager.init(this)
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

        val updateChannel = NotificationChannel(
            CHANNEL_UPDATE,
            getString(R.string.notification_channel_update),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(updateChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "dilinkauto_service"
        const val CHANNEL_UPDATE = "dilinkauto_update"

        /** Loads an app icon at [size]×[size] and returns PNG bytes. No caching — phone only transmits. */
        fun loadIconPng(pm: PackageManager, packageName: String, size: Int): ByteArray {
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
}
