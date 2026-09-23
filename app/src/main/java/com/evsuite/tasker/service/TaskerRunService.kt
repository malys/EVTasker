package com.evsuite.tasker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.PhysicalButtonEventDecoder
import com.evsuite.hardware.catalog.SnapshotKeys
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
        private const val EXTRA_EVENT = "event"

        /** [buttonEvent]: the press a button rule is tested on, as [PhysicalButtonEventDecoder.Event.value]. */
        fun start(context: Context, ruleId: String, buttonEvent: String? = null) {
            context.startForegroundService(
                Intent(context, TaskerRunService::class.java)
                    .putExtra(EXTRA_RULE_ID, ruleId)
                    .putExtra(EXTRA_EVENT, buttonEvent)
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
            val event = intent?.getStringExtra(EXTRA_EVENT)
            RuleCycle.run(
                this,
                "MANUAL",
                eventReadings = event?.let { mapOf(SnapshotKeys.KEY_PHYSICAL_BUTTON_EVENT to it) }.orEmpty(),
                ruleId = intent?.getStringExtra(EXTRA_RULE_ID)
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
