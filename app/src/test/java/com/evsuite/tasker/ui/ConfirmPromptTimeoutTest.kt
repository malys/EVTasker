package com.evsuite.tasker.ui

import com.evsuite.hardware.catalog.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmPromptTimeoutTest {

    private val spec = ActionType.ASK_CONFIRM.spec

    @Test
    fun `a rule saved before the wait was configurable gets the default, not the floor`() {
        // 0 is the model default: the field the rule never set. Clamping it into the range
        // would silently shorten every prompt written before the slider existed.
        assertEquals(
            ActionType.ASK_CONFIRM_DEFAULT_SECONDS * 1_000L,
            ConfirmPrompt.timeoutMsFor(0)
        )
        assertEquals(ConfirmPrompt.TIMEOUT_MS, ConfirmPrompt.timeoutMsFor(0))
    }

    @Test
    fun `the wait the rule asks for is the wait it gets`() {
        assertEquals(25_000L, ConfirmPrompt.timeoutMsFor(25))
    }

    @Test
    fun `a value outside the catalogue bounds is brought back inside them`() {
        // An imported rule carries whatever the file says; the editor's bounds are not a
        // guarantee about the JSON.
        assertEquals(spec.min * 1_000L, ConfirmPrompt.timeoutMsFor(1))
        assertEquals(spec.max * 1_000L, ConfirmPrompt.timeoutMsFor(9_999))
        // Negative is not a shorter wait than none — it is still no value.
        assertEquals(ConfirmPrompt.TIMEOUT_MS, ConfirmPrompt.timeoutMsFor(-5))
    }
}
