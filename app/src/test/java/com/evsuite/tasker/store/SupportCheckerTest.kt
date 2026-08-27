package com.evsuite.tasker.store

import com.evsuite.hardware.FirmwareGen
import com.evsuite.hardware.catalog.ActionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored support set must match the EVHardware firmware matrix. These cover the pure
 * computation ([SupportChecker.supportedActionNames]); the persistence around it is thin
 * SharedPreferences plumbing.
 */
class SupportCheckerTest {

    // SET_SEAT_HEAT_LEFT is @SupportedOn(SWI133, SWI68, SWI165): a discriminating entry.
    private val heatOnly = ActionType.SET_SEAT_HEAT_LEFT.name

    // LAUNCH_APP has no @SupportedOn: firmware-independent, always offered.
    private val independent = ActionType.LAUNCH_APP.name

    @Test fun `restricted action hidden on unlisted firmware`() {
        val names = SupportChecker.supportedActionNames(FirmwareGen.SWI69)
        assertFalse(heatOnly in names)
        assertTrue(independent in names)
    }

    @Test fun `restricted action shown on listed firmware`() {
        val names = SupportChecker.supportedActionNames(FirmwareGen.SWI68)
        assertTrue(heatOnly in names)
        assertTrue(independent in names)
    }

    @Test fun `unknown firmware hides nothing the matrix could answer for`() {
        val names = SupportChecker.supportedActionNames(null)
        assertTrue(names.containsAll(ActionType.entries.filter { it.writeProven }.map { it.name }))
    }

    /**
     * An unproven write is not a firmware question, so the matrix cannot hide it — and an
     * unknown generation makes the matrix hide nothing at all. Without this filter the glass
     * actions would be offered on precisely the cars nothing is known about.
     */
    @Test fun `an unproven write is never offered, whatever the firmware`() {
        val unproven = ActionType.entries.filter { !it.writeProven }.map { it.name }
        assertTrue("the glass is the case this exists for", ActionType.SET_WINDOWS.name in unproven)

        listOf(FirmwareGen.SWI68, FirmwareGen.SWI165, FirmwareGen.SWI69, null).forEach { gen ->
            val names = SupportChecker.supportedActionNames(gen)
            unproven.forEach { assertFalse("$it offered on $gen", it in names) }
        }
    }
}
