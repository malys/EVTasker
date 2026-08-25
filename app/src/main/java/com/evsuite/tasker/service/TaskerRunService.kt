package com.evsuite.tasker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.evsuite.hardware.EVHardware
import com.evsuite.tasker.store.AppState
import com.evsuite.tasker.util.Notifier
import com.evsuite.tasker.vehicle.RuleCycle
import com.evsuite.tasker.vehicle.VendorServices
import kotlin.concurrent.thread

/**
 * Runs one evaluation cycle for the manual "Test now" button, then stops.
 *
 * Reuses exactly the ignition path (EVHardware direct reads/writes) so a test proves the
 * real thing. Ensures the vehicle layer is initialised in case the persistent
 * [TaskerVehicleService] has not started yet.
 */
class TaskerRunService : Service() {

    companion object {
        private const val EXTRA_RULE_ID = "ruleId"

        fun start(context: Context, ruleId: String) {
            context.startForegroundService(
                Intent(context, TaskerRunService::class.java).putExtra(EXTRA_RULE_ID, ruleId)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.startInForeground(this)
        thread(name = "mg4-tasker-manual") {
            EVHardware.init(applicationContext)      // idempotent
            EVHardware.initAudio(applicationContext) // idempotent; binds the vendor audio helper
            VendorServices.connect(applicationContext)
            RuleCycle.run(this, "MANUAL", ruleId = intent?.getStringExtra(EXTRA_RULE_ID))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
