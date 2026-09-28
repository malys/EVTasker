package com.evsuite.tasker.util

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.RuleTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTriggerDetectorTest {

    private val home = "48.858370,2.294481"
    private val atHome = CarLocation.Fix(48.858370, 2.294481)
    private val away = CarLocation.Fix(48.873800, 2.295000) // ~1.7 km north

    private fun rule(inside: Boolean = true, id: String = "r", point: String = home) = Rule(
        id = id, name = id, trigger = RuleTrigger.LOCATION,
        conditions = listOf(Condition(ConditionType.LOCATION_WITHIN, text = point, number = 200f, flag = inside)),
        actions = listOf(Action(ActionType.SHOW_NOTIFICATION, text = "hi"))
    )

    @Test
    fun `starting inside the zone is a baseline, not an arrival`() {
        val d = LocationTriggerDetector()
        assertEquals(emptyList<String>(), d.sample(listOf(rule()), atHome))
        assertEquals(emptyList<String>(), d.sample(listOf(rule()), atHome))
    }

    @Test
    fun `arriving fires once, and again only after leaving`() {
        val d = LocationTriggerDetector()
        val rules = listOf(rule())
        d.sample(rules, away)
        assertEquals(listOf("r"), d.sample(rules, atHome))
        assertEquals(emptyList<String>(), d.sample(rules, atHome))
        d.sample(rules, away)
        assertEquals(listOf("r"), d.sample(rules, atHome))
    }

    @Test
    fun `a not-near condition fires on leaving`() {
        val d = LocationTriggerDetector()
        val rules = listOf(rule(inside = false))
        d.sample(rules, atHome)
        assertEquals(listOf("r"), d.sample(rules, away))
    }

    @Test
    fun `no fix neither fabricates nor hides a transition`() {
        val d = LocationTriggerDetector()
        val rules = listOf(rule())
        assertTrue(d.sample(rules, null).isEmpty())
        d.sample(rules, away)
        assertTrue(d.sample(rules, null).isEmpty())
        assertEquals(listOf("r"), d.sample(rules, atHome))
    }

    @Test
    fun `only the rule whose place was reached fires`() {
        val d = LocationTriggerDetector()
        val rules = listOf(rule(id = "home"), rule(id = "elsewhere", point = "45.0,5.0"))
        d.sample(rules, away)
        assertEquals(listOf("home"), d.sample(rules, atHome))
    }

    @Test
    fun `moving the place starts a new baseline`() {
        val d = LocationTriggerDetector()
        d.sample(listOf(rule(point = "45.0,5.0")), atHome)
        assertTrue(d.sample(listOf(rule()), atHome).isEmpty())
    }

    @Test
    fun `reset requires a new outside baseline`() {
        val d = LocationTriggerDetector()
        d.sample(listOf(rule()), away)
        d.reset()
        assertFalse(d.sample(listOf(rule()), atHome).isNotEmpty())
    }
}
