package com.mg4.tasker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mg4.tasker.bridge.BridgeActionExecutor
import com.mg4.tasker.bridge.BridgeClient
import com.mg4.hardware.AppLogger
import com.mg4.tasker.engine.RuleEngine
import com.mg4.tasker.store.HistoryStore
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.util.Notifier
import kotlin.concurrent.thread

/**
 * Runs one evaluation cycle, then stops.
 *
 * A foreground service rather than work inside the receiver: binding MG4Control and then
 * applying a profile can exceed the few seconds a BroadcastReceiver is granted, and the
 * system would kill the process in the middle of a vehicle write sequence.
 */
class TaskerRunService : Service() {

    companion object {
        private const val TAG = "MG4Tasker.Run"
        const val EXTRA_TRIGGER = "trigger"
        const val TRIGGER_IGNITION = "IGNITION_ON"
        const val TRIGGER_MANUAL   = "MANUAL"

        fun start(context: Context, trigger: String) {
            val intent = Intent(context, TaskerRunService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger)
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_IGNITION

        // Dedicated thread: connect() blocks for up to 15 s waiting for MG4Control, and
        // the HVAC writes on the other side poll vehicle state for several seconds.
        thread(name = "mg4-tasker-run") {
            runCycle(trigger)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun runCycle(trigger: String) {
        val rules = RuleStore(this).getAll()
        if (rules.isEmpty()) {
            AppLogger.i(TAG, "no rules: cycle skipped")
            return
        }

        val client = BridgeClient(this)
        try {
            val connected = client.connect()
            if (!connected) {
                AppLogger.w(TAG, "MG4Control unreachable — no rule will be applied")
            }

            val snapshot = client.readSnapshot()
            val engine = RuleEngine(BridgeActionExecutor(this, client))
            val result = engine.run(rules, snapshot, trigger, System.currentTimeMillis())

            HistoryStore(this).append(result)
            AppLogger.i(TAG, "cycle $trigger finished — ${result.ruleRuns.size} rules evaluated")
        } catch (e: Exception) {
            AppLogger.e(TAG, "cycle $trigger aborted: ${e.message}")
        } finally {
            client.disconnect()
        }
    }

    private fun startForegroundCompat() {
        val notification = Notifier.buildForegroundNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifier.FOREGROUND_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifier.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }
}
