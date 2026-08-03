package com.mg4.tasker.model

import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
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
}
