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

    /** A manual test ignores the trigger, but addresses only the rule selected by the user. */
    const val MANUAL = "MANUAL"
    const val PHYSICAL_BUTTON = "PHYSICAL_BUTTON"

    fun run(
        context: Context,
        trigger: String,
        eventReadings: Map<String, Any> = emptyMap(),
        ruleId: String? = null
    ) {
        val all = RuleStore(context).getAll()
        // Rules wired to a different event are not "skipped", they were never addressed: the
        // history would otherwise fill with a line per rule per ignition saying nothing.
        val rules = when (trigger) {
            MANUAL -> all.filter { it.id == ruleId }
            PHYSICAL_BUTTON -> all.filter { it.hasPhysicalButtonCondition }
            else -> all.filter { !it.hasPhysicalButtonCondition && it.firesOn.name == trigger }
        }
        if (rules.isEmpty()) {
            AppLogger.i(TAG, "no rules for $trigger (${all.size} total) — skipped")
            return
        }

        val startedAt = System.currentTimeMillis()
        val profileBridge = ProfileBridge(context).takeIf { it.connect() }
        try {
            val snapshotStartedAt = System.currentTimeMillis()
            val baseSnapshot = VehicleReader.read(
                btMacs = BtTracker.snapshot(context),
                btAvailable = BtDevices.isAvailable(context),
                fix = CarLocation.lastKnown(context)
            )
            val snapshot = baseSnapshot.copy(readings = baseSnapshot.readings + eventReadings)
            val snapshotMs = System.currentTimeMillis() - snapshotStartedAt
            val result = RuleEngine(DirectExecutor(context, profileBridge))
                .run(rules, snapshot, trigger, System.currentTimeMillis())
            HistoryStore(context).append(result)
            // A gate refusal is "not now", not "no": keep it for the next standstill. Done
            // here rather than in the engine, which stays Android-free and stateless.
            DeferredWrites.offer(context, result, rules)
            // Timed, because "the car must stay responsive" is a claim that needs a number.
            // The line lands in the log and therefore in every exported and shared report.
            AppLogger.i(
                TAG,
                "cycle $trigger — ${result.ruleRuns.size} rules evaluated in " +
                    "${System.currentTimeMillis() - startedAt} ms " +
                    "(snapshot ${snapshotMs} ms)"
            )
            CycleReporter.publish(result)
        } finally {
            profileBridge?.disconnect()
        }
    }
}
