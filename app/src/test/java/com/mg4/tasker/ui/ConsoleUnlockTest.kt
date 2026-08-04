package com.mg4.tasker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleUnlockTest {

    @Test
    fun `console unlocks on third diagnostic tap`() {
        val unlock = ConsoleUnlock()

        assertFalse(unlock.onDiagnosticTap())
        assertFalse(unlock.onDiagnosticTap())
        assertTrue(unlock.onDiagnosticTap())
    }

    @Test
    fun `console stays unlocked after threshold`() {
        val unlock = ConsoleUnlock()

        repeat(3) { unlock.onDiagnosticTap() }

        assertTrue(unlock.onDiagnosticTap())
    }
}
