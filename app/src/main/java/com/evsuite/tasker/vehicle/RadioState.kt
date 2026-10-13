package com.evsuite.tasker.vehicle

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.saic.SaicRadio

/**
 * The tuner as `SaicRadio.currentStation()` reports it, in one line (CR-020).
 *
 * `ALLOWED` on a radio action only says the binder call returned. Whether the tuner went
 * silent or moved is what the before/after pair in the history answers, and what the
 * Diagnostic `RADIO_STATE` row shows on demand.
 */
object RadioState {

    /** The actions whose history detail carries the before/after pair. */
    val PROBED = setOf(
        ActionType.PLAY_RADIO,
        ActionType.PAUSE_RADIO,
        ActionType.TUNE_RADIO,
        ActionType.RADIO_PLAY_PAUSE,
        ActionType.RADIO_NEXT_STATION,
        ActionType.RADIO_PREV_STATION,
    )

    /** How long the tuner gets to act on a command before it is read again. */
    const val SETTLE_MS = 1_500L

    /** `FM 101500 kHz playing`; `unreadable` when the service did not answer. */
    fun describe(t: SaicRadio.Tuned?): String {
        if (t == null) return "unreadable"
        val band = when (t.band) {
            SaicRadio.BAND_AM -> "AM"
            SaicRadio.BAND_FM -> "FM"
            SaicRadio.BAND_DAB -> "DAB"
            else -> "band ${t.band}"
        }
        return "$band ${t.frequencyKhz} kHz ${if (t.playing) "playing" else "paused"}"
    }

    /** The action's own detail, followed by the two readings. */
    fun detail(own: String?, before: String, after: String): String =
        listOfNotNull(own, "before=$before after=$after").joinToString("; ")
}
