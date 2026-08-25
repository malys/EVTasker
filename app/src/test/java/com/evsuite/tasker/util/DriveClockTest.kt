package com.evsuite.tasker.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clock behind the "drive duration" condition.
 *
 * What is worth guarding is the null: the whole condition rests on "not knowing when the
 * drive started" being different from "it started now".
 */
class DriveClockTest {

    @After
    fun tearDown() = DriveClock.reset()

    @Test
    fun `no ignition seen yet means unknown, not zero`() {
        DriveClock.reset()
        assertNull(DriveClock.minutes(nowMs = 10_000L))
    }

    @Test
    fun `minutes count from the ignition transition`() {
        DriveClock.start(nowMs = 0L)
        assertEquals(0, DriveClock.minutes(nowMs = 59_999L))
        assertEquals(1, DriveClock.minutes(nowMs = 60_000L))
        assertEquals(90, DriveClock.minutes(nowMs = 90 * 60_000L))
    }

    @Test
    fun `a clock that jumps backwards reports zero rather than a negative drive`() {
        // The head unit sets its clock from the network once it wakes; a drive that started
        // "in the future" must not make every duration comparison come out true.
        DriveClock.start(nowMs = 10 * 60_000L)
        assertEquals(0, DriveClock.minutes(nowMs = 0L))
    }

    @Test
    fun `a new drive restarts the count`() {
        DriveClock.start(nowMs = 0L)
        DriveClock.start(nowMs = 60 * 60_000L)
        assertEquals(0, DriveClock.minutes(nowMs = 60 * 60_000L))
    }
}
