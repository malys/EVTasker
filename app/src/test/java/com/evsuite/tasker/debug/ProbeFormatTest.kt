package com.evsuite.tasker.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFormatTest {

    @After
    fun clear() {
        SurfaceCaptures.before = null
        SurfaceCaptures.after = null
    }

    @Test
    fun `OEM screens are listed per activity and capped`() {
        val screens = mapOf(
            "com.saicmotor.radio" to listOf("com.saicmotor.radio.MainActivity", "other.Act"),
            "com.saicmotor.empty" to emptyList(),
        )
        assertEquals(
            "com.saicmotor.radio/.MainActivity\ncom.saicmotor.radio/other.Act\ncom.saicmotor.empty: no exported activity",
            OemScreens.format(screens)
        )
        assertEquals(
            "com.saicmotor.radio/.MainActivity\n… 2 more not listed (cap 1)",
            OemScreens.format(screens, cap = 1)
        )
        assertEquals("no com.saicmotor.* package", OemScreens.format(emptyMap()))
    }

    @Test
    fun `the surface section needs both captures for a diff`() {
        assertNull(SurfaceCaptures.report())
        SurfaceCaptures.before = mapOf("a" to "1", "b" to "2")
        assertEquals("only the before capture was taken", SurfaceCaptures.report())
        SurfaceCaptures.after = mapOf("a" to "1", "b" to "3", "c" to "4")
        val report = SurfaceCaptures.report()!!
        assertTrue(report, report.startsWith("Values read: 2 before, 3 after — 2 changed"))
        assertTrue(report, "  b: 2 → 3" in report)
        assertTrue(report, "  c: — → 4" in report)
    }
}
