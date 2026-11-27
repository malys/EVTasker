package com.evsuite.tasker.vehicle

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.vehicle.RuleCycle.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCycleClockTest {

    @Test fun `a clock condition in any case holds the rule back`() {
        val outside = Condition(ConditionType.OUTSIDE_TEMP, number = 10f)
        assertEquals(emptySet<Input>(), RuleCycle.inputsRead(Rule(name = "r", conditions = listOf(outside))))
        assertEquals(
            setOf(Input.CLOCK),
            RuleCycle.inputsRead(Rule(name = "r", conditions = listOf(Condition(ConditionType.DAY_OF_WEEK)))),
        )
        // The "else if" is where it is easy to miss.
        assertEquals(
            setOf(Input.CLOCK),
            RuleCycle.inputsRead(
                Rule(
                    name = "r",
                    conditions = listOf(outside),
                    elseIf = listOf(Branch(conditions = listOf(Condition(ConditionType.TIME_OF_DAY))))
                )
            )
        )
    }

    @Test fun `a phone or a place read at boot is an input to wait for`() {
        val profile = Rule(
            name = "Profil",
            conditions = listOf(Condition(ConditionType.BT_DEVICE_CONNECTED, text = "88:46:04:6F:D1:6A")),
            elseIf = listOf(Branch(conditions = listOf(Condition(ConditionType.BT_DEVICE_HANDSFREE)))),
        )
        val gate = Rule(
            name = "Portail",
            conditions = listOf(
                Condition(ConditionType.LOCATION_WITHIN, number = 50f, text = "43.5,1.5"),
                Condition(ConditionType.PHYSICAL_BUTTON, number = 17f, text = "LONG"),
            ),
        )
        assertEquals(setOf(Input.BLUETOOTH), RuleCycle.inputsRead(profile))
        // Two named phones: the ranking is an input too (CP-100).
        val twoPhones = profile.copy(
            elseIf = listOf(Branch(conditions = listOf(Condition(ConditionType.BT_DEVICE_HANDSFREE, text = "a4:e2:87:cf:72:3b")))),
        )
        assertEquals(setOf(Input.BLUETOOTH, Input.PHONE_PRIORITY), RuleCycle.inputsRead(twoPhones))
        assertEquals(setOf(Input.LOCATION), RuleCycle.inputsRead(gate))
    }

    @Test fun `a weather condition in any case puts the rule in the late pass`() {
        assertFalse(RuleCycle.readsWeather(Rule(name = "cold", conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, number = 10f)))))
        assertTrue(
            RuleCycle.readsWeather(
                Rule(
                    name = "sky",
                    conditions = listOf(Condition(ConditionType.DAY_OF_WEEK)),
                    elseIf = listOf(Branch(conditions = listOf(Condition(ConditionType.WEATHER_NOW, number = 1f))))
                )
            )
        )
    }

    @Test fun `what every case of the profile rule does runs ahead, and only once`() {
        val regen = Action(ActionType.SET_REGEN_LEVEL, number = 2, payload = "")
        fun driver(name: String) = Action(ActionType.SET_DRIVER, text = name, payload = "")
        val profile = Rule(
            name = "Profil",
            actions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 6), regen, driver("Cris")),
            elseIf = listOf(Branch(actions = listOf(regen, Action(ActionType.SET_AEB_ENABLED, flag = false), driver("Syl")))),
            elseActions = listOf(driver("Cris"), regen),
        )
        assertEquals(listOf(regen), RuleCycle.commonActions(profile))
        val late = RuleCycle.without(profile, listOf(regen))
        assertFalse((late.branches.flatMap { it.actions } + late.otherwise).contains(regen))
        assertEquals(listOf(driver("Cris")), late.otherwise)
        // No "else": a rule matching nothing runs nothing, so nothing may run ahead.
        assertEquals(emptyList<Action>(), RuleCycle.commonActions(profile.copy(elseActions = null)))
        val wait = Action(ActionType.DELAY, number = 5)
        assertEquals(emptyList<Action>(), RuleCycle.commonActions(Rule(name = "w", actions = listOf(wait), elseActions = listOf(wait))))
    }

    @Test fun `EVProfile is bound only for a pass that has a profile action, else included`() {
        val beep = Action(ActionType.entries.first { it != ActionType.APPLY_PROFILE && it != ActionType.SHOW_PROFILE_PICKER })
        assertFalse(RuleCycle.usesProfileBridge(Rule(name = "r", actions = listOf(beep))))
        assertTrue(RuleCycle.usesProfileBridge(Rule(name = "r", actions = listOf(Action(ActionType.APPLY_PROFILE)))))
        assertTrue(
            RuleCycle.usesProfileBridge(
                Rule(name = "r", actions = listOf(beep), elseActions = listOf(Action(ActionType.SHOW_PROFILE_PICKER)))
            )
        )
    }

    @Test fun `phones rank by the rule's cases, and only a lower phone alone waits for a higher one`() {
        val cris = "88:46:04:6F:D1:6A"
        val syl = "A4:E2:87:CF:72:3B"
        val rule = Rule(
            name = "Profil",
            conditions = listOf(
                Condition(ConditionType.BT_DEVICE_HANDSFREE, text = cris),
                Condition(ConditionType.BT_DEVICE_CONNECTED, text = cris),
            ),
            elseIf = listOf(
                Branch(conditions = listOf(Condition(ConditionType.BT_DEVICE_CONNECTED, text = syl.lowercase()))),
                // "Not connected" names no phone to wait for.
                Branch(conditions = listOf(Condition(ConditionType.BT_DEVICE_CONNECTED, flag = false, text = "11:22:33:44:55:66"))),
            ),
        )
        val ranking = RuleCycle.phonePriority(rule)
        assertEquals(listOf(cris, syl), ranking)
        assertTrue(RuleCycle.higherPhoneMissing(ranking, setOf(syl)))
        assertFalse(RuleCycle.higherPhoneMissing(ranking, setOf(cris)))
        assertFalse(RuleCycle.higherPhoneMissing(ranking, setOf(cris, syl)))
        assertFalse(RuleCycle.higherPhoneMissing(ranking, emptySet()))
    }
}
