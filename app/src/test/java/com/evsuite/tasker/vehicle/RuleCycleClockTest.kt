package com.evsuite.tasker.vehicle

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCycleClockTest {

    @Test fun `a clock condition in any case holds the rule back`() {
        val outside = Condition(ConditionType.OUTSIDE_TEMP, number = 10f)
        assertFalse(RuleCycle.readsClock(Rule(name = "r", conditions = listOf(outside))))
        assertTrue(RuleCycle.readsClock(Rule(name = "r", conditions = listOf(Condition(ConditionType.DAY_OF_WEEK)))))
        // The "else if" is where it is easy to miss.
        assertTrue(
            RuleCycle.readsClock(
                Rule(
                    name = "r",
                    conditions = listOf(outside),
                    elseIf = listOf(Branch(conditions = listOf(Condition(ConditionType.TIME_OF_DAY))))
                )
            )
        )
    }
}
