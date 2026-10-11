package com.evsuite.tasker.util

/**
 * Whether the head unit's clock tells the time yet.
 *
 * The head unit boots on its RTC default, 2019-01-01, and the ignition cycle can run before
 * the network or GPS corrects it. Until then the time of day, the weekday and the date are
 * fiction, and a rule that reads them would fire on a day that is not today.
 */
object WallClock {

    // ponytail: fixed floor (2026-01-01 UTC), earlier than any build of this app; a clock
    // behind it is unset. Move to the build time if a later RTC default ever shows up.
    const val FLOOR_MS = 1_767_225_600_000L

    fun isSet(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= FLOOR_MS
}
