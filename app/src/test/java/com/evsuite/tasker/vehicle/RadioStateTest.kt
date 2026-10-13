package com.evsuite.tasker.vehicle

import com.evsuite.hardware.saic.SaicRadio
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioStateTest {

    @Test
    fun `a reading names band, frequency and play state`() {
        assertEquals("FM 101500 kHz playing", RadioState.describe(SaicRadio.Tuned(SaicRadio.BAND_FM, 101500, true)))
        assertEquals("DAB 174928 kHz paused", RadioState.describe(SaicRadio.Tuned(SaicRadio.BAND_DAB, 174928, false)))
        assertEquals("band 9 1 kHz paused", RadioState.describe(SaicRadio.Tuned(9, 1, false)))
        assertEquals("unreadable", RadioState.describe(null))
    }

    @Test
    fun `the pair follows the action's own detail`() {
        assertEquals("101500 kHz; before=a after=b", RadioState.detail("101500 kHz", "a", "b"))
        assertEquals("before=a after=b", RadioState.detail(null, "a", "b"))
    }
}
