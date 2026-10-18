package com.evsuite.tasker.store

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.evsuite.tasker.util.WallClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * How long the head unit keeps running after the ignition goes off (RI-004).
 *
 * The vehicle service writes the wall time of each ignition-OFF and a heartbeat every
 * [HEARTBEAT_MS]. A heartbeat that finds the previous one more than [GAP_MS] away (a reboot,
 * a suspend, or a clock jump) closes the previous cycle: its OFF and its last heartbeat are
 * kept for the Diagnostic `POWER_CYCLE` row. The difference is the post-off run window, to
 * one heartbeat. App-private prefs, `apply()` only; nothing here touches the car.
 *
 * Nothing is written while the head unit still runs on its factory clock: a 36 s wake on
 * 2019-01-01 would otherwise become the "previous" cycle, and the jump to the real date one
 * long gap, hiding the cycle that actually ended the last drive.
 */
object PowerCycleStore {

    const val HEARTBEAT_MS = 30_000L
    private const val GAP_MS = 3 * HEARTBEAT_MS

    private const val PREFS = "ev_tasker_power_cycle"
    private const val KEY_OFF = "ignition_off"
    private const val KEY_BEAT = "heartbeat"
    private const val KEY_PREV_OFF = "previous_ignition_off"
    private const val KEY_PREV_BEAT = "previous_heartbeat"
    private const val KEY_RESUMED = "resumed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ignitionOff(context: Context, now: Long = System.currentTimeMillis()) {
        if (!WallClock.isSet(now)) return
        prefs(context).edit().putLong(KEY_OFF, now).apply()
    }

    fun heartbeat(context: Context, now: Long = System.currentTimeMillis()) {
        if (!WallClock.isSet(now)) return
        val p = prefs(context)
        val edit = p.edit().putLong(KEY_BEAT, now)
        val last = p.long(KEY_BEAT)
        if (last != null && abs(now - last) > GAP_MS) {
            // The OFF moves with its cycle, so a next cycle without one prints none.
            edit.putLong(KEY_PREV_BEAT, last).putLong(KEY_RESUMED, now).remove(KEY_OFF)
            p.long(KEY_OFF)?.let { edit.putLong(KEY_PREV_OFF, it) } ?: edit.remove(KEY_PREV_OFF)
        }
        edit.apply()
    }

    fun describe(context: Context): String {
        val p = prefs(context)
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return format(p.long(KEY_PREV_OFF), p.long(KEY_PREV_BEAT), boot, p.long(KEY_RESUMED))
    }

    /** `previous: off <t0>, last heartbeat <t1> (+Δs), boot <t2>, resumed <t3>`. */
    internal fun format(prevOff: Long?, prevBeat: Long?, boot: Long, resumed: Long?): String {
        val delta = if (prevOff != null && prevBeat != null) " (+${(prevBeat - prevOff) / 1000} s)" else ""
        return "previous: off ${time(prevOff)}, last heartbeat ${time(prevBeat)}$delta, " +
            "boot ${time(boot)}, resumed ${time(resumed)}"
    }

    private fun time(at: Long?): String =
        at?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) } ?: "none"

    private fun SharedPreferences.long(key: String): Long? = if (contains(key)) getLong(key, 0) else null
}
