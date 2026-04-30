package com.dilinkauto.client.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.NotificationData
import java.io.ByteArrayOutputStream

/**
 * Listens for phone notifications and forwards them to the car display.
 * Requires the user to grant notification access in system settings.
 *
 * On HyperOS: This service needs Autostart enabled to survive background killing.
 */
class NotificationService : NotificationListenerService() {

    companion object {
        var instance: NotificationService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val connection = ConnectionService.activeConnection ?: return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(sbn.packageName, 0)
        ).toString()

        // Skip our own notifications
        if (sbn.packageName == packageName) return

        val progress = extras.getInt("android.progress", 0)
        val progressMax = extras.getInt("android.progressMax", 0)
        val progressIndeterminate = extras.getBoolean("android.progressIndeterminate", false)

        val notification = NotificationData(
            id = sbn.id,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            progress = progress,
            progressMax = progressMax,
            progressIndeterminate = progressIndeterminate,
            iconPng = loadAppIconPng(sbn.packageName)
        )

        try {
            connection.sendData(DataMsg.NOTIFICATION_POST, notification.encode())
        } catch (_: Exception) {
            // Connection may have dropped
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val connection = ConnectionService.activeConnection ?: return

        val notification = NotificationData(
            id = sbn.id,
            packageName = sbn.packageName,
            appName = "",
            title = "",
            text = "",
            timestamp = System.currentTimeMillis()
        )

        try {
            connection.sendData(DataMsg.NOTIFICATION_REMOVE, notification.encode())
        } catch (_: Exception) {}
    }

    fun cancelNotification(packageName: String, id: Int) {
        val sbn = activeNotifications.find { it.packageName == packageName && it.id == id }
        if (sbn != null) cancelNotification(sbn.key)
    }

    @Suppress("NOTHING_TO_INLINE")
    fun cancelAll() {
        super.cancelAllNotifications()
    }

    private fun loadAppIconPng(packageName: String): ByteArray {
        return try {
            val icon: Drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (icon is BitmapDrawable) {
                Bitmap.createScaledBitmap(icon.bitmap, 64, 64, true)
            } else {
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                icon.setBounds(0, 0, 64, 64)
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
