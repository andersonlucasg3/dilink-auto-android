package com.dilinkauto.server

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.dilinkauto.protocol.AndroidPlatformHooks
import java.io.File

class ServerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidPlatformHooks.install()
        createNotificationChannels()
        iconCache = AppIconCache(File(filesDir, "icons"))

        CarCrashHandler.install(this)
        Log.i("ServerApp", CarCrashReport.deviceInfo(this))
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

        /**
         * Process-scoped icon cache, created in [onCreate].
         *
         * Audit R3-SRP-20 flagged this service locator; the verdict after
         * review is **retain, deliberately**. The two consumers are an Android
         * `Service` (instantiated by the framework) and a `@Composable` (which
         * has a `Context`, not a constructor) — so "constructor injection"
         * would require a DI framework for a single process-lifetime object.
         * The `private set` keeps the locator write-once, which is the part of
         * the finding that was actually actionable.
         */
        lateinit var iconCache: AppIconCache
            private set
    }
}
