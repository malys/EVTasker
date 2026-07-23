package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.hardware.catalog.ActionType
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    /** Exécuteur factice : enregistre ce qui a été tenté, ne touche à rien. */
    private class RecordingExecutor(
        private val verdict: String = BridgeContract.VERDICT_ALLOWED
    ) : ActionExecutor {
        val executed = mutableListOf<ActionType>()

        override fun execute(action: Action): ActionResult {
            executed += action.type
            return ActionResult(
                action.type,
                ok = verdict == BridgeContract.VERDICT_ALLOWED,
                verdict = verdict
            )
        }
    }

    private fun rule(
        match: MatchMode = MatchMode.ALL,
        enabled: Boolean = true,
        vararg conditions: Condition
    ) = Rule(
        name = "test",
        enabled = enabled,
        match = match,
        conditions = conditions.toList(),
        actions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 12))
    )

    private fun run(rule: Rule, snapshot: Snapshot, executor: ActionExecutor) =
        RuleEngine(executor).run(listOf(rule), snapshot, "TEST", 0L).ruleRuns.first()

    // -------------------------------------------------------------------------

    @Test
    fun `regle desactivee n execute rien`() {
        val executor = RecordingExecutor()
        val disabled = rule(enabled = false, conditions = arrayOf(
            Condition(ConditionType.AEB_ENABLED, flag = true)
        ))

        val result = run(disabled, Snapshot(readings = mapOf(BridgeContract.KEY_AEB_ENABLED to true)), executor)

        assertEquals(RuleOutcome.DISABLED, result.outcome)
        assertTrue("aucune action ne doit être tentée", executor.executed.isEmpty())
    }

    @Test
    fun `en ET toutes les conditions remplies declenchent`() {
        val executor = RecordingExecutor()
        val warm = rule(conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f),
            Condition(ConditionType.IN_PARK, flag = true)
        ))

        val result = run(warm, Snapshot(readings = mapOf(
            BridgeContract.KEY_OUTSIDE_TEMP to 18f,
            BridgeContract.KEY_IN_PARK to true
        )), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
        assertEquals(listOf(ActionType.SET_MEDIA_VOLUME), executor.executed)
    }

    @Test
    fun `en ET une condition illisible rend la regle non evaluable`() {
        // Le point central de la conception : on n'écrit pas dans un véhicule sur la base
        // d'une donnée qu'on n'a pas pu lire. La règle est signalée, pas appliquée.
        val executor = RecordingExecutor()
        val warm = rule(conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f),
            Condition(ConditionType.IN_PARK, flag = true)
        ))

        val result = run(warm, Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), executor)

        assertEquals(RuleOutcome.NOT_EVALUABLE, result.outcome)
        assertEquals(listOf(ConditionType.OUTSIDE_TEMP), result.unavailableConditions)
        assertTrue("rien ne doit être écrit sur une donnée manquante", executor.executed.isEmpty())
    }

    @Test
    fun `en OU une condition vraie suffit malgre une illisible`() {
        // En OU, une seule condition vraie tranche : inutile de connaître les autres.
        val executor = RecordingExecutor()
        val any = rule(match = MatchMode.ANY, conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f),
            Condition(ConditionType.IN_PARK, flag = true)
        ))

        val result = run(any, Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
    }

    @Test
    fun `en OU aucune vraie plus une illisible reste indetermine`() {
        val executor = RecordingExecutor()
        val any = rule(match = MatchMode.ANY, conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f),
            Condition(ConditionType.IN_PARK, flag = true)
        ))

        val result = run(any, Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to false)), executor)

        assertEquals(RuleOutcome.NOT_EVALUABLE, result.outcome)
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `en OU aucune vraie et toutes lisibles donne non remplie`() {
        val executor = RecordingExecutor()
        val any = rule(match = MatchMode.ANY, conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f),
            Condition(ConditionType.IN_PARK, flag = true)
        ))

        val result = run(any, Snapshot(readings = mapOf(
            BridgeContract.KEY_OUTSIDE_TEMP to 2f,
            BridgeContract.KEY_IN_PARK to false
        )), executor)

        assertEquals(RuleOutcome.NOT_MATCHED, result.outcome)
    }

    @Test
    fun `sans pont aucune regle vehicule ne se declenche`() {
        // MG4Control absent : l'instantané est vide. Rien ne doit partir « au cas où ».
        val executor = RecordingExecutor()
        val warm = rule(conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f)
        ))

        val result = run(warm, Snapshot(bridgeAvailable = false), executor)

        assertEquals(RuleOutcome.NOT_EVALUABLE, result.outcome)
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `un refus du verrou est rapporte et non masque`() {
        // La règle a bien matché ; c'est MG4Control qui a refusé l'écriture. L'historique
        // doit porter le motif, sinon l'utilisateur conclut que sa règle est mauvaise.
        val executor = RecordingExecutor(verdict = BridgeContract.VERDICT_MOVING)
        val warm = rule(conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f)
        ))

        val result = run(warm, Snapshot(readings = mapOf(BridgeContract.KEY_OUTSIDE_TEMP to 20f)), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
        assertEquals(1, result.actionResults.size)
        assertEquals(BridgeContract.VERDICT_MOVING, result.actionResults.first().verdict)
        assertEquals(false, result.actionResults.first().ok)
    }

    @Test
    fun `chaque regle est evaluee independamment`() {
        val executor = RecordingExecutor()
        val matching = Rule(
            name = "ok",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(Action(ActionType.SET_STEERING_HEAT, flag = true))
        )
        val notMatching = Rule(
            name = "ko",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = false)),
            actions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 5))
        )

        val engine = RuleEngine(executor)
        val run = engine.run(
            listOf(matching, notMatching),
            Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)),
            "TEST", 0L
        )

        assertEquals(RuleOutcome.FIRED, run.ruleRuns[0].outcome)
        assertEquals(RuleOutcome.NOT_MATCHED, run.ruleRuns[1].outcome)
        assertEquals(listOf(ActionType.SET_STEERING_HEAT), executor.executed)
    }
}
