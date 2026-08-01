package com.mg4.tasker.vehicle

import android.content.Context
import com.mg4.hardware.AppLogger
import com.mg4.tasker.engine.RuleEngine
import com.mg4.tasker.store.HistoryStore
import com.mg4.tasker.store.RuleStore
import com.mg4.tasker.util.BtDevices

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
                btAvailable = BtDevices.isAvailable(context)
            )
            val result = RuleEngine(DirectExecutor(context, profileBridge))
                .run(rules, snapshot, trigger, System.currentTimeMillis())
            HistoryStore(context).append(result)
            AppLogger.i(TAG, "cycle $trigger — ${result.ruleRuns.size} rules evaluated")
        } finally {
            profileBridge?.disconnect()
        }
    }
}
