package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.ConditionOutcome
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
    private val sleep: (Long) -> Unit = Thread::sleep
) {

    fun run(rules: List<Rule>, snapshot: Snapshot, trigger: String, now: Long): EngineRun =
        EngineRun(
            timestamp = now,
            trigger = trigger,
            bridgeAvailable = snapshot.bridgeAvailable,
            ruleRuns = rules.map { evaluateRule(it, snapshot) }
        )

    private fun evaluateRule(rule: Rule, snapshot: Snapshot): RuleRun {
        if (!rule.enabled) {
            return RuleRun(rule.id, rule.name, RuleOutcome.DISABLED)
        }

        val outcomes = rule.conditions.map { it.type to ConditionEvaluator.evaluate(it, snapshot) }
        val unavailable = outcomes.filter { it.second == ConditionOutcome.UNAVAILABLE }.map { it.first }

        when (rule.match) {
            MatchMode.ALL -> {
                // Under ALL, an unreadable condition prevents any conclusion: we cannot
                // claim that EVERY condition holds. Acting anyway would mean writing to
                // the vehicle on an assumption.
                if (unavailable.isNotEmpty()) return notEvaluable(rule, unavailable)
                if (outcomes.any { it.second == ConditionOutcome.NO_MATCH }) {
                    return RuleRun(rule.id, rule.name, RuleOutcome.NOT_MATCHED)
                }
            }
            MatchMode.ANY -> {
                // Under ANY, one true condition settles it, so unreadable ones do not
                // block the conclusion. But if NONE is true and some remain unreadable,
                // the result is undetermined, not negative.
                if (outcomes.none { it.second == ConditionOutcome.MATCH }) {
                    return if (unavailable.isNotEmpty()) notEvaluable(rule, unavailable)
                    else RuleRun(rule.id, rule.name, RuleOutcome.NOT_MATCHED)
                }
            }
        }

        val results = rule.actions.map { executeWithBackoff(it) }
        return RuleRun(rule.id, rule.name, RuleOutcome.FIRED, results)
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
