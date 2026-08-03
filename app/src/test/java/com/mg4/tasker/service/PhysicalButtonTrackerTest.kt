package com.mg4.tasker.service

import com.mg4.hardware.catalog.SnapshotKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalButtonTrackerTest {
    @Test fun `left star short press fires on release`() {
        val tracker = PhysicalButtonTracker()
        assertNull(tracker.accept(17, down = true, longPress = false))
        val event = tracker.accept(17, down = false, longPress = false)!!
        assertEquals(PhysicalButtonTracker.Press.SHORT, event.press)
        assertTrue(event.readings()[SnapshotKeys.KEY_STAR_LEFT_SHORT] == true)
    }

    @Test fun `long press fires once and suppresses short release`() {
        val tracker = PhysicalButtonTracker()
        assertNull(tracker.accept(17, down = true, longPress = false))
        assertEquals(
            PhysicalButtonTracker.Press.LONG,
            tracker.accept(17, down = true, longPress = true)?.press
        )
        assertNull(tracker.accept(17, down = true, longPress = true))
        assertNull(tracker.accept(17, down = false, longPress = false))
    }

    @Test fun `both right star firmware codes are accepted`() {
        for (code in listOf(286, 18)) {
            val tracker = PhysicalButtonTracker()
            tracker.accept(code, down = true, longPress = false)
            assertEquals(
                PhysicalButtonTracker.Button.STAR_RIGHT,
                tracker.accept(code, down = false, longPress = false)?.button
            )
        }
    }

    @Test fun `unknown and orphan release events are ignored`() {
        val tracker = PhysicalButtonTracker()
        assertNull(tracker.accept(999, down = true, longPress = false))
        assertNull(tracker.accept(17, down = false, longPress = false))
    }
}
