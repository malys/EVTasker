package com.mg4.tasker.vehicle

import com.mg4.hardware.catalog.ActionType
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.Condition
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.RuleRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What gets a second chance and what does not.
 *
 * The distinction is the whole feature: a write the gate held back can succeed later, and a
 * write the firmware does not support cannot. Keeping the second would mean the app quietly
 * retrying, at every red light, something that will never work.
 */
class DeferredWritesTest {

    private val driveMode = Action(ActionType.SET_DRIVE_MODE, number = 1)
    private val profile = Action(ActionType.APPLY_PROFILE, text = "p1")

    private fun rule(vararg actions: Action) = Rule(
        id = "r1",
        name = "Eco on the motorway",
        conditions = listOf(Condition(ConditionType.SPEED)),
        actions = actions.toList()
    )

    private fun cycle(vararg results: ActionResult) = EngineRun(
        timestamp = 0L,
        trigger = "IGNITION_ON",
        bridgeAvailable = false,
        ruleRuns = listOf(RuleRun("r1", "Eco on the motorway", RuleOutcome.FIRED, results.toList()))
    )

    @Test
    fun `a refusal for movement is kept`() {
        val kept = DeferredWrites.refusalsIn(
            cycle(ActionResult(ActionType.SET_DRIVE_MODE, false, BridgeContract.VERDICT_MOVING)),
            listOf(rule(driveMode)),
            now = 1_000L
        )
        assertEquals(1, kept.size)
        assertEquals(ActionType.SET_DRIVE_MODE, kept.first().action.type)
        // The rule's name travels with it, so the later history line can say whose write it was.
        assertEquals("Eco on the motorway", kept.first().ruleName)
        assertEquals(1_000L, kept.first().queuedAt)
    }

    @Test
    fun `a refusal for an unreadable speed is kept too`() {
        // Same nature: the car did not say no, it said nothing.
        val kept = DeferredWrites.refusalsIn(
            cycle(ActionResult(ActionType.SET_DRIVE_MODE, false, BridgeContract.VERDICT_UNKNOWN_SPEED)),
            listOf(rule(driveMode)),
            now = 0L
        )
        assertEquals(1, kept.size)
    }

    @Test
    fun `anything that is not a gate refusal is dropped`() {
        val kept = DeferredWrites.refusalsIn(
            cycle(
                ActionResult(ActionType.SET_DRIVE_MODE, true, BridgeContract.VERDICT_ALLOWED),
                ActionResult(ActionType.SET_REGEN_LEVEL, false, BridgeContract.VERDICT_UNSUPPORTED),
                ActionResult(ActionType.SET_ONE_PEDAL, false, BridgeContract.VERDICT_ERROR),
                ActionResult(ActionType.SET_AEB_ENABLED, false, BridgeContract.VERDICT_NO_BRIDGE),
            ),
            listOf(rule(driveMode)),
            now = 0L
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `a profile is never deferred`() {
        // It needs a live MG4Control bind that belonged to the cycle now finished.
        val kept = DeferredWrites.refusalsIn(
            cycle(ActionResult(ActionType.APPLY_PROFILE, false, BridgeContract.VERDICT_MOVING)),
            listOf(rule(profile)),
            now = 0L
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `a result whose rule has since been deleted is dropped`() {
        // The history keeps runs for rules that no longer exist; the queue must not resurrect
        // an action from one of them.
        val kept = DeferredWrites.refusalsIn(
            cycle(ActionResult(ActionType.SET_DRIVE_MODE, false, BridgeContract.VERDICT_MOVING)),
            rules = emptyList(),
            now = 0L
        )
        assertTrue(kept.isEmpty())
    }
}
