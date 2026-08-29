package com.evsuite.tasker.util

/**
 * Detects a confirmed transition from a non-P gear into P.
 *
 * The first readable sample only establishes a baseline: a service recreated while the car
 * is already parked must not run every park rule again. An unreadable sample changes nothing,
 * so a brief gap between a confirmed non-P state and P does not hide the real transition.
 */
class ParkTriggerDetector {
    private var lastKnownInPark: Boolean? = null

    fun sample(inPark: Boolean?): Boolean {
        if (inPark == null) return false
        val enteredPark = lastKnownInPark == false && inPark
        lastKnownInPark = inPark
        return enteredPark
    }

    fun reset() {
        lastKnownInPark = null
    }
}
