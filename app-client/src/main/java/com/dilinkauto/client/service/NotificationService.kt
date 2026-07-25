package com.dilinkauto.client.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dilinkauto.client.ClientApp
import com.dilinkauto.protocol.DataMsg
import com.dilinkauto.protocol.NotificationData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

        private val _notificationsFlow = MutableStateFlow<List<NotificationData>>(emptyList())

        /** Active notifications, for same-process consumers (VD launcher panel). */
        val notificationsFlow: StateFlow<List<NotificationData>> = _notificationsFlow

        private fun addToFlow(notification: NotificationData) {
            _notificationsFlow.update { list ->
                list.filterNot {
                    it.packageName == notification.packageName && it.id == notification.id
                } + notification
            }
        }

        private fun removeFromFlow(packageName: String, id: Int) {
            _notificationsFlow.update { list ->
                list.filterNot { it.packageName == packageName && it.id == id }
            }
        }

        /** Dismiss a notification, falling back to the flow when no service is running. */
        fun clearNotification(packageName: String, id: Int) {
            instance?.cancelNotification(packageName, id)
            removeFromFlow(packageName, id)
        }

        /** Dismiss all notifications, falling back to the flow when no service is running. */
        fun clearAllNotifications() {
            instance?.cancelAll()
            _notificationsFlow.value = emptyList()
        }
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
            iconPng = ClientApp.loadIconPng(packageManager, sbn.packageName, 96)
        )

        // Same-process consumers (VD launcher panel)
        addToFlow(notification)

        // TCP forwarding to the car display
        val connection = ConnectionService.activeConnection ?: return
        try {
            connection.sendData(DataMsg.NOTIFICATION_POST, notification.encode())
        } catch (_: Exception) {
            // Connection may have dropped
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Same-process consumers (VD launcher panel)
        removeFromFlow(sbn.packageName, sbn.id)

        // TCP forwarding to the car display
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
}
