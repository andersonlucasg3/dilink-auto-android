package com.dilinkauto.protocol

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground-service plumbing shared by the phone ([com.dilinkauto.client.service.ConnectionService])
 * and the car ([com.dilinkauto.server.service.CarConnectionService]).
 *
 * Both services held a near-identical `acquireWakeLock` / `buildNotification` /
 * `updateNotification` trio, and the copies had already drifted in five ways
 * (docs/audit-srp-dry.md DRY-6): notification channel id, wake-lock tag,
 * notification id, a Stop action present only on the phone, and a `try/catch`
 * around `nm.notify` present only on the phone.
 *
 * This class owns the shared policy. Per-side differences (channel id, ids,
 * optional actions, title/text sources) stay as constructor parameters rather
 * than being guessed from a Service subclass.
 */
class ForegroundNotifier(
    context: Context,
    /** Must already be created via NotificationManager, or notify() is a no-op. */
    private val channelId: String,
    private val notificationId: Int,
    /** Wake-lock tag; must be unique per process for the logcat warning's sake. */
    private val wakeLockTag: String,
    private val title: String,
    /** Builds the body for a given status string resource. */
    private val text: (Context, Int) -> String,
    /**
     * Extra notification action, e.g. a Stop button. Null for none.
     * The phone passes one; the car does not.
     */
    private val extraAction: ((Context) -> NotificationCompat.Action)? = null,
    /** Auto-release after this long, so a leaked lock cannot pin the CPU forever. */
    private val wakeLockTimeoutMs: Long = 4 * 60 * 60 * 1000L
) {
    private val appContext = context.applicationContext

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Partial wake lock for the duration of a streaming session.
     *
     * The screen may be off (the mirror owns the display), but MediaCodec keeps
     * producing frames, so the CPU must stay awake. Idempotent in effect: a
     * second call re-acquires under the same tag.
     */
    fun acquireWakeLock() {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
            .apply { acquire(wakeLockTimeoutMs) }
    }

    /** Releases the wake lock if held. Safe to call when never acquired. */
    fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    fun buildNotification(messageRes: Int): Notification =
        NotificationCompat.Builder(appContext, channelId)
            .setContentTitle(title)
            .setContentText(text(appContext, messageRes))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .apply { extraAction?.let { addAction(it(appContext)) } }
            .build()

    /**
     * Posts or updates the foreground notification.
     *
     * The `try/catch` here used to exist only on the phone side; it is kept for
     * both because `nm.notify` can throw if the channel was never registered
     * (a real failure mode after a process restart racing channel creation),
     * and losing that notification must not take down the streaming service.
     */
    fun updateNotification(messageRes: Int) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, buildNotification(messageRes))
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Builds the phone-side Stop action; needs a service class for the intent target. */
        fun stopAction(
            iconRes: Int,
            label: String,
            serviceClass: Class<out Service>,
            action: String
        ): (Context) -> NotificationCompat.Action = { ctx ->
            val pi = PendingIntent.getService(
                ctx, 0,
                Intent(ctx, serviceClass).apply { this.action = action },
                PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(iconRes, label, pi).build()
        }
    }
}