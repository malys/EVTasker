package com.evsuite.tasker.store

import android.content.Context

/**
 * The variables rules set, Boolean or Float, by normalised name.
 *
 * Two preference files rather than a flag beside each value: one is kept, the other is
 * emptied at every ignition ([clearCycle]), and the preference map already stores both types
 * natively. A variable lives in exactly one of them — setting it moves it to the file its
 * action asks for.
 *
 * commit(), not apply(): the head unit powers off seconds after the car is parked, and a value
 * set by an end-of-drive rule must be on disk before that.
 */
object VariableStore {

    private const val KEPT = "ev_tasker_variables"
    private const val CYCLE = "ev_tasker_variables_cycle"

    fun all(context: Context): Map<String, Any> =
        (prefs(context, CYCLE).all + prefs(context, KEPT).all)
            .filterValues { it is Boolean || it is Float }
            .mapValues { it.value!! }

    fun set(context: Context, name: String, value: Any, keep: Boolean) {
        prefs(context, if (keep) CYCLE else KEPT).edit().remove(name).commit()
        val editor = prefs(context, if (keep) KEPT else CYCLE).edit()
        when (value) {
            is Boolean -> editor.putBoolean(name, value)
            is Float -> editor.putFloat(name, value)
            else -> return
        }
        editor.commit()
    }

    /** Forgets every variable that was not kept — called once per ignition. */
    fun clearCycle(context: Context) {
        prefs(context, CYCLE).edit().clear().commit()
    }

    private fun prefs(context: Context, file: String) =
        context.getSharedPreferences(file, Context.MODE_PRIVATE)
}
