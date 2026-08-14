package com.evsuite.tasker.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.evsuite.tasker.R

/** Notifications: the foreground service, and the "notify" action. */
object Notifier {

    const val CHANNEL_ID = "ev_tasker"
    const val FOREGROUND_NOTIFICATION_ID = 1
    private const val MESSAGE_NOTIFICATION_ID = 2

    fun ensureChannel(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                // LOW: running at startup must not make a sound or steal focus while
                // the driver is still settling in.
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /**
     * True when a "notify" action would actually reach the driver.
     *
     * Both switches matter and they are independent: the user can silence the whole app, or
     * only this channel. Either way the action succeeds silently today — the diagnostic is
     * what makes that visible before a rule relies on it.
     */
    fun canNotify(context: Context): Boolean {
        ensureChannel(context)
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun buildForegroundNotification(context: Context): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_running_title))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    /**
     * Puts a rule's message in front of the driver.
     *
     * On screen *and* in the shade, not one or the other. A notification on a head unit is a
     * small icon in a bar nobody looks at while driving, and the channel can be silenced
     * without the app being told at the moment it posts — which is how "the message action
     * does not work" happened. The toast is what the driver actually sees; the notification
     * is what is still there a minute later.
     *
     * Safe from any thread: the toast is posted to the main looper.
     */
    fun showRuleMessage(context: Context, message: String) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        }

        ensureChannel(appContext)
        val manager = ContextCompat.getSystemService(appContext, NotificationManager::class.java) ?: return
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(MESSAGE_NOTIFICATION_ID, notification) }
    }
}
