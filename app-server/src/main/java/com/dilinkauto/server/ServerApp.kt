package com.dilinkauto.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import java.io.File

class ServerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        iconCache = AppIconCache(File(filesDir, "icons"))
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SERVICE = "dilinkauto_car_service"

        lateinit var iconCache: AppIconCache
            private set
    }
}
