package com.dilinkauto.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
    }
}
