package com.evsuite.tasker.debug

import com.evsuite.hardware.FirmwareGen
import com.evsuite.hardware.FirmwareSupport
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.effectProven
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.engine.ActionCompatibility
import com.evsuite.tasker.engine.ConditionEvaluator
import com.evsuite.tasker.model.CompareOp
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.ConditionOutcome
import com.evsuite.tasker.model.Snapshot

/**
 * Turns the execution context into a per-catalogue-entry verdict.
 *
 * The contract of this screen is strong and deliberate: **OK means the rule engine will not
 * refuse this entry right now.** So nothing here re-implements the decision — a condition is
 * declared readable only if [ConditionEvaluator], the very object the engine calls, says so
 * on the same snapshot, and an action is declared runnable only after every check
 * `DirectExecutor` performs before it writes (firmware matrix, standstill gate, EVProfile
 * bind, TTS engine, notification channel) has passed.
 *
 * What it cannot do is perform the write itself: applying a drive mode to see whether it
 * sticks would change the car under the driver. For vehicle writes, "OK" therefore means
 * "everything the app checks before writing passes"; the write itself is the only step left,
 * and it is the one the history reports afterwards.
 *
 * That last step is only a promise worth making where the write is known to do something.
 * Where it is not — [ActionType.writeProven] `= false` — the entry is blocked here
 * ([Reason.WRITE_UNPROVEN]) and dropped from the picker rather than shown OK, because a
 * service that accepts a value and silently drops it passes every check this screen can
 * make. OK must mean the action works, not that nothing stood in its way.
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
        /** EVHardware is not ready at all: every vehicle signal and write is out. */
        LAYER_NOT_READY,
        /** Standstill gate: the car is moving. */
        GATE_MOVING,
        /** Standstill gate: speed unreadable, so the gate fails closed. */
        GATE_UNKNOWN_SPEED,
        /** This firmware generation is not in the entry's `@SupportedOn` set. */
        UNSUPPORTED_FIRMWARE,
        /**
         * The write exists and would be accepted, but nothing establishes that it does
         * anything — [com.evsuite.hardware.catalog.ActionType.writeProven].
         */
        WRITE_UNPROVEN,
        NO_EVPROFILE,
        EVPROFILE_UNREACHABLE,
        NO_TTS_ENGINE,
        NOTIFICATIONS_OFF,
        /** The SAIC vendor service behind this entry is not bound on this car. */
        NO_VENDOR_SERVICE,
        /** No phone is connected on the Bluetooth message profile, so nothing can carry an SMS. */
        NO_MESSAGING_PHONE,
        NO_MESSAGING_PROFILE,
        /** Nothing on the head unit answers a `geo:` intent. */
        NO_NAVIGATION_APP,
        /** No location permission, or no fix recent enough to place the car. */
        NO_LOCATION,
        /** The car has not moved yet, so no device can be called "on board". */
        NOT_DRIVEN_YET,
        /** The platform would not say which phone the head unit made hands-free. */
        NO_HANDSFREE_INFO,
        ;

        /**
         * True when this verdict describes **the car**, not the moment it was taken.
         *
         * The rule editor hides entries the last diagnostic blocked for one of these
         * ([com.evsuite.tasker.store.SupportStore]) — a diagnostic that says "this head unit has
         * no radio service" and an action picker that keeps offering "tune radio" cannot both
         * be right, and the picker was the one lying.
         *
         * Everything else is deliberately absent. A gate refusal, a Bluetooth radio switched
         * off, a missing location fix, a vehicle layer still starting: all of those change
         * within one drive, and a rule the user could not even write because the car happened
         * to be moving during a diagnostic would be a far worse bug than the one being fixed.
         *
         * [NOTIFICATIONS_OFF] is left out for the same reason from the other side: it is a
         * permission the user flips in Settings, and the entry must be there when they come
         * back from flipping it.
         */
        val describesTheCar: Boolean
            get() = this in STRUCTURAL
    }

    private val STRUCTURAL = setOf(
        Reason.UNSUPPORTED_FIRMWARE,
        // Not about the car but about what the project knows about it, which changes only
        // with an app update — never within a drive, which is what this set is really for.
        Reason.WRITE_UNPROVEN,
        Reason.NO_VENDOR_SERVICE,
        Reason.NO_NAVIGATION_APP,
        Reason.NO_TTS_ENGINE,
        Reason.NO_EVPROFILE,
        // The head unit's Bluetooth stack carries no MAP client. Unlike a phone that left with
        // its owner, this is what the car *is*: no pairing, no permission and no drive changes
        // it, so the editor should stop offering an action that can never run here.
        Reason.NO_MESSAGING_PROFILE,
        Reason.NOT_READABLE
    )

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
        val evprofileInstalled: Boolean,
        /** EVProfile's bridge actually bound — installed is not the same as reachable. */
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
        /** A phone connected on the Bluetooth message profile — what carries a text message. */
        val messagingPhone: Boolean = false,
        /** False when this head unit's Bluetooth stack carries no MAP client at all. */
        val messagingProfile: Boolean = true,
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
            // A steering-wheel button has no resting value: the snapshot carries one only
            // while the press is being dispatched. Probing it at rest therefore always found
            // nothing, and the report accused the car of not reporting a signal it reports
            // perfectly well — a false verdict the rule editor now acts on by hiding the
            // entry. There is nothing to read here, and nothing wrong.
            if (type == ConditionType.PHYSICAL_BUTTON) {
                return@map Entry(type.name, Status.OK, Reason.NONE, hidden = hidden)
            }
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
     *
     * An action whose write was never shown to do anything
     * ([ActionType.writeProven]) is blocked for the same reason, one step earlier: OK on this
     * screen is a promise that the action works, and a write the service accepts and drops
     * would break that promise while every check above it passed.
     */
    fun actions(caps: Capabilities, gen: FirmwareGen?): List<Entry> =
        ActionType.entries.map { type ->
            val hidden = !ActionCompatibility.isConfirmed(type, gen) || !type.effectProven
            val reason = when {
                !ActionCompatibility.isConfirmed(type, gen) -> Reason.UNSUPPORTED_FIRMWARE
                !type.effectProven -> Reason.WRITE_UNPROVEN
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
        // Order matters: with the radio off nothing is knowable, and saying "the car has
        // not moved yet" would send the reader looking for a drive instead of a switch.
        !snapshot.btAvailable && type in BLUETOOTH_CONDITIONS -> Reason.BLUETOOTH_OFF
        type == ConditionType.BT_DEVICE_ONBOARD -> Reason.NOT_DRIVEN_YET
        type == ConditionType.BT_DEVICE_HANDSFREE -> Reason.NO_HANDSFREE_INFO
        type in BLUETOOTH_CONDITIONS -> Reason.BLUETOOTH_OFF
        type == ConditionType.LOCATION_WITHIN -> Reason.NO_LOCATION
        !snapshot.bridgeAvailable -> Reason.LAYER_NOT_READY
        else -> Reason.NOT_READABLE
    }

    private val BLUETOOTH_CONDITIONS = setOf(
        ConditionType.BT_DEVICE_CONNECTED,
        ConditionType.ANY_BT_CONNECTED,
        ConditionType.BT_DEVICE_ONBOARD,
        ConditionType.BT_DEVICE_HANDSFREE
    )

    /** The first check [com.evsuite.tasker.vehicle.DirectExecutor] would fail on, in its own order. */
    private fun blockingReason(type: ActionType, caps: Capabilities): Reason = when (type) {
        ActionType.APPLY_PROFILE, ActionType.SHOW_PROFILE_PICKER -> when {
            !caps.evprofileInstalled -> Reason.NO_EVPROFILE
            !caps.profileBridgeReachable -> Reason.EVPROFILE_UNREACHABLE
            else -> gateReason(type, caps)
        }
        // Resolving the target package is a per-rule matter, not a per-action one: the
        // executor checks the package the rule names, which this screen does not know.
        ActionType.LAUNCH_APP -> Reason.NONE
        ActionType.SHOW_NOTIFICATION ->
            if (caps.notificationsEnabled) Reason.NONE else Reason.NOTIFICATIONS_OFF
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
        // The whole tuner family binds one vendor service, so one capability answers for all
        // of it. Station stepping used to fall through to the AOSP car layer below, which
        // answers a different question entirely: the layer can be up with no radio service in
        // sight, and the screen said "layer not ready" for a radio that was simply absent.
        in RADIO_ACTIONS ->
            if (caps.radioService) gateReason(type, caps) else Reason.NO_VENDOR_SERVICE
        ActionType.CALL_NUMBER, ActionType.CALL_CONTACT ->
            if (caps.phoneService) Reason.NONE else Reason.NO_VENDOR_SERVICE

        // These run entirely inside EVTasker. Their configured value can still be invalid,
        // but availability of the vehicle layer is irrelevant to whether the action exists.
        ActionType.WEBHOOK, ActionType.DELAY, ActionType.ASK_CONFIRM,
        // Rule chaining, the media key and the head unit's own radios: all platform calls.
        // Whether the target rule still exists, or whether anything is playing to receive
        // the key, is a per-rule matter this screen does not know.
        ActionType.ENABLE_RULE, ActionType.DISABLE_RULE, ActionType.MEDIA_CONTROL,
        ActionType.SET_BLUETOOTH, ActionType.SET_WIFI -> Reason.NONE

        // Not a vendor service: the message leaves through the paired phone over the
        // Bluetooth message profile, so what decides is whether a phone is connected on it.
        ActionType.SEND_SMS ->
            when {
                caps.messagingPhone -> Reason.NONE
                // The car, not the phone: no amount of pairing fixes a stack with no MAP client.
                !caps.messagingProfile -> Reason.NO_MESSAGING_PROFILE
                else -> Reason.NO_MESSAGING_PHONE
            }

        // Everything else is a direct EVHardware write.
        else -> if (!caps.vehicleLayerReady) Reason.LAYER_NOT_READY else gateReason(type, caps)
    }

    private val CLIMATE_ACTIONS = setOf(
        ActionType.SET_CLIMATE_POWER, ActionType.SET_CABIN_TEMP, ActionType.SET_AC,
        ActionType.SET_CLIMATE_AUTO, ActionType.SET_RECIRCULATION, ActionType.SET_FAN_LEVEL,
        ActionType.SET_FRONT_DEFROST, ActionType.SET_REAR_DEFROST,
        ActionType.SET_ECON, ActionType.SET_PASSENGER_TEMP,
        // Different sub-service, same hub and same bind, so the same capability answers.
        ActionType.SET_WINDOWS, ActionType.SET_DOOR_LOCK,
        ActionType.SET_WINDOW_DRIVER, ActionType.SET_WINDOW_PASSENGER,
        ActionType.SET_WINDOW_REAR_LEFT, ActionType.SET_WINDOW_REAR_RIGHT
    )

    private val RADIO_ACTIONS = setOf(
        ActionType.PLAY_RADIO, ActionType.PAUSE_RADIO, ActionType.RADIO_PLAY_PAUSE,
        ActionType.TUNE_RADIO, ActionType.RADIO_NEXT_STATION, ActionType.RADIO_PREV_STATION,
        ActionType.SELECT_RADIO_BAND,
        // The gated one: gateReason above is what refuses it while the car is moving.
        ActionType.OPEN_RADIO_SCREEN
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
            ConditionType.BT_DEVICE_CONNECTED,
            ConditionType.BT_DEVICE_ONBOARD,
            ConditionType.BT_DEVICE_HANDSFREE -> PROBE_MAC
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
        // Null never reaches here — an unavailable condition reports its reason instead —
        // but the empty set does, and "connected, none of them travelling" is exactly the
        // state a user misreads.
        ConditionType.BT_DEVICE_ONBOARD -> snapshot.btOnboardMacs?.sorted()?.joinToString(", ")
        ConditionType.BT_DEVICE_HANDSFREE -> snapshot.btHandsFreeMacs?.sorted()?.joinToString(", ")
        // Where the car thinks it is, which is the only way to tell a radius that is too
        // small from a fix that is simply wrong.
        ConditionType.LOCATION_WITHIN ->
            snapshot.latitude?.let { lat -> snapshot.longitude?.let { lon -> "$lat,$lon" } }
        else -> type.snapshotKey?.let { snapshot.readings[it] }
    }
}
