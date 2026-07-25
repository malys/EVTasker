package com.mg4.tasker.model

import com.mg4.hardware.catalog.ConditionType
import com.mg4.hardware.catalog.ActionType

/** Outcome of evaluating one condition. */
enum class ConditionOutcome {
    MATCH,
    NO_MATCH,
    /** The value could not be read — the firmware does not expose it, or no bridge. */
    UNAVAILABLE
}

/** What became of a rule during one evaluation cycle. */
enum class RuleOutcome {
    /** Conditions met, actions attempted. */
    FIRED,
    /** Conditions evaluated, not met. */
    NOT_MATCHED,
    /** At least one condition unreadable: no conclusion possible, so no action taken. */
    NOT_EVALUABLE,
    /** Rule switched off by the user. */
    DISABLED
}

/**
 * Result of one attempted action.
 *
 * [verdict] reuses MG4Control's vocabulary (ALLOWED, REFUSED_MOVING…). That is what makes
 * "refused because the car was moving" displayable instead of an anonymous failure.
 */
data class ActionResult(
    val actionType: ActionType,
    val ok: Boolean,
    val verdict: String,
    val detail: String? = null,
    /** How many tries this took. >1 means the earlier ones hit a transient ERROR and were retried. */
    val attempts: Int = 1
)

/** One clear verdict for a rule's cycle: applied, skipped, or failed. */
enum class RuleStatus {
    APPLIED,
    SKIPPED,
    FAILED
}

/** Trace of one rule during a cycle, as shown in the history. */
data class RuleRun(
    val ruleId: String,
    val ruleName: String,
    val outcome: RuleOutcome,
    val actionResults: List<ActionResult> = emptyList(),
    /** Conditions that could not be read, to explain a NOT_EVALUABLE. */
    val unavailableConditions: List<ConditionType> = emptyList()
) {
    /** Derived from [outcome] and [actionResults] so callers don't re-derive this logic themselves. */
    val status: RuleStatus
        get() = when {
            outcome != RuleOutcome.FIRED -> RuleStatus.SKIPPED
            actionResults.any { !it.ok }  -> RuleStatus.FAILED
            else                          -> RuleStatus.APPLIED
        }
}

/** Trace of a full trigger (one ignition cycle, or a manual test). */
data class EngineRun(
    val timestamp: Long,
    val trigger: String,
    val bridgeAvailable: Boolean,
    val ruleRuns: List<RuleRun>
)
