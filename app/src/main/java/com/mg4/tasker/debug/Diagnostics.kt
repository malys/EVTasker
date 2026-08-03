package com.mg4.tasker.debug

import com.mg4.hardware.FirmwareGen
import com.mg4.hardware.FirmwareSupport
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.engine.ConditionEvaluator
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.ConditionOutcome
import com.mg4.tasker.model.Snapshot

/**
 * Turns the execution context into a per-catalogue-entry verdict.
 *
 * The contract of this screen is strong and deliberate: **OK means the rule engine will not
 * refuse this entry right now.** So nothing here re-implements the decision — a condition is
 * declared readable only if [ConditionEvaluator], the very object the engine calls, says so
 * on the same snapshot, and an action is declared runnable only after every check
 * `DirectExecutor` performs before it writes (firmware matrix, standstill gate, MG4Control
 * bind, TTS engine, notification channel) has passed.
 *
 * What it cannot do is perform the write itself: applying a drive mode to see whether it
 * sticks would change the car under the driver. For vehicle writes, "OK" therefore means
 * "everything the app checks before writing passes"; the write itself is the only step left,
 * and it is the one the history reports afterwards.
 *
 * Pure by design — no Android, no hardware. The context is collected once by
 * [DiagnosticProbe] and handed in as [Capabilities], which is what makes every verdict here
 * testable on the JVM with no vehicle.
 */
object Diagnostics {

    /**
     * A MAC no device carries, used to make the Bluetooth probe *configured*.
     *
     * [ConditionEvaluator] answers UNAVAILABLE for a blank MAC — that is a rule the user has
     * not finished writing, not a signal the car cannot report. Probing with a real-shaped
     * address separates the two.
     */
    private const val PROBE_MAC = "00:00:00:00:00:00"

    /** All seven days, so [ConditionType.DAY_OF_WEEK] probes availability, not configuration. */
    private val ALL_DAYS = (1..7).toList()

    /**
     * Null Island, for the same reason as [PROBE_MAC]: a parseable point makes the location
     * probe report whether the car has a fix, instead of reporting an unfinished rule. No
     * car will be within the radius of it, which does not matter — only MATCH versus
     * UNAVAILABLE is read back.
     */
    private const val PROBE_POINT = "0.0,0.0"

    enum class Status { OK, BLOCKED }

    /** Why an entry is blocked, or [NONE] when it is not. */
    enum class Reason {
        NONE,
        /** The vehicle layer answered, but this signal is not in the snapshot. */
        NOT_READABLE,
        /** Bluetooth radio off or unreadable: nothing can be said about connected devices. */
        BLUETOOTH_OFF,
        /** MG4Hardware is not ready at all: every vehicle signal and write is out. */
        LAYER_NOT_READY,
        /** Standstill gate: the car is moving. */
        GATE_MOVING,
        /** Standstill gate: speed unreadable, so the gate fails closed. */
        GATE_UNKNOWN_SPEED,
        /** This firmware generation is not in the entry's `@SupportedOn` set. */
        UNSUPPORTED_FIRMWARE,
        NO_MG4CONTROL,
        MG4CONTROL_UNREACHABLE,
        NO_TTS_ENGINE,
        NOTIFICATIONS_OFF,
        /** The SAIC vendor service behind this entry is not bound on this car. */
        NO_VENDOR_SERVICE,
        /** Nothing on the head unit answers a `geo:` intent. */
        NO_NAVIGATION_APP,
        /** No location permission, or no fix recent enough to place the car. */
        NO_LOCATION,
    }

    /**
     * One catalogue entry's verdict.
     *
     * [name] is the enum constant, not a label: the report is read on a laptop by whoever is
     * debugging, and a translated label would not match the code they are looking at.
     */
    data class Entry(
        val name: String,
        val status: Status,
        val reason: Reason,
        /** Value read for a condition — null for an action, or when unreadable. */
        val value: Any? = null,
        /**
         * The rule editor hides this entry on the detected firmware. Kept separate from
         * [status]: a hidden entry can still reach the engine through an imported rule.
         */
        val hidden: Boolean = false
    )

