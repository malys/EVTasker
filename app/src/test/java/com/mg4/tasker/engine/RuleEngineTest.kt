package com.mg4.tasker.engine

import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.hardware.catalog.ActionType
import com.mg4.tasker.model.BRANCH_ELSE
import com.mg4.tasker.model.BRANCH_IF
import com.mg4.tasker.model.Branch
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleTrigger
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.RuleStatus
import com.mg4.tasker.model.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // -------------------------------------------------------------------------
    // Backoff — ERROR only, and rule status
    // -------------------------------------------------------------------------

    /** Fails the first [failures] calls with ERROR, then succeeds. */
    private class FlakyExecutor(private val failures: Int) : ActionExecutor {
        var calls = 0
        override fun execute(action: Action): ActionResult {
            calls++
            return if (calls <= failures) ActionResult(action.type, false, BridgeContract.VERDICT_ERROR)
            else ActionResult(action.type, true, BridgeContract.VERDICT_ALLOWED)
        }
    }

    @Test
    fun `une erreur transitoire est retentee avec un delai croissant puis reussit`() {
        val executor = FlakyExecutor(failures = 2)
        val sleeps = mutableListOf<Long>()
        val warm = rule(conditions = arrayOf(Condition(ConditionType.IN_PARK, flag = true)))

        val engine = RuleEngine(executor, maxAttempts = 3, initialDelayMs = 100, sleep = { sleeps += it })
        val result = engine.run(listOf(warm), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L)
            .ruleRuns.first()

        assertEquals(3, executor.calls)
        assertEquals(listOf(100L, 200L), sleeps)
        assertEquals(true, result.actionResults.first().ok)
        assertEquals(3, result.actionResults.first().attempts)
        assertEquals(RuleStatus.APPLIED, result.status)
    }

    @Test
    fun `une erreur transitoire persistante epuise les tentatives et la regle est en echec`() {
        val executor = FlakyExecutor(failures = 10)
        val warm = rule(conditions = arrayOf(Condition(ConditionType.IN_PARK, flag = true)))

        val engine = RuleEngine(executor, maxAttempts = 3, initialDelayMs = 0, sleep = {})
        val result = engine.run(listOf(warm), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L)
            .ruleRuns.first()

        assertEquals(3, executor.calls)
        assertEquals(false, result.actionResults.first().ok)
        assertEquals(RuleStatus.FAILED, result.status)
    }

    @Test
    fun `un refus delibere n est pas retente`() {
        val executor = RecordingExecutor(verdict = BridgeContract.VERDICT_MOVING)
        val warm = rule(conditions = arrayOf(Condition(ConditionType.IN_PARK, flag = true)))

        val engine = RuleEngine(executor, sleep = { throw AssertionError("no sleep expected") })
        val result = engine.run(listOf(warm), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L)
            .ruleRuns.first()

        assertEquals(1, result.actionResults.first().attempts)
        assertEquals(RuleStatus.FAILED, result.status)
    }

    @Test
    fun `une regle appliquee sans echec est au statut applied`() {
        val executor = RecordingExecutor()
        val warm = rule(conditions = arrayOf(Condition(ConditionType.IN_PARK, flag = true)))

        val result = run(warm, Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), executor)

        assertEquals(RuleStatus.APPLIED, result.status)
    }

    @Test
    fun `une regle non evaluable est au statut skipped`() {
        val executor = RecordingExecutor()
        val warm = rule(conditions = arrayOf(
            Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.GT, number = 10f)
        ))

        val result = run(warm, Snapshot(bridgeAvailable = false), executor)

        assertEquals(RuleStatus.SKIPPED, result.status)
    }

    // -------------------------------------------------------------------------
    // Ordre des actions et attente
    // -------------------------------------------------------------------------

    @Test
    fun `les actions s executent dans l ordre de la regle`() {
        val executor = RecordingExecutor()
        val ordered = Rule(
            name = "ordre",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(
                Action(ActionType.SET_CLIMATE_POWER, flag = true),
                Action(ActionType.SET_FAN_LEVEL, number = 3),
                Action(ActionType.SET_CABIN_TEMP, number = 21)
            )
        )

        RuleEngine(executor).run(
            listOf(ordered), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L
        )

        assertEquals(
            listOf(ActionType.SET_CLIMATE_POWER, ActionType.SET_FAN_LEVEL, ActionType.SET_CABIN_TEMP),
            executor.executed
        )
    }

    @Test
    fun `l attente retarde l action suivante sans passer par l executeur`() {
        val executor = RecordingExecutor()
        val sleeps = mutableListOf<Long>()
        val paced = Rule(
            name = "attente",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(
                Action(ActionType.SET_CLIMATE_POWER, flag = true),
                Action(ActionType.DELAY, number = 5),
                Action(ActionType.SET_FAN_LEVEL, number = 3)
            )
        )

        val result = RuleEngine(executor, sleep = { sleeps += it }).run(
            listOf(paced), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L
        ).ruleRuns.first()

        // L'attente n'est pas une écriture véhicule : elle ne doit rien demander à l'exécuteur.
        assertEquals(listOf(ActionType.SET_CLIMATE_POWER, ActionType.SET_FAN_LEVEL), executor.executed)
        assertEquals(listOf(5_000L), sleeps)
        // Elle reste visible dans l'historique, à sa place dans la séquence.
        assertEquals(
            listOf(ActionType.SET_CLIMATE_POWER, ActionType.DELAY, ActionType.SET_FAN_LEVEL),
            result.actionResults.map { it.actionType }
        )
        assertEquals(RuleStatus.APPLIED, result.status)
    }

    @Test
    fun `une attente hors bornes est ramenee dans celles du catalogue`() {
        val sleeps = mutableListOf<Long>()
        // number = 0 : ce qu'une règle enregistrée sans valeur porterait.
        val zero = Rule(
            name = "zero",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(Action(ActionType.DELAY), Action(ActionType.SET_FAN_LEVEL, number = 1))
        )

        RuleEngine(RecordingExecutor(), sleep = { sleeps += it }).run(
            listOf(zero), Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), "TEST", 0L
        )

        assertEquals(listOf(ActionType.DELAY.spec.min * 1_000L), sleeps)
    }

    @Test
    fun `le budget d attente du cycle est partage par toutes les regles`() {
        val executor = RecordingExecutor()
        val sleeps = mutableListOf<Long>()
        fun waiting(name: String) = Rule(
            name = name,
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(
                Action(ActionType.DELAY, number = 30),
                Action(ActionType.SET_FAN_LEVEL, number = 1)
            )
        )

        // 45 s de budget : la première règle en consomme 30, la seconde n'en obtient que 15.
        val engine = RuleEngine(executor, sleep = { sleeps += it }, delayBudgetMs = 45_000L)
        val runs = engine.run(
            listOf(waiting("un"), waiting("deux")),
            Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)),
            "TEST", 0L
        ).ruleRuns

        assertEquals(listOf(30_000L, 15_000L), sleeps)
        assertEquals(true, runs[0].actionResults.first().ok)
        // L'attente écourtée est signalée, pas passée sous silence.
        val truncated = runs[1].actionResults.first()
        assertEquals(false, truncated.ok)
        assertEquals(BridgeContract.VERDICT_UNSUPPORTED, truncated.verdict)
        // Ce qui suivait s'exécute quand même : le budget borne l'attente, pas la règle.
        assertEquals(
            listOf(ActionType.SET_FAN_LEVEL, ActionType.SET_FAN_LEVEL),
            executor.executed
        )
    }

    @Test
    fun `le budget d attente repart entier a chaque cycle`() {
        val sleeps = mutableListOf<Long>()
        val waiting = Rule(
            name = "attente",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(Action(ActionType.DELAY, number = 10), Action(ActionType.SET_FAN_LEVEL, number = 1))
        )
        val snapshot = Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true))

        val engine = RuleEngine(RecordingExecutor(), sleep = { sleeps += it }, delayBudgetMs = 10_000L)
        engine.run(listOf(waiting), snapshot, "TEST", 0L)
        engine.run(listOf(waiting), snapshot, "TEST", 1L)

        // Un budget qui ne se réarme pas ferait du deuxième démarrage une règle sans attente.
        assertEquals(listOf(10_000L, 10_000L), sleeps)
    }

    // -------------------------------------------------------------------------
    // Triggers
    // -------------------------------------------------------------------------

    /**
     * The routing itself, kept out of [com.mg4.tasker.vehicle.RuleCycle] so it can be tested
     * without a vehicle: a rule is addressed by the event it was wired to, and a manual test
     * addresses every rule whatever it says.
     */
    private fun addressed(rules: List<Rule>, trigger: String, ruleId: String? = null): List<Rule> =
        if (trigger == "MANUAL") rules.filter { it.id == ruleId }
        else rules.filter { it.firesOn.name == trigger }

    @Test
    fun `un declencheur n adresse que les regles qui lui sont cablees`() {
        val onStart = Rule(name = "start", trigger = RuleTrigger.IGNITION_ON)
        val onStop = Rule(name = "stop", trigger = RuleTrigger.IGNITION_OFF)
        val legacy = Rule(name = "legacy")   // écrite avant les déclencheurs

        assertEquals(
            listOf("start", "legacy"),
            addressed(listOf(onStart, onStop, legacy), "IGNITION_ON").map { it.name }
        )
        assertEquals(
            listOf("stop"),
            addressed(listOf(onStart, onStop, legacy), "IGNITION_OFF").map { it.name }
        )
    }

    @Test
    fun `le test manuel adresse uniquement la regle selectionnee`() {
        val rules = listOf(
            Rule(name = "start", trigger = RuleTrigger.IGNITION_ON),
            Rule(name = "stop", trigger = RuleTrigger.IGNITION_OFF)
        )

        assertEquals(listOf(rules[1]), addressed(rules, "MANUAL", rules[1].id))
    }

    // -------------------------------------------------------------------------
    // si / sinon si / sinon
    // -------------------------------------------------------------------------

    /** Froid : sièges chauffants ; tiède : ventilation ; sinon : volume média. */
    private fun byTemperature(elseActions: List<Action>? = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 12))) =
        Rule(
            name = "température",
            conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.LT, number = 5f)),
            actions = listOf(Action(ActionType.SET_STEERING_HEAT, number = 2)),
            elseIf = listOf(
                Branch(
                    conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.LT, number = 20f)),
                    actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
                )
            ),
            elseActions = elseActions
        )

    private fun atTemperature(celsius: Float) =
        Snapshot(readings = mapOf(BridgeContract.KEY_OUTSIDE_TEMP to celsius))

    @Test
    fun `le premier cas qui correspond gagne et les suivants sont ignores`() {
        val executor = RecordingExecutor()

        val result = run(byTemperature(), atTemperature(2f), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
        assertEquals(listOf(ActionType.SET_STEERING_HEAT), executor.executed)
        assertEquals(BRANCH_IF, result.firedBranch)
    }

    @Test
    fun `le sinon si prend le relais quand le si ne correspond pas`() {
        val executor = RecordingExecutor()

        val result = run(byTemperature(), atTemperature(12f), executor)

        assertEquals(listOf(ActionType.SET_FAN_LEVEL), executor.executed)
        assertEquals("le premier sinon si est le cas numéro 1", 1, result.firedBranch)
    }

    @Test
    fun `le sinon s execute quand aucun cas ne correspond`() {
        val executor = RecordingExecutor()

        val result = run(byTemperature(), atTemperature(25f), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
        assertEquals(listOf(ActionType.SET_MEDIA_VOLUME), executor.executed)
        assertEquals(BRANCH_ELSE, result.firedBranch)
    }

    @Test
    fun `sans sinon, aucun cas correspondant ne declenche rien`() {
        val executor = RecordingExecutor()

        val result = run(byTemperature(elseActions = null), atTemperature(25f), executor)

        assertEquals(RuleOutcome.NOT_MATCHED, result.outcome)
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun `une lecture manquante arrete la regle au lieu de tomber dans le sinon`() {
        // Le point de sûreté : illisible n'est pas faux. Enchaîner sur le cas suivant, ou
        // sur le sinon, écrirait dans la voiture PARCE QUE la valeur manque.
        val executor = RecordingExecutor()

        val result = run(byTemperature(), Snapshot(), executor)

        assertEquals(RuleOutcome.NOT_EVALUABLE, result.outcome)
        assertTrue("aucun cas ne doit s'exécuter", executor.executed.isEmpty())
        assertEquals(listOf(ConditionType.OUTSIDE_TEMP), result.unavailableConditions)
    }

    @Test
    fun `une lecture manquante dans un sinon si arrete aussi la regle`() {
        val executor = RecordingExecutor()
        val rule = Rule(
            name = "mixte",
            conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, op = CompareOp.LT, number = 5f)),
            actions = listOf(Action(ActionType.SET_STEERING_HEAT, number = 2)),
            elseIf = listOf(
                Branch(
                    conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
                    actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
                )
            ),
            elseActions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 12))
        )

        // Le premier cas se conclut (il fait 25 °C), le second n'est pas lisible.
        val result = run(rule, atTemperature(25f), executor)

        assertEquals(RuleOutcome.NOT_EVALUABLE, result.outcome)
        assertTrue(executor.executed.isEmpty())
        assertEquals(listOf(ConditionType.IN_PARK), result.unavailableConditions)
    }

    @Test
    fun `une regle sans autre cas ne nomme aucune branche`() {
        val executor = RecordingExecutor()
        val simple = rule(conditions = arrayOf(Condition(ConditionType.IN_PARK, flag = true)))

        val result = run(simple, Snapshot(readings = mapOf(BridgeContract.KEY_IN_PARK to true)), executor)

        assertEquals(RuleOutcome.FIRED, result.outcome)
        assertNull("rien à nommer quand il n'y a qu'un cas", result.firedBranch)
    }
}
