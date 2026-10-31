package com.evsuite.tasker.engine

import java.time.LocalDate
import java.util.Locale
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Variable names, and the variables every cycle computes for itself.
 *
 * The sun is computed here rather than asked of anything: the position and the date are
 * enough, so it answers with no network and no vehicle. The formula is the NOAA sunrise
 * equation, within a minute or two of the published tables — far finer than a rule about
 * the screen brightness needs.
 *
 * No dew point: it needs the outside humidity, which neither the car nor its weather service
 * reports.
 */
object Variables {

    /** Sunrise, sunset, civil dawn and dusk: minutes since local midnight. */
    const val SUNRISE = "SUNRISE"
    const val SUNSET = "SUNSET"
    const val DAWN = "DAWN"
    const val DUSK = "DUSK"
    /** True between sunset and sunrise. */
    const val NIGHT = "NIGHT"

    /** The computed variables that hold a time of day. */
    val TIMES = listOf(SUNRISE, SUNSET, DAWN, DUSK)

    /** Every computed name — none of them can be set by a rule. */
    val PREDEFINED = TIMES + NIGHT

    const val MAX_NAME_LENGTH = 32

    /**
     * One spelling per variable. "froid" typed in a condition and "FROID" in the action that
     * sets it are the same variable; otherwise the condition could never be read.
     */
    fun normalize(name: String): String = name.trim().uppercase(Locale.ROOT)

    /** A name a rule may set: non-blank, short, and not one the cycle computes. */
    fun isSettable(name: String): Boolean {
        val n = normalize(name)
        return n.isNotEmpty() && n.length <= MAX_NAME_LENGTH && n !in PREDEFINED
    }

    /**
     * The sun variables for a place and a day, minutes in local time.
     *
     * Nothing without a position or a set clock: a sunset computed for the boot default of
     * 2019 or for no place at all would be a value, and a wrong one. A day the sun does not
     * rise or set leaves those times out, and NIGHT then says which of the two it is.
     */
    fun sun(
        latitude: Double?,
        longitude: Double?,
        clockSet: Boolean,
        localDate: String,
        minutesOfDay: Int,
        utcOffsetMinutes: Int
    ): Map<String, Any> {
        if (!clockSet || latitude == null || longitude == null) return emptyMap()
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return emptyMap()
        val day = SunDay(latitude, longitude, date)

        val values = HashMap<String, Any>()
        val rise = day.event(-0.833, rising = true, utcOffsetMinutes)
        val set = day.event(-0.833, rising = false, utcOffsetMinutes)
        rise?.let { values[SUNRISE] = it.toFloat() }
        set?.let { values[SUNSET] = it.toFloat() }
        day.event(-6.0, rising = true, utcOffsetMinutes)?.let { values[DAWN] = it.toFloat() }
        day.event(-6.0, rising = false, utcOffsetMinutes)?.let { values[DUSK] = it.toFloat() }
        values[NIGHT] = when {
            rise != null && set != null -> minutesOfDay < rise || minutesOfDay >= set
            else -> day.polarNight(-0.833)
        }
        return values
    }

    /** One solar day at one place: the transit and the declination the events derive from. */
    private class SunDay(latitude: Double, longitude: Double, date: LocalDate) {
        private val phi = Math.toRadians(latitude)
        private val transit: Double
        private val declination: Double

        init {
            // Days since J2000.0 at noon UTC of the date, shifted to the local meridian.
            val n = date.toEpochDay() + 2440588.0 - 2451545.0 + 0.0008
            val mean = n - longitude / 360.0
            val anomaly = Math.toRadians((357.5291 + 0.98560028 * mean).mod(360.0))
            val centre = 1.9148 * sin(anomaly) + 0.0200 * sin(2 * anomaly) + 0.0003 * sin(3 * anomaly)
            val ecliptic = Math.toRadians((Math.toDegrees(anomaly) + centre + 180.0 + 102.9372).mod(360.0))
            transit = 2451545.0 + mean + 0.0053 * sin(anomaly) - 0.0069 * sin(2 * ecliptic)
            declination = asin(sin(ecliptic) * sin(Math.toRadians(23.4397)))
        }

        private fun hourAngleCos(elevation: Double): Double =
            (sin(Math.toRadians(elevation)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))

        /** Whether the sun stays below [elevation] all day (true) or above it (false). */
        fun polarNight(elevation: Double): Boolean = hourAngleCos(elevation) > 1.0

        /** Local minutes since midnight of the crossing, null when the sun never crosses. */
        fun event(elevation: Double, rising: Boolean, utcOffsetMinutes: Int): Int? {
            val c = hourAngleCos(elevation)
            if (c < -1.0 || c > 1.0) return null
            val omega = Math.toDegrees(acos(c)) / 360.0
            val julian = if (rising) transit - omega else transit + omega
            val utcMinutes = (julian - 2440587.5) * 1440.0
            return Math.round(utcMinutes + utcOffsetMinutes).mod(1440L).toInt()
        }
    }
}
