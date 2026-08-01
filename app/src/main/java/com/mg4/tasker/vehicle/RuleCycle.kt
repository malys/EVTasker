package com.mg4.tasker.vehicle

import android.content.Context
import com.mg4.hardware.AppLogger
import com.mg4.tasker.engine.RuleEngine
import com.mg4.tasker.store.HistoryStore
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.util.BtDevices
import com.mg4.tasker.util.CarLocation

/**
 * Bluetooth devices currently connected to the vehicle.
 *
 * Two sources, unioned: the ACL_CONNECTED/DISCONNECTED transitions the vehicle service
 * observes while it runs, and a direct query of the stack ([BtDevices.connected]). Neither
 * is sufficient alone — the broadcasts miss every device already connected when the service
 * started, and the query needs a hidden API that a non-platform build cannot reach.
 */
object BtTracker {
    private val macs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun add(mac: String) { macs.add(mac) }
    fun remove(mac: String) { macs.remove(mac) }
    fun observed(): Set<String> = macs.toSet()

    fun snapshot(context: Context): Set<String> = observed() + BtDevices.connected(context)
}

/**
 * Delivers the outcome of a cycle to whatever screen is watching.
 *
 * The manual test runs in a service, so the Rules screen has no return value to show. It
 * used to say "test running" and nothing else, which left the user to open the History tab
 * to find out what happened — for a button whose whole purpose is to answer that question.
 *
 * A listener rather than a broadcast: same process, one observer at a time, and nothing to
 * unregister at the wrong moment beyond clearing the field.
 */
object CycleReporter {

    @Volatile
    var listener: ((EngineRun) -> Unit)? = null

    fun publish(run: EngineRun) {
        listener?.invoke(run)
    }
}

/**
 * One rule-evaluation pass, shared by the ignition trigger and the manual "Test now".
 *
 * Reads the vehicle directly ([VehicleReader]) and applies actions directly
 * ([DirectExecutor]); MG4Control is used only if present, only for the profile action.
 */
object RuleCycle {

    private const val TAG = "MG4Tasker.Cycle"

    fun run(context: Context, trigger: String) {
        val rules = RuleStore(context).getAll()
        if (rules.isEmpty()) { AppLogger.i(TAG, "no rules: $trigger skipped"); return }

        val profileBridge = ProfileBridge(context).takeIf { it.connect() }
        try {
            val snapshot = VehicleReader.read(
                btMacs = BtTracker.snapshot(context),
                btAvailable = BtDevices.isAvailable(context),
                fix = CarLocation.lastKnown(context)
            )
            val result = RuleEngine(DirectExecutor(context, profileBridge))
                .run(rules, snapshot, trigger, System.currentTimeMillis())
            HistoryStore(context).append(result)
            AppLogger.i(TAG, "cycle $trigger — ${result.ruleRuns.size} rules evaluated")
            CycleReporter.publish(result)
        } finally {
            profileBridge?.disconnect()
        }
    }
}
