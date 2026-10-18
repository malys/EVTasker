package com.evsuite.tasker.vehicle

import android.content.Context
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.engine.RuleEngine
import com.evsuite.tasker.store.HistoryStore
import com.evsuite.tasker.store.RuleStore
import com.evsuite.tasker.model.EngineRun
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.RuleTrigger
import com.evsuite.tasker.util.BtDevices
import com.evsuite.tasker.util.CarLocation
import com.evsuite.tasker.util.WallClock
import android.os.SystemClock
import kotlin.concurrent.thread

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

    /**
     * What a rule may have to wait for before it can be evaluated, and for how long at most.
     *
     * Past its wait a rule runs anyway, and the history shows it not evaluable (`CLOCK_UNSET`,
     * no fix) or on the branch the inputs pointed to, rather than leaving no trace. Only the
     * evaluation waits: the actions still go through the standstill gate when they run, and a
     * refusal then is final like any other.
     */
    internal enum class Input(val waitMs: Long) {
        /** The RTC default, until the network or GPS corrects it. Waited for at any time. */
        CLOCK(5 * 60_000L),

        /** A slow boot links the phone after the ignition cycle has run (2026-10-17). */
        BLUETOOTH(20_000L),

        /** A cold receiver: the gate button found no fix for 20 s after boot (2026-10-16). */
        LOCATION(60_000L),
    }

    private const val INPUT_POLL_MS = 2_000L

    /**
     * Bluetooth and position are waited for only this long after the head unit booted. Later,
     * no phone means no phone and no fix means no fix, and a rule must not sit on them.
     */
    private const val BOOT_WINDOW_MS = 3 * 60_000L

    private val BT_CONDITIONS = setOf(
        ConditionType.BT_DEVICE_CONNECTED, ConditionType.ANY_BT_CONNECTED,
        ConditionType.BT_DEVICE_ONBOARD, ConditionType.BT_DEVICE_HANDSFREE,
    )

    /** Held cycles, so a button pressed four times while the GPS warms up fires once. */
    private val waiting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // A P cycle can still be closing glass when ignition-off arrives. Keep whole cycles,
    // not just individual writes, atomic against every other trigger.
    @Synchronized
    fun run(
        context: Context,
        trigger: String,
        eventReadings: Map<String, Any> = emptyMap(),
        ruleId: String? = null,
        /** The rules held back by [waitForInputs], run once it is over. */
        waited: Set<String>? = null
    ) {
        val all = RuleStore(context).getAll()
        // Rules wired to a different event are not "skipped", they were never addressed: the
        // history would otherwise fill with a line per rule per ignition saying nothing.
        var rules = when (trigger) {
            MANUAL -> all.filter { it.id == ruleId }
            PHYSICAL_BUTTON -> all.filter { it.hasPhysicalButtonCondition }
            // A position event belongs to the one rule whose place it is: its neighbours are
            // not arriving anywhere, so [ruleId] narrows a trigger cycle too when given.
            else -> all.filter {
                !it.hasPhysicalButtonCondition && it.firesOn.name == trigger &&
                    (ruleId == null || it.id == ruleId)
            }
        }
        if (waited != null) rules = rules.filter { it.id in waited }
        if (rules.isEmpty()) {
            AppLogger.i(TAG, "no rules for $trigger (${all.size} total) — skipped")
            return
        }
        // A manual test answers now, inputs or not: the user is looking at the result.
        if (trigger != MANUAL && waited == null) {
            val app = context.applicationContext
            val held = rules.associateWith { rule -> inputsRead(rule).filter { missing(app, it, trigger) } }
                .filterValues { it.isNotEmpty() }
            if (held.isNotEmpty()) {
                waitForInputs(app, trigger, eventReadings, held.keys.map { it.id }.toSet(),
                    held.values.flatten().toSet())
            }
            rules = rules - held.keys
            if (rules.isEmpty()) return
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

    /** What any case of [rule], "else if" ones included, reads that can be late at boot. */
    internal fun inputsRead(rule: Rule): Set<Input> =
        rule.branches.flatMap { it.conditions }.mapNotNullTo(mutableSetOf()) {
            when (it.type) {
                in WallClock.CONDITIONS -> Input.CLOCK
                in BT_CONDITIONS -> Input.BLUETOOTH
                ConditionType.LOCATION_WITHIN -> Input.LOCATION
                else -> null
            }
        }

    private fun missing(context: Context, input: Input, trigger: String): Boolean {
        val booting = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
        return when (input) {
            Input.CLOCK -> !WallClock.isSet()
            // At ignition only: a phone missing at P or at a button press is simply not there.
            Input.BLUETOOTH -> booting && trigger == RuleTrigger.IGNITION_ON.name &&
                BtTracker.snapshot(context).isEmpty()
            Input.LOCATION -> booting && CarLocation.lastKnown(context) == null
        }
    }

    /**
     * Runs [ruleIds] again once [inputs] are there, or once each has been waited for long enough.
     *
     * At boot the clock reads 2019-01-01 until the network or GPS corrects it, the phone links
     * seconds after the ignition cycle and the GPS has no fix yet. Rules that read one of those
     * wait for it; the others ran already and are not held up. Abandoned if the ignition changes
     * meanwhile: a start-of-drive rule must not fire after the drive is over. Waits on elapsed
     * time, because the wall clock is precisely what jumps.
     */
    private fun waitForInputs(
        context: Context,
        trigger: String,
        eventReadings: Map<String, Any>,
        ruleIds: Set<String>,
        inputs: Set<Input>,
    ) {
        val key = "$trigger ${ruleIds.sorted()} $eventReadings"
        if (!waiting.add(key)) {
            AppLogger.i(TAG, "$trigger already waiting for ${inputs.joinToString()} — repeat dropped")
            return
        }
        val ignition = EVHardware.getCurrentIgnitionState()
        AppLogger.i(TAG, "waiting for ${inputs.joinToString()} — ${ruleIds.size} $trigger rule(s) held")
        thread(name = "mg4-tasker-input-wait") {
            try {
                val start = SystemClock.elapsedRealtime()
                fun late() = inputs.filter {
                    missing(context, it, trigger) && SystemClock.elapsedRealtime() - start < it.waitMs
                }
                while (late().isNotEmpty()) Thread.sleep(INPUT_POLL_MS)
                val waitedS = (SystemClock.elapsedRealtime() - start) / 1000
                if (EVHardware.getCurrentIgnitionState() != ignition) {
                    AppLogger.i(TAG, "ignition changed during the ${waitedS} s wait — $trigger rules dropped")
                    return@thread
                }
                val still = inputs.filter { missing(context, it, trigger) }
                AppLogger.i(TAG, "after ${waitedS} s, ${if (still.isEmpty()) "inputs ready" else "still missing ${still.joinToString()}"} — running held $trigger rules")
                run(context, trigger, eventReadings, waited = ruleIds)
            } finally {
                waiting.remove(key)
            }
        }
    }
}
