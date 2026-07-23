package com.mg4.tasker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.mg4.hardware.MG4Hardware
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.vehicle.RuleCycle
import kotlin.concurrent.thread

/**
 * Runs one evaluation cycle for the manual "Test now" button, then stops.
 *
 * Reuses exactly the ignition path (MG4Hardware direct reads/writes) so a test proves the
 * real thing. Ensures the vehicle layer is initialised in case the persistent
 * [TaskerVehicleService] has not started yet.
 */
class TaskerRunService : Service() {

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TaskerRunService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifier.FOREGROUND_NOTIFICATION_ID, Notifier.buildForegroundNotification(this))
        thread(name = "mg4-tasker-manual") {
            MG4Hardware.init(applicationContext)   // idempotent
            RuleCycle.run(this, "MANUAL")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
