package com.dilinkauto.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Environment
import com.dilinkauto.client.service.UpdateManager
import java.io.File

class ClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        UpdateManager.init(this)
        ShizukuManager.init(this)
        iconCache = AppIconCache(
            packageManager,
            File(Environment.getExternalStorageDirectory(), "DiLinkAuto")
        )
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

        lateinit var iconCache: AppIconCache
            private set
    }
}