    /** Everything about the execution context that cannot be derived from the snapshot. */
    data class Capabilities(
        val vehicleLayerReady: Boolean,
        /** [BridgeContract] verdict the standstill gate would return for a write now. */
        val gateVerdict: String,
        val mg4ControlInstalled: Boolean,
        /** MG4Control's bridge actually bound — installed is not the same as reachable. */
        val profileBridgeReachable: Boolean,
        val notificationsEnabled: Boolean,
        val ttsEngineAvailable: Boolean,
        /**
         * SAIC vendor services, each bound or not independently. They are what the climate,
         * charging, radio and call entries run on, and the firmware matrix cannot answer for
         * them: it says which generation *should* have the service, the bind says whether
         * this car actually does.
         */
        val climateService: Boolean = false,
        val chargingService: Boolean = false,
        val radioService: Boolean = false,
        val phoneService: Boolean = false,
        /** Something on the head unit answers a `geo:` intent. */
        val navigationApp: Boolean = false,
    )

    /**
     * One verdict per condition, catalogue order.
     *
     * A condition that reads is OK even when the firmware matrix does not list this
     * generation: the read is ground truth, the matrix is a static table, and the engine
     * follows the read. The disagreement is surfaced through [Entry.hidden] instead.
     */
    fun conditions(snapshot: Snapshot, gen: FirmwareGen?): List<Entry> =
        ConditionType.entries.map { type ->
            val outcome = ConditionEvaluator.evaluate(probeOf(type), snapshot)
            val hidden = !FirmwareSupport.isSupported(type, gen)
            if (outcome == ConditionOutcome.UNAVAILABLE) {
                Entry(
                    name = type.name,
                    status = Status.BLOCKED,
                    reason = unavailableReason(type, snapshot),
                    hidden = hidden
                )
            } else {
                Entry(type.name, Status.OK, Reason.NONE, rawValue(type, snapshot), hidden)
            }
        }

    /**
     * One verdict per action, catalogue order.
     *
     * A firmware the matrix excludes blocks the action outright — unlike a condition there is
     * no read to fall back on, and the only way to find out would be to write to the car.
     * Failing closed is the same choice the standstill gate makes.
     */
    fun actions(caps: Capabilities, gen: FirmwareGen?): List<Entry> =
        ActionType.entries.map { type ->
            val hidden = !FirmwareSupport.isSupported(type, gen)
            val reason = when {
                hidden -> Reason.UNSUPPORTED_FIRMWARE
                else -> blockingReason(type, caps)
            }
            Entry(
                name = type.name,
                status = if (reason == Reason.NONE) Status.OK else Status.BLOCKED,
                reason = reason,
                hidden = hidden
            )
        }

    /**
     * Why a condition could not be evaluated.
     *
     * The Bluetooth entries are context, not vehicle signals: naming the vehicle layer for
     * them would point whoever reads the report at the wrong subsystem.
     */
    private fun unavailableReason(type: ConditionType, snapshot: Snapshot): Reason = when {
        type in BLUETOOTH_CONDITIONS -> Reason.BLUETOOTH_OFF
        type == ConditionType.LOCATION_WITHIN -> Reason.NO_LOCATION
        !snapshot.bridgeAvailable -> Reason.LAYER_NOT_READY
        else -> Reason.NOT_READABLE
    }

    private val BLUETOOTH_CONDITIONS =
        setOf(ConditionType.BT_DEVICE_CONNECTED, ConditionType.ANY_BT_CONNECTED)

