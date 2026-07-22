package com.mg4.tasker.model

import java.util.UUID

/** Comparison operator for numeric conditions. */
enum class CompareOp { EQ, NE, LT, LE, GT, GE }

/** How a rule's conditions combine. */
enum class MatchMode { ALL, ANY }

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
    /** Profile id, package name, or notification text. */
    val text: String = ""
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
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList()
) {
    /**
     * A rule with no condition would apply on every start without the user asking; a rule
     * with no action does nothing. Both are refused at save time rather than silently
     * ignored at run time.
     */
    fun isComplete(): Boolean = conditions.isNotEmpty() && actions.isNotEmpty()
}
