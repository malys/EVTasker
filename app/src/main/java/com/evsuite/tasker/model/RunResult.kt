package com.evsuite.tasker.model

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.bridge.BridgeContract

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
 * [verdict] reuses EVProfile's vocabulary (ALLOWED, REFUSED_MOVING…). That is what makes
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

/** [RuleRun.firedBranch] for the rule's own conditions — the "if". */
const val BRANCH_IF = 0

/** [RuleRun.firedBranch] for the trailing "else", which has no conditions to index. */
const val BRANCH_ELSE = -1

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
    val unavailableConditions: List<ConditionType> = emptyList(),
    /**
     * Which branch ran: [BRANCH_IF], the 1-based index of an "else if", or [BRANCH_ELSE].
     *
     * Null when the rule has no branch to name — either it is a plain if/then rule, or
     * nothing fired. Also null for every history entry written before branches existed,
     * which is why it is nullable rather than defaulted to [BRANCH_IF].
     */
    val firedBranch: Int? = null
) {
    /** Derived from [outcome] and [actionResults] so callers don't re-derive this logic themselves. */
    val status: RuleStatus
        get() = when {
            outcome != RuleOutcome.FIRED -> RuleStatus.SKIPPED
            actionResults.any { !it.ok }  -> RuleStatus.FAILED
            // The driver said no, and the rest of the branch was dropped on purpose. Not
            // applied — most of it never ran — and not failed either: nothing went wrong.
            // Checked after the failure case so a real failure earlier in the branch is
            // still the headline.
            actionResults.any { it.verdict == BridgeContract.VERDICT_DECLINED } ->
                RuleStatus.SKIPPED
            else                          -> RuleStatus.APPLIED
        }
}

/** Trace of one complete vehicle, button, or manual trigger. */
data class EngineRun(
    val timestamp: Long,
    val trigger: String,
    val bridgeAvailable: Boolean,
    val ruleRuns: List<RuleRun>
)
