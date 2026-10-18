package com.evsuite.tasker.vehicle

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.vehicle.RuleCycle.Input
import org.junit.Assert.assertEquals
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
        assertEquals(setOf(Input.LOCATION), RuleCycle.inputsRead(gate))
    }
}
