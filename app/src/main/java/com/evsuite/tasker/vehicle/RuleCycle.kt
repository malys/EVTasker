package com.evsuite.tasker.vehicle

import android.content.Context
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.tasker.engine.RuleEngine
import com.evsuite.tasker.store.HistoryStore
import com.evsuite.tasker.store.RuleStore
import com.evsuite.tasker.model.EngineRun
import com.evsuite.tasker.util.BtDevices
import com.evsuite.tasker.util.CarLocation

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
 * Which of the connected devices are actually **in** the car.
 *
 * Bluetooth reaches through a wall: a phone left in the house connects to a car parked in
 * front of it, and every "when I arrive" rule fires on the driveway. Nothing in the stack
 * measures distance, so the car measures it instead — a device still connected after the
 * car has driven [MOVING_KMH] went with it, and one that stayed behind has dropped off by
 * then. Sampled while the car moves ([sample]), intersected so that a device leaving the
 * link at any point during the drive leaves the set for good.
 *
 * That answer is the accurate one, and it is also the one that does not exist yet at
 * ignition — which is when most rules run. Reporting UNAVAILABLE there made the condition
 * look broken: on a car whose speed is unreadable it never resolved at all, and on every
 * other one it stayed unanswerable for the first minute of every drive.
 *
 * So before the car has moved, [onboard] falls back to the device the head unit has itself
 * made active ([BtDevices.activeHandsFree]) — the phone it routes calls and media through.
 * That is the closest thing the platform offers to "the strongest link", and unlike a plain
 * connection it is a choice the car made among the phones in range. Null is still returned
 * when even that is unknowable, so "unreadable ≠ absent" continues to hold.
 */
object BtOnboard {

    /** Above walking pace and above GPS-less speed noise: the car has left the driveway. */
    private const val MOVING_KMH = 10f

    @Volatile
    private var driven: Set<String>? = null

    fun sample(context: Context) {
        val speed = EVHardware.getVehicleSpeedKmh() ?: return
        if (speed < MOVING_KMH) return
        val connected = BtTracker.snapshot(context)
        driven = driven?.intersect(connected) ?: connected
    }

    /**
     * The on-board set, best source first. Null only when nothing can be said at all.
     *
     * The active device is intersected with what is actually connected: the stack keeps
     * naming the last active phone for a while after it has gone, and a MAC that is no
     * longer linked is not on board.
     */
    fun onboard(context: Context): Set<String>? {
        driven?.let { return it }
        if (!BtDevices.isAvailable(context)) return null
        val active = BtDevices.activeHandsFree(context) ?: return null
        return active.intersect(BtTracker.snapshot(context))
    }

    /** The drive is over; the next one has its own passengers. */
    fun reset() { driven = null }
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
 * One rule-evaluation pass, shared by vehicle triggers and the manual "Test now".
 *
 * Reads the vehicle directly ([VehicleReader]) and applies actions directly
 * ([DirectExecutor]); EVProfile is used only if present, only for the profile action.
 */
object RuleCycle {

    private const val TAG = "EVTasker.Cycle"

    /** A manual test ignores the trigger, but addresses only the rule selected by the user. */
    const val MANUAL = "MANUAL"
    const val PHYSICAL_BUTTON = "PHYSICAL_BUTTON"

    // A P cycle can still be closing glass when ignition-off arrives. Keep whole cycles,
    // not just individual writes, atomic against every other trigger.
    @Synchronized
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
            // Only what these rules read. A pass with no weather condition must not pay for
            // a weather query before running the ignition actions it was started for.
            val wanted = rules.flatMap { rule -> rule.branches }
                .flatMap { branch -> branch.conditions }
                .mapNotNull { it.type.snapshotKey }
                .toSet()
            val baseSnapshot = VehicleReader.read(
                context = context,
                wanted = wanted,
                btMacs = BtTracker.snapshot(context),
                btAvailable = BtDevices.isAvailable(context),
                btOnboardMacs = BtOnboard.onboard(context),
                btHandsFreeMacs = BtDevices.activeHandsFree(context),
                fix = CarLocation.lastKnown(context)
            )
            val snapshot = baseSnapshot.copy(readings = baseSnapshot.readings + eventReadings)
            val snapshotMs = System.currentTimeMillis() - snapshotStartedAt
            val result = RuleEngine(DirectExecutor(context, profileBridge))
                .run(rules, snapshot, trigger, System.currentTimeMillis())
            HistoryStore(context).append(result)
            // Gate refusals are final. Never queue a write for a later traffic stop.
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
