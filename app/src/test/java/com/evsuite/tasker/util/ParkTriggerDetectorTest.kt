package com.evsuite.tasker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkTriggerDetectorTest {

    @Test
    fun `an initial parked sample establishes a baseline without firing`() {
        val detector = ParkTriggerDetector()

        assertFalse(detector.sample(true))
        assertFalse(detector.sample(true))
    }

    @Test
    fun `a confirmed transition from non-park to park fires once`() {
        val detector = ParkTriggerDetector()

        assertFalse(detector.sample(false))
        assertTrue(detector.sample(true))
        assertFalse(detector.sample(true))
        assertFalse(detector.sample(false))
        assertTrue(detector.sample(true))
    }

    @Test
    fun `an unreadable sample does not fabricate or hide a transition`() {
        val detector = ParkTriggerDetector()

        assertFalse(detector.sample(null))
        assertFalse(detector.sample(false))
        assertFalse(detector.sample(null))
        assertTrue(detector.sample(true))
    }

    @Test
    fun `reset requires a new non-park baseline`() {
        val detector = ParkTriggerDetector()
        detector.sample(false)
        detector.reset()

        assertFalse(detector.sample(true))
    }
}