    /** The first check [com.mg4.tasker.vehicle.DirectExecutor] would fail on, in its own order. */
    private fun blockingReason(type: ActionType, caps: Capabilities): Reason = when (type) {
        ActionType.APPLY_PROFILE -> when {
            !caps.mg4ControlInstalled -> Reason.NO_MG4CONTROL
            !caps.profileBridgeReachable -> Reason.MG4CONTROL_UNREACHABLE
            else -> gateReason(type, caps)
        }
        // Resolving the target package is a per-rule matter, not a per-action one: the
        // executor checks the package the rule names, which this screen does not know.
        ActionType.LAUNCH_APP -> Reason.NONE
        // Always reaches the driver: the message is shown on screen, and the notification
        // is the part that may be silenced. The NOTIFICATIONS row still reports the channel.
        ActionType.SHOW_NOTIFICATION -> Reason.NONE
        ActionType.SPEAK_TEXT ->
            if (caps.ttsEngineAvailable) Reason.NONE else Reason.NO_TTS_ENGINE
        ActionType.NAVIGATE_TO ->
            if (caps.navigationApp) Reason.NONE else Reason.NO_NAVIGATION_APP

        // Vendor services — bound separately from the AOSP car layer, so the layer being
        // down says nothing about them and vice versa.
        in CLIMATE_ACTIONS ->
            if (caps.climateService) gateReason(type, caps) else Reason.NO_VENDOR_SERVICE
        in CHARGING_ACTIONS ->
            if (caps.chargingService) gateReason(type, caps) else Reason.NO_VENDOR_SERVICE
        ActionType.PLAY_RADIO ->
            if (caps.radioService) Reason.NONE else Reason.NO_VENDOR_SERVICE
        ActionType.CALL_NUMBER, ActionType.CALL_CONTACT ->
            if (caps.phoneService) Reason.NONE else Reason.NO_VENDOR_SERVICE

        // Everything else is a direct MG4Hardware write.
        else -> if (!caps.vehicleLayerReady) Reason.LAYER_NOT_READY else gateReason(type, caps)
    }

    private val CLIMATE_ACTIONS = setOf(
        ActionType.SET_CLIMATE_POWER, ActionType.SET_CABIN_TEMP, ActionType.SET_AC,
        ActionType.SET_CLIMATE_AUTO, ActionType.SET_RECIRCULATION, ActionType.SET_FAN_LEVEL,
        ActionType.SET_FRONT_DEFROST, ActionType.SET_REAR_DEFROST,
        // Different sub-service, same hub and same bind, so the same capability answers.
        ActionType.SET_WINDOWS, ActionType.SET_DOOR_LOCK
    )

    private val CHARGING_ACTIONS = setOf(
        ActionType.SET_CHARGE_LIMIT, ActionType.SET_CHARGING_ENABLED,
        ActionType.SET_CHARGE_SCHEDULE, ActionType.SET_CHARGE_WINDOW,
        ActionType.SET_BATTERY_PREHEAT
    )

    private fun gateReason(type: ActionType, caps: Capabilities): Reason =
        if (!type.gated) Reason.NONE else when (caps.gateVerdict) {
            BridgeContract.VERDICT_ALLOWED -> Reason.NONE
            BridgeContract.VERDICT_MOVING -> Reason.GATE_MOVING
            else -> Reason.GATE_UNKNOWN_SPEED
        }

    /**
     * A condition configured well enough that [ConditionEvaluator] answers about the data
     * rather than about the rule. The comparison itself is irrelevant: only MATCH vs
     * UNAVAILABLE is read back.
     */
    private fun probeOf(type: ConditionType) = Condition(
        type = type,
        op = CompareOp.EQ,
        text = when (type) {
            ConditionType.BT_DEVICE_CONNECTED -> PROBE_MAC
            ConditionType.LOCATION_WITHIN -> PROBE_POINT
            ConditionType.DATE -> java.time.LocalDate.now().toString()
            else -> ""
        },
        days = ALL_DAYS
    )

    /**
     * Raw value behind the condition, left untranslated — rendering belongs to the caller.
     *
     * The context conditions carry no snapshot key (they are computed locally), but they are
     * exactly the ones a user misreads: seeing the connected MACs is what explains a
     * Bluetooth rule that never fires.
     */
    private fun rawValue(type: ConditionType, snapshot: Snapshot): Any? = when (type) {
        ConditionType.TIME_OF_DAY -> snapshot.minutesOfDay
        ConditionType.DAY_OF_WEEK -> snapshot.dayOfWeek
        ConditionType.DATE -> snapshot.localDate
        ConditionType.ANY_BT_CONNECTED -> snapshot.btMacs.isNotEmpty()
        ConditionType.BT_DEVICE_CONNECTED -> snapshot.btMacs.sorted().joinToString(", ")
        // Where the car thinks it is, which is the only way to tell a radius that is too
        // small from a fix that is simply wrong.
        ConditionType.LOCATION_WITHIN ->
            snapshot.latitude?.let { lat -> snapshot.longitude?.let { lon -> "$lat,$lon" } }
        else -> type.snapshotKey?.let { snapshot.readings[it] }
    }
}
