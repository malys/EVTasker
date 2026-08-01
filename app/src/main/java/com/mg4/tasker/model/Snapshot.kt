package com.mg4.tasker.model

/**
 * Vehicle and context state at evaluation time.
 *
 * [readings] is keyed by the bridge snapshot keys. A MISSING key means "unreadable" —
 * never "zero". The whole engine rests on that distinction: it is what stops a
 * "temperature > 10" rule from firing on a firmware that does not expose temperature.
 */
data class Snapshot(
    val readings: Map<String, Any> = emptyMap(),
    /** MAC addresses of Bluetooth devices currently connected to the vehicle. */
    val btMacs: Set<String> = emptySet(),
    /**
     * Whether [btMacs] means anything: false when the radio is off or unreadable, and the
     * Bluetooth conditions are then unavailable rather than "nothing connected".
     *
     * Separate from [bridgeAvailable] on purpose — the Bluetooth context conditions are
     * firmware-independent, and a vehicle layer that is not ready says nothing about which
     * phone is paired to the head unit.
     */
    val btAvailable: Boolean = true,
    /** Minutes since midnight, local time. */
    val minutesOfDay: Int = 0,
    /** Current day, java.util.Calendar.MONDAY…SUNDAY values. */
    val dayOfWeek: Int = 0,
    /** false when MG4Control did not answer: everything is unreadable, nothing fires. */
    val bridgeAvailable: Boolean = true
) {
    fun number(key: String): Float? = when (val v = readings[key]) {
        is Float -> v
        is Int   -> v.toFloat()
        else     -> null
    }

    fun int(key: String): Int? = when (val v = readings[key]) {
        is Int   -> v
        is Float -> v.toInt()
        else     -> null
    }

    fun bool(key: String): Boolean? = readings[key] as? Boolean

    fun string(key: String): String? = readings[key] as? String
}
