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

    @Test fun `unknown firmware hides nothing`() {
        val names = SupportChecker.supportedActionNames(null)
        assertTrue(names.containsAll(ActionType.entries.map { it.name }))
    }
}
