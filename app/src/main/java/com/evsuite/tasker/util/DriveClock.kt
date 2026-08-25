package com.evsuite.tasker.util

/**
 * When the current drive started, so a rule can ask how long it has lasted.
 *
 * Fed by the ignition transition the vehicle service already listens for, which is why the
 * duration costs no poll and no extra bind. It is deliberately in memory only: a duration
 * restored from disk after the app was killed mid-drive would be the age of a record, not
 * the length of a journey, and the rule reading it could not tell the two apart.
 *
 * Not cleared at ignition-off. The rules that run *at* switch-off are exactly the ones that
 * want the drive's length, and clearing it there would leave them with nothing to read.
 */
object DriveClock {

    @Volatile private var startedAtMs: Long? = null

    /** Called on the ignition-on transition. */
    fun start(nowMs: Long = System.currentTimeMillis()) {
        startedAtMs = nowMs
    }

    /**
     * Minutes since the drive began, or null when no transition has been seen yet.
     *
     * Null rather than 0: a service that started on a car already running does not know when
     * the drive began, and answering 0 would make "driving for more than 20 minutes" false
     * for the whole trip.
     */
    fun minutes(nowMs: Long = System.currentTimeMillis()): Int? {
        val started = startedAtMs ?: return null
        return ((nowMs - started).coerceAtLeast(0L) / 60_000L).toInt()
    }

    /** Test seam. */
    fun reset() {
        startedAtMs = null
    }
}
