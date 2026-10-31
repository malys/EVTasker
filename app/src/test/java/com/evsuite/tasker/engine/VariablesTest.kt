package com.evsuite.tasker.engine

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.ActionResult
import com.evsuite.tasker.model.CompareOp
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.ConditionOutcome
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.RuleOutcome
import com.evsuite.tasker.model.Snapshot
import com.evsuite.hardware.catalog.SnapshotKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VariablesTest {

    private fun paris(date: String, minutes: Int = 720, offset: Int = 120) =
        Variables.sun(48.8566, 2.3522, true, date, minutes, offset)

    private fun assertNear(expected: Int, actual: Any?) {
        val value = (actual as Float).toInt()
        assertTrue("expected ~$expected, got $value", abs(expected - value) <= 3)
    }

    @Test
    fun `sun times match the published tables for Paris`() {
        // Published: 05:47 / 21:58 CEST at the June solstice, 08:42 / 16:56 CET in December.
        val june = paris("2026-06-21")
        assertNear(5 * 60 + 47, june[Variables.SUNRISE])
        assertNear(21 * 60 + 58, june[Variables.SUNSET])
        val december = paris("2026-12-21", offset = 60)
        assertNear(8 * 60 + 42, december[Variables.SUNRISE])
        assertNear(16 * 60 + 56, december[Variables.SUNSET])
        // Civil twilight brackets the sun's own crossing.
        assertTrue((june[Variables.DAWN] as Float) < (june[Variables.SUNRISE] as Float))
        assertTrue((june[Variables.DUSK] as Float) > (june[Variables.SUNSET] as Float))
    }

    @Test
    fun `night is between sunset and sunrise`() {
        assertEquals(false, paris("2026-06-21", minutes = 12 * 60)[Variables.NIGHT])
        assertEquals(true, paris("2026-06-21", minutes = 23 * 60)[Variables.NIGHT])
        assertEquals(true, paris("2026-06-21", minutes = 4 * 60)[Variables.NIGHT])
    }

    @Test
    fun `a polar day has no sunrise and still says whether it is night`() {
        val winter = Variables.sun(69.65, 18.96, true, "2026-12-21", 720, 60)
        assertNull(winter[Variables.SUNRISE])
        assertEquals(true, winter[Variables.NIGHT])
        val summer = Variables.sun(69.65, 18.96, true, "2026-06-21", 0, 120)
        assertNull(summer[Variables.SUNSET])
        assertEquals(false, summer[Variables.NIGHT])
    }

    @Test
    fun `no position or no clock computes nothing`() {
        assertTrue(Variables.sun(null, null, true, "2026-06-21", 720, 120).isEmpty())
        assertTrue(Variables.sun(48.8, 2.3, false, "2019-01-01", 720, 60).isEmpty())
    }

    @Test
    fun `computed names cannot be set and names ignore case`() {
        assertFalse(Variables.isSettable("sunset"))
        assertFalse(Variables.isSettable("  "))
        assertTrue(Variables.isSettable("cold"))
        assertEquals("COLD", Variables.normalize(" cold "))
    }

    // ---- Conditions ----

    private fun eval(condition: Condition, snapshot: Snapshot) = ConditionEvaluator.evaluate(condition, snapshot)

    @Test
    fun `time now compares against sunset plus an offset`() {
        val s = Snapshot(minutesOfDay = 20 * 60, variables = mapOf(Variables.SUNSET to 19 * 60f))
        val after = Condition(ConditionType.CURRENT_TIME, CompareOp.GT, 30f, ref = "sunset")
        assertEquals(ConditionOutcome.MATCH, eval(after, s))
        assertEquals(ConditionOutcome.NO_MATCH, eval(after.copy(number = 90f), s))
    }

    @Test
    fun `time comparisons wrap around midnight`() {
        val sunset = mapOf(Variables.SUNSET to 19 * 60f)
        val afterSunset = Condition(ConditionType.CURRENT_TIME, CompareOp.GT, ref = Variables.SUNSET)
        // Still night at 01:00: "after sunset" holds for the twelve hours that follow it.
        assertEquals(ConditionOutcome.MATCH, eval(afterSunset, Snapshot(minutesOfDay = 60, variables = sunset)))
        assertEquals(ConditionOutcome.NO_MATCH, eval(afterSunset, Snapshot(minutesOfDay = 12 * 60, variables = sunset)))
        // 19:00 + 300 min is 00:00, not a time that never comes.
        val late = afterSunset.copy(number = 300f)
        assertEquals(ConditionOutcome.MATCH, eval(late, Snapshot(minutesOfDay = 30, variables = sunset)))
        assertEquals(ConditionOutcome.NO_MATCH, eval(late, Snapshot(minutesOfDay = 23 * 60, variables = sunset)))
        // "Before sunrise" at 23:00 is the night before it.
        val beforeSunrise = Condition(ConditionType.CURRENT_TIME, CompareOp.LT, ref = Variables.SUNRISE)
        assertEquals(ConditionOutcome.MATCH,
            eval(beforeSunrise, Snapshot(minutesOfDay = 23 * 60, variables = mapOf(Variables.SUNRISE to 7 * 60f))))
    }

    @Test
    fun `clock comparison boundaries`() {
        assertTrue(ConditionEvaluator.compareClock(600f, 600f, CompareOp.EQ))
        assertTrue(ConditionEvaluator.compareClock(600f, 600f, CompareOp.GE))
        assertTrue(ConditionEvaluator.compareClock(600f, 600f, CompareOp.LE))
        assertFalse(ConditionEvaluator.compareClock(600f, 600f, CompareOp.GT))
        assertFalse(ConditionEvaluator.compareClock(600f, 600f, CompareOp.LT))
        assertTrue(ConditionEvaluator.compareClock(5f, 1435f, CompareOp.GT))
        assertTrue(ConditionEvaluator.compareClock(1435f, 5f, CompareOp.LT))
    }

    @Test
    fun `a reading compares against a variable`() {
        val s = Snapshot(
            readings = mapOf(SnapshotKeys.KEY_OUTSIDE_TEMP to 3f),
            variables = mapOf("LIMIT" to 5f)
        )
        val below = Condition(ConditionType.OUTSIDE_TEMP, CompareOp.LT, ref = "LIMIT")
        assertEquals(ConditionOutcome.MATCH, eval(below, s))
    }

    @Test
    fun `an unset variable is unavailable, never false or zero`() {
        val s = Snapshot(minutesOfDay = 600, readings = mapOf(SnapshotKeys.KEY_OUTSIDE_TEMP to 3f))
        assertEquals(ConditionOutcome.UNAVAILABLE,
            eval(Condition(ConditionType.CURRENT_TIME, CompareOp.GT, ref = Variables.SUNSET), s))
        assertEquals(ConditionOutcome.UNAVAILABLE,
            eval(Condition(ConditionType.OUTSIDE_TEMP, CompareOp.LT, ref = "LIMIT"), s))
        assertEquals(ConditionOutcome.UNAVAILABLE, eval(Condition(ConditionType.VARIABLE_FLAG, text = "COLD"), s))
    }

    @Test
    fun `time now is unavailable on the boot clock`() {
        val s = Snapshot(minutesOfDay = 600, clockSet = false)
        assertEquals(ConditionOutcome.UNAVAILABLE, eval(Condition(ConditionType.CURRENT_TIME, CompareOp.GT), s))
    }

    @Test
    fun `variable conditions read their own type only`() {
        val s = Snapshot(variables = mapOf("COLD" to true, "LIMIT" to 5f))
        assertEquals(ConditionOutcome.MATCH, eval(Condition(ConditionType.VARIABLE_FLAG, text = "cold", flag = true), s))
        assertEquals(ConditionOutcome.NO_MATCH, eval(Condition(ConditionType.VARIABLE_FLAG, text = "COLD", flag = false), s))
        assertEquals(ConditionOutcome.MATCH,
            eval(Condition(ConditionType.VARIABLE_NUMBER, CompareOp.GE, 5f, text = "LIMIT"), s))
        assertEquals(ConditionOutcome.UNAVAILABLE, eval(Condition(ConditionType.VARIABLE_FLAG, text = "LIMIT"), s))
        assertEquals(ConditionOutcome.UNAVAILABLE, eval(Condition(ConditionType.VARIABLE_NUMBER, text = "COLD"), s))
    }

    // ---- Engine ----

    private val executed = mutableListOf<ActionType>()
    private val executor = ActionExecutor { action ->
        executed += action.type
        ActionResult(action.type, true, BridgeContract.VERDICT_ALLOWED)
    }

    @Test
    fun `a variable set by one rule is read by the next one of the same cycle`() {
        val kept = mutableListOf<Triple<String, Any, Boolean>>()
        val setter = Rule(
            name = "cold",
            conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, CompareOp.LT, 10f)),
            actions = listOf(Action(ActionType.SET_VARIABLE_FLAG, text = "cold", flag = true, keep = true))
        )
        val reader = Rule(
            name = "heat",
            conditions = listOf(Condition(ConditionType.VARIABLE_FLAG, text = "COLD", flag = true)),
            actions = listOf(Action(ActionType.SET_FRONT_DEFROST, flag = true))
        )
        val run = RuleEngine(executor, onVariableSet = { n, v, k -> kept += Triple(n, v, k) })
            .run(listOf(setter, reader), Snapshot(readings = mapOf(SnapshotKeys.KEY_OUTSIDE_TEMP to 4f)), "TEST", 0L)

        assertEquals(listOf(RuleOutcome.FIRED, RuleOutcome.FIRED), run.ruleRuns.map { it.outcome })
        assertEquals(listOf(Triple("COLD", true as Any, true)), kept)
        // The engine sets it itself: nothing reaches the executor but the vehicle action.
        assertEquals(listOf(ActionType.SET_FRONT_DEFROST), executed)
    }

    @Test
    fun `a number variable stores the number and is forgotten unless kept`() {
        val kept = mutableListOf<Triple<String, Any, Boolean>>()
        val rule = Rule(
            name = "n",
            conditions = listOf(Condition(ConditionType.CURRENT_TIME, CompareOp.GE, 0f)),
            actions = listOf(Action(ActionType.SET_VARIABLE_NUMBER, text = "limit", number = 7))
        )
        RuleEngine(executor, onVariableSet = { n, v, k -> kept += Triple(n, v, k) })
            .run(listOf(rule), Snapshot(minutesOfDay = 60), "TEST", 0L)

        assertEquals(listOf(Triple("LIMIT", 7f as Any, false)), kept)
    }

    @Test
    fun `a computed variable cannot be overwritten by a rule`() {
        val rule = Rule(
            name = "n",
            conditions = listOf(Condition(ConditionType.CURRENT_TIME, CompareOp.GE, 0f)),
            actions = listOf(Action(ActionType.SET_VARIABLE_FLAG, text = "night", flag = false))
        )
        val result = RuleEngine(executor).run(listOf(rule), Snapshot(minutesOfDay = 60), "TEST", 0L)
            .ruleRuns.first().actionResults.first()

        assertFalse(result.ok)
        assertEquals(BridgeContract.VERDICT_UNSUPPORTED, result.verdict)
    }
}
