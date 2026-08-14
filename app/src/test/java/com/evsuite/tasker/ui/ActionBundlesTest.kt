package com.evsuite.tasker.ui

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.model.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionBundlesTest {
    @Test
    fun `tune radio expands to tune wait then play`() {
        val actions = ActionBundles.expand(Action(type = ActionType.TUNE_RADIO, text = "103.5"))

        assertEquals(
            listOf(ActionType.TUNE_RADIO, ActionType.DELAY, ActionType.PLAY_RADIO),
            actions.map { it.type }
        )
        assertEquals(1, actions[1].number)
        assertEquals("103.5", actions[0].text)
    }

    @Test
    fun `ordinary action stays single`() {
        val action = Action(type = ActionType.SET_DRIVE_MODE, number = 2)
        assertEquals(listOf(action), ActionBundles.expand(action))
    }
}
