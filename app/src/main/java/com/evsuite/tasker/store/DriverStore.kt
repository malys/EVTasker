package com.evsuite.tasker.store

import android.content.Context
import com.evsuite.hardware.DriverContract

/**
 * Who a rule said is driving this ignition cycle (CP-098), and when it said so.
 *
 * Published to the suite's other apps by [DriverProvider]; never a vehicle setting. Cleared
 * at every ignition with the cycle variables, so a drive with no matching rule reads as no
 * driver rather than as whoever drove last.
 *
 * commit(), not apply(): the head unit powers off seconds after the car is parked.
 */
object DriverStore {

    private const val PREFS = "ev_tasker_driver"
    private const val KEY_NAME = "name"
    private const val KEY_SET_AT = "set_at_ms"

    data class Driver(val name: String, val setAtMs: Long)

    /** Stores [raw] once normalised; false when the name is not a valid one. */
    fun set(context: Context, raw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val name = DriverContract.normalizeName(raw) ?: return false
        prefs(context).edit().putString(KEY_NAME, name).putLong(KEY_SET_AT, nowMs).commit()
        return true
    }

    fun current(context: Context): Driver? {
        val prefs = prefs(context)
        val name = DriverContract.normalizeName(prefs.getString(KEY_NAME, null)) ?: return null
        return Driver(name, prefs.getLong(KEY_SET_AT, 0L))
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
