package com.mg4.tasker.model

import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ActionType
import java.util.UUID

/** Comparison operator for numeric conditions. */
enum class CompareOp { EQ, NE, LT, LE, GT, GE }

/** How a rule's conditions combine. */
enum class MatchMode { ALL, ANY }

/**
 * What makes a rule run.
 *
 * The vehicle service already receives every ignition transition — it simply ignored all but
 * RUN. So switching off costs no extra listener, no extra bind and no polling; it is the
 * same event stream, read to the end.
 *
 * At [IGNITION_OFF] the car is powering down. Settings that persist (charge limit, door
 * locks, windows) land; anything the vehicle drops with the ignition may not, and the
 * history reports what each action returned rather than assuming.
 */
enum class RuleTrigger { IGNITION_ON, IGNITION_OFF }

/**
 * One configured condition.
 *
 * The value fields form a flat union rather than a sealed hierarchy: Gson serialises flat
 * data classes without an adapter, and [ConditionType.spec] already says which field is
 * authoritative. A sealed hierarchy would mean writing and maintaining a TypeAdapter for
 * no difference in use.
 */
data class Condition(
    val type: ConditionType,
    val op: CompareOp = CompareOp.EQ,
    /** Numeric threshold — temperature, speed, volume, level… */
    val number: Float = 0f,
    /** Expected value for boolean conditions. */
    val flag: Boolean = true,
    /** Bluetooth MAC address, or firmware generation identifier. */
    val text: String = "",
    // DATE also uses text, as an ISO-8601 local date (yyyy-MM-dd), for stable persistence.
    /** [ConditionType.TIME_OF_DAY] — minutes since midnight. */
    val minutesFrom: Int = 0,
    val minutesTo: Int = 0,
    /** [ConditionType.DAY_OF_WEEK] — java.util.Calendar.MONDAY…SUNDAY values. */
    val days: List<Int> = emptyList()
)

/** One configured action. */
data class Action(
    val type: ActionType,
    /** Numeric value or enum value, depending on [ActionType.spec]. */
    val number: Int = 0,
    val flag: Boolean = true,
    /** Profile id, package name, notification text, destination or phone number. */
    val text: String = "",
    /** Optional webhook POST body. Nullable so rules saved before this field remain safe. */
    val payload: String? = null,
    /** Human-readable contact name; execution deliberately uses [text], the stored number. */
    val displayName: String? = null,
    /** [ActionType.SET_CHARGE_WINDOW] — minutes since midnight, start and end. */
    val minutesFrom: Int = 0,
    val minutesTo: Int = 0
)

/**
 * A rule: conditions, how they combine, and actions.
 *
 * No "last run" field here: rules are evaluated once per ignition cycle and the history
 * lives in [com.mg4.tasker.store.HistoryStore]. Mixing the two would rewrite the rules
 * file on every vehicle start.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val match: MatchMode = MatchMode.ALL,
    /**
     * Nullable on purpose. Gson builds instances without calling the constructor, so a
     * Kotlin default never applies to a field absent from stored JSON — a non-null type here
     * would hand the engine a null and crash on every rule saved before this existed. Read
     * it through [firesOn].
     */
    val trigger: RuleTrigger? = null,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList()
) {
    /**
     * A rule with no condition would apply on every start without the user asking; a rule
     * with no action does nothing. Both are refused at save time rather than silently
     * ignored at run time.
     *
     * A rule made only of waits counts as having no action: it holds the cycle for its
     * duration and changes nothing.
     */
    fun isComplete(): Boolean =
        conditions.isNotEmpty() && actions.any { it.type != ActionType.DELAY }

    /** [trigger], defaulting to vehicle start — what every rule written before this did. */
    val firesOn: RuleTrigger get() = trigger ?: RuleTrigger.IGNITION_ON

    /** Button conditions are event sources themselves; the ignition trigger is ignored. */
    val hasPhysicalButtonCondition: Boolean get() =
        conditions.any { it.type.eventDriven }
}
