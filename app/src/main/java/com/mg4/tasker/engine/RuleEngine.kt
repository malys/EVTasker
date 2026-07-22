package com.mg4.tasker.engine

import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.ConditionOutcome
import com.mg4.tasker.model.ConditionType
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
 */
class RuleEngine(private val executor: ActionExecutor) {

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

        val results = rule.actions.map { executor.execute(it) }
        return RuleRun(rule.id, rule.name, RuleOutcome.FIRED, results)
    }

    private fun notEvaluable(rule: Rule, unavailable: List<ConditionType>) =
        RuleRun(rule.id, rule.name, RuleOutcome.NOT_EVALUABLE, unavailableConditions = unavailable)
}
