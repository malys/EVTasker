package com.mg4.tasker.model

import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTest {

    private val condition = Condition(ConditionType.IN_PARK, flag = true)

    @Test
    fun `une regle avec une condition et une action est complete`() {
        val rule = Rule(
            name = "ok",
            conditions = listOf(condition),
            actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 3))
        )

        assertTrue(rule.isComplete())
    }

    @Test
    fun `une regle faite uniquement d attentes ne fait rien et est refusee`() {
        val rule = Rule(
            name = "attentes",
            conditions = listOf(condition),
            actions = listOf(Action(ActionType.DELAY, number = 5), Action(ActionType.DELAY, number = 10))
        )

        assertFalse(rule.isComplete())
    }

    @Test
    fun `une attente accompagnee d une vraie action reste complete`() {
        val rule = Rule(
            name = "attente + action",
            conditions = listOf(condition),
            actions = listOf(
                Action(ActionType.SET_CLIMATE_POWER, flag = true),
                Action(ActionType.DELAY, number = 5),
                Action(ActionType.SET_FAN_LEVEL, number = 3)
            )
        )

        assertTrue(rule.isComplete())
    }

    @Test
    fun `le total des attentes est celui compare au budget du cycle`() {
        val rule = Rule(
            name = "attentes",
            conditions = listOf(condition),
            actions = listOf(
                Action(ActionType.DELAY, number = 30),
                Action(ActionType.SET_FAN_LEVEL, number = 3),
                // Hors bornes : bornée comme à l'exécution, sinon l'éditeur et le moteur
                // ne compteraient pas la même chose.
                Action(ActionType.DELAY, number = 600)
            )
        )

        assertEquals(90_000L, rule.totalDelayMs)
        assertFalse(rule.totalDelayMs > DELAY_BUDGET_MS)
    }

    // ---------------------------------------------------------------- branches

    private val otherCondition = Condition(ConditionType.OUTSIDE_TEMP, number = 5f)

    private fun branched(
        elseIf: List<Branch>? = listOf(
            Branch(
                conditions = listOf(otherCondition),
                actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
            )
        ),
        elseActions: List<Action>? = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 8))
    ) = Rule(
        name = "cases",
        conditions = listOf(condition),
        actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 3)),
        elseIf = elseIf,
        elseActions = elseActions
    )

    @Test
    fun `une regle a cas multiples complets est complete`() {
        assertTrue(branched().isComplete())
    }

    @Test
    fun `un sinon si sans condition est refuse`() {
        // Sans condition ce n'est plus un cas : il avale tous ceux écrits après lui.
        val rule = branched(
            elseIf = listOf(Branch(actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))))
        )

        assertFalse(rule.isComplete())
    }

    @Test
    fun `un sinon si sans action est refuse`() {
        val rule = branched(elseIf = listOf(Branch(conditions = listOf(otherCondition))))

        assertFalse(rule.isComplete())
    }

    @Test
    fun `un sinon fait uniquement d attentes est refuse`() {
        val rule = branched(elseActions = listOf(Action(ActionType.DELAY, number = 5)))

        assertFalse(rule.isComplete())
    }

    @Test
    fun `l absence de sinon reste complete`() {
        assertTrue(branched(elseActions = null).isComplete())
    }

    @Test
    fun `le budget compare le cas le plus long, pas la somme des cas`() {
        // Un seul cas s'exécute : additionner les branches refuserait une règle qui ne peut
        // jamais attendre aussi longtemps.
        val rule = Rule(
            name = "attentes par cas",
            conditions = listOf(condition),
            actions = listOf(
                Action(ActionType.DELAY, number = 30),
                Action(ActionType.SET_FAN_LEVEL, number = 3)
            ),
            elseIf = listOf(
                Branch(
                    conditions = listOf(otherCondition),
                    actions = listOf(
                        Action(ActionType.DELAY, number = 60),
                        Action(ActionType.DELAY, number = 50),
                        Action(ActionType.SET_FAN_LEVEL, number = 1)
                    )
                )
            ),
            elseActions = listOf(
                Action(ActionType.DELAY, number = 10),
                Action(ActionType.SET_MEDIA_VOLUME, number = 8)
            )
        )

        assertEquals(110_000L, rule.totalDelayMs)
    }

    @Test
    fun `un bouton nomme dans un sinon si adresse quand meme la regle`() {
        val rule = branched(
            elseIf = listOf(
                Branch(
                    conditions = listOf(Condition(ConditionType.PHYSICAL_BUTTON)),
                    actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
                )
            )
        )

        assertTrue(rule.hasPhysicalButtonCondition)
    }

    private val buttonCondition = Condition(ConditionType.PHYSICAL_BUTTON)

    private fun buttonCase(vararg conditions: Condition) = Branch(
        conditions = conditions.toList(),
        actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
    )

    @Test
    fun `une regle bouton dont chaque cas nomme le bouton est saine`() {
        val rule = Rule(
            name = "bouton",
            conditions = listOf(buttonCondition),
            actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 3)),
            elseIf = listOf(buttonCase(buttonCondition, otherCondition))
        )

        assertTrue(rule.buttonAddressingIsSound)
    }

    @Test
    fun `un cas sans bouton dans une regle bouton est refuse`() {
        // Le bouton adresse la règle entière : ce cas s'évaluerait sur des appuis qu'il ne
        // mentionne pas.
        val rule = Rule(
            name = "bouton mixte",
            conditions = listOf(buttonCondition),
            actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 3)),
            elseIf = listOf(buttonCase(otherCondition))
        )

        assertFalse(rule.buttonAddressingIsSound)
    }

    @Test
    fun `un sinon dans une regle bouton est refuse`() {
        // Le « sinon » ne teste rien : il s'exécuterait à chaque appui de n'importe quel bouton.
        val rule = Rule(
            name = "bouton + sinon",
            conditions = listOf(buttonCondition),
            actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 3)),
            elseActions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 8))
        )

        assertFalse(rule.buttonAddressingIsSound)
    }

    @Test
    fun `une regle sans bouton n est pas concernee`() {
        assertTrue(branched().buttonAddressingIsSound)
    }
}
