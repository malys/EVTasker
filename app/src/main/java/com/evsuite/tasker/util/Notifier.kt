package com.evsuite.tasker.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ServiceCompat
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
     * Puts [service] in the foreground with the service types it is actually allowed to hold.
     *
     * The plain two-argument `startForeground` means "every type this service declares in the
     * manifest", and since API 34 each of those types is checked against the permissions the
     * app holds *at that moment*. Both services declare `location` as well as
     * `connectedDevice`, so on a car where position was never granted — a fresh install, or a
     * driver who said no — the service died in `onCreate` with a SecurityException, taking
     * every rule with it, position-based or not.
     *
     * The location type is therefore claimed only while the permission backing it is held.
     * Called again from `onStartCommand`, so a grant that arrives later upgrades the running
     * service instead of waiting for the next boot.
     */
    fun startInForeground(service: Service) {
        ServiceCompat.startForeground(
            service,
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(service),
            foregroundServiceTypes(service)
        )
    }

    /**
     * The subset of the declared service types this app currently holds the permissions for.
     *
     * Both declared types are permission-backed and both permissions are runtime ones:
     * `connectedDevice` rests on BLUETOOTH_CONNECT, `location` on either position grade. A
     * type claimed without its permission fails the whole start, so each is claimed only when
     * it is held — and a service that holds neither still runs, still applies profiles and
     * still writes the car, because the vehicle permissions are signature-level and are not
     * part of this question.
     */
    fun foregroundServiceTypes(context: Context): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        if (hasBluetoothConnect(context)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (CarLocation.hasPermission(context)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    /** Below API 31 the permission does not exist and the type needs nothing to be claimed. */
    private fun hasBluetoothConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

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
