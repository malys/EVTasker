package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.BRANCH_ELSE
import com.mg4.tasker.model.Branch
import com.mg4.tasker.model.ConditionOutcome
import com.mg4.tasker.model.DELAY_BUDGET_MS
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.RuleRun
import com.mg4.tasker.model.Snapshot

/** Runs an action and returns its verdict. Implemented by the bridge, faked in tests. */
fun interface ActionExecutor {
    fun execute(action: Action): ActionResult
}

/**
 * Decides which rules fire and has their actions executed.
 *
 * No Android dependency: the engine receives a frozen snapshot and an executor. That is
 * what makes the firing behaviour testable — including refusals and missing data — with
 * no vehicle.
 *
 * Retries [BridgeContract.VERDICT_ERROR] with exponential backoff ([initialDelayMs], then
 * ×[backoffFactor] each time, up to [maxAttempts] tries): that verdict means a transient
 * IPC/binder hiccup. Every other verdict (moving, unsupported, no bridge) is a deliberate
 * refusal — retrying it would just repeat the same answer. [sleep] is injectable so tests
 * can verify backoff without actually waiting.
 */
class RuleEngine(
    private val executor: ActionExecutor,
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 250,
    private val backoffFactor: Double = 2.0,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val delayBudgetMs: Long = DELAY_BUDGET_MS
) {

    /**
     * What is left of [delayBudgetMs] for this cycle. Reset at the start of every [run]: the
     * budget belongs to the pass, not to the engine, and the rules of one cycle share it —
     * a rule that waits also delays the rules evaluated after it.
     */
    private var remainingDelayMs: Long = delayBudgetMs

    fun run(rules: List<Rule>, snapshot: Snapshot, trigger: String, now: Long): EngineRun {
        remainingDelayMs = delayBudgetMs
        return EngineRun(
            timestamp = now,
            trigger = trigger,
            bridgeAvailable = snapshot.bridgeAvailable,
            ruleRuns = rules.map { evaluateRule(it, snapshot) }
        )
    }

    /**
     * The branches in order, first match wins, [Rule.otherwise] when none did — and the whole
     * rule abandoned the moment a branch cannot be decided.
     *
     * That last part is the safety rule of the app applied to branching. A branch whose
     * conditions cannot be read is not a branch that failed to match: treating it as one
     * would let the rule fall through to the next case, or to the "else", and write to the
     * vehicle *because* a value was missing. "Unreadable ≠ false" has to hold across the
     * chain, not only inside one branch, so an undetermined branch stops the rule where it
     * stands and the history says which readings were missing.
     */
    private fun evaluateRule(rule: Rule, snapshot: Snapshot): RuleRun {
        if (!rule.enabled) {
            return RuleRun(rule.id, rule.name, RuleOutcome.DISABLED)
        }

        // A plain if/then rule has no branch worth naming in the history.
        val named = rule.hasAlternatives
        rule.branches.forEachIndexed { index, branch ->
            when (val verdict = decide(branch, snapshot)) {
                is BranchVerdict.Match -> return fire(rule, branch.actions, if (named) index else null)
                is BranchVerdict.Undetermined -> return notEvaluable(rule, verdict.unavailable)
                is BranchVerdict.NoMatch -> Unit   // this case does not hold; try the next one
            }
        }

        if (rule.otherwise.isEmpty()) return RuleRun(rule.id, rule.name, RuleOutcome.NOT_MATCHED)
        return fire(rule, rule.otherwise, BRANCH_ELSE)
    }

    /** What one branch's conditions say: it holds, it does not, or it cannot be decided. */
    private sealed interface BranchVerdict {
        data object Match : BranchVerdict
        data object NoMatch : BranchVerdict
        data class Undetermined(val unavailable: List<ConditionType>) : BranchVerdict
    }

    private fun decide(branch: Branch, snapshot: Snapshot): BranchVerdict {
        val outcomes = branch.conditions.map { it.type to ConditionEvaluator.evaluate(it, snapshot) }
        val unavailable = outcomes.filter { it.second == ConditionOutcome.UNAVAILABLE }.map { it.first }

        return when (branch.match) {
            // Under ALL, an unreadable condition prevents any conclusion: we cannot claim
            // that EVERY condition holds. Acting anyway would mean writing to the vehicle
            // on an assumption.
            MatchMode.ALL -> when {
                unavailable.isNotEmpty() -> BranchVerdict.Undetermined(unavailable)
                outcomes.any { it.second == ConditionOutcome.NO_MATCH } -> BranchVerdict.NoMatch
                else -> BranchVerdict.Match
            }
            // Under ANY, one true condition settles it, so unreadable ones do not block the
            // conclusion. But if NONE is true and some remain unreadable, the result is
            // undetermined, not negative.
            MatchMode.ANY -> when {
                outcomes.any { it.second == ConditionOutcome.MATCH } -> BranchVerdict.Match
                unavailable.isNotEmpty() -> BranchVerdict.Undetermined(unavailable)
                else -> BranchVerdict.NoMatch
            }
        }
    }

    /**
     * Runs one branch's actions, in order.
     *
     * Sequential on purpose: two writes to the same subsystem sent at once are answered from
     * the state the car had before either landed, and the user's order is the order they
     * meant — a [ActionType.DELAY] between two of them only means anything if what follows it
     * really waits.
     */
    private fun fire(rule: Rule, actions: List<Action>, branch: Int?): RuleRun {
        val results = actions.map {
            if (it.type == ActionType.DELAY) pause(it) else executeWithBackoff(it)
        }
        return RuleRun(rule.id, rule.name, RuleOutcome.FIRED, results, firedBranch = branch)
    }

    /**
     * The wait, run here rather than in the executor.
     *
     * Nothing is written, so there is no verdict to obtain from the vehicle; and the engine
     * is what runs the actions one after the other, so it is also what can hold the line
     * between two of them. Reusing the injectable [sleep] keeps tests from actually waiting.
     *
     * Each wait is capped by the catalogue, but a chain of them is not: without a budget for
     * the cycle, a rule could hold the pass for as long as it liked, and the actions that
     * follow — in that rule and in every rule after it — would be applied against a snapshot
     * taken minutes earlier, for an ignition transition that is long over. Past the budget
     * the wait is cut short and reported as such, rather than silently ignored: what runs
     * afterwards is no longer what the rule described.
     */
    private fun pause(action: Action): ActionResult {
        val spec = ActionType.DELAY.spec
        val requested = action.number.coerceIn(spec.min, spec.max) * 1000L
        val granted = minOf(requested, remainingDelayMs)
        remainingDelayMs -= granted
        if (granted > 0) sleep(granted)
        return if (granted == requested) {
            ActionResult(ActionType.DELAY, true, BridgeContract.VERDICT_ALLOWED, "${requested / 1000} s")
        } else {
            ActionResult(
                ActionType.DELAY, false, BridgeContract.VERDICT_UNSUPPORTED,
                "${granted / 1000} s of ${requested / 1000} s — cycle wait budget spent"
            )
        }
    }

    private fun executeWithBackoff(action: Action): ActionResult {
        var delay = initialDelayMs
        var attempt = 1
        var result = executor.execute(action)
        while (result.verdict == BridgeContract.VERDICT_ERROR && attempt < maxAttempts) {
            sleep(delay)
            delay = (delay * backoffFactor).toLong()
            attempt++
            result = executor.execute(action)
        }
        return result.copy(attempts = attempt)
    }

    private fun notEvaluable(rule: Rule, unavailable: List<ConditionType>) =
        RuleRun(rule.id, rule.name, RuleOutcome.NOT_EVALUABLE, unavailableConditions = unavailable)
}
