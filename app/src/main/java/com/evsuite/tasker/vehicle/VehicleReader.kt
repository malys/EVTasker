package com.evsuite.tasker.vehicle

import android.content.Context
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.catalog.SnapshotKeys
import com.evsuite.hardware.catalog.VehicleEnums
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.saic.SaicWeather
import com.evsuite.hardware.saic.SaicVehicleControl
import com.evsuite.tasker.model.Snapshot
import com.evsuite.tasker.util.CarLocation
import com.evsuite.tasker.util.DriveClock
import com.evsuite.tasker.util.PlatformContext
import java.util.Calendar

/**
 * Builds a [Snapshot] by reading the vehicle **directly** through EVHardware.
 *
 * This is what makes EVTasker independent: no bridge, no EVProfile. A getter returning
 * null / -1 (layer not ready, property absent) is simply left out of the snapshot — the
 * engine treats a missing key as "unreadable", never as a value.
 *
 * [Snapshot.bridgeAvailable] is reused to mean "the vehicle layer is available": false when
 * EVHardware is not ready yet, so no rule fires on empty data.
 */
object VehicleReader {

    fun read(
        /** For the platform-context readings: what is playing, which network, a live call. */
        context: Context,
        /**
         * Snapshot keys worth paying for on this pass.
         *
         * Almost every reading here is a binder call that answers at once, and those are taken
         * unconditionally. One is not: the weather query reaches the head unit's provider and
         * may take up to its timeout. Charging every rule cycle for a reading no rule asked
         * about would delay the ignition actions that *were* asked for, so the caller says what
         * it needs and the expensive readings are skipped when nothing does.
         *
         * Null means "everything" — for the diagnostic, whose whole job is to report on
         * readings no rule may be using.
         */
        wanted: Set<String>?,
        btMacs: Set<String>,
        btAvailable: Boolean,
        btOnboardMacs: Set<String>? = null,
        btHandsFreeMacs: Set<String>? = null,
        fix: CarLocation.Fix? = null
    ): Snapshot {
        val calendar = Calendar.getInstance()
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val date = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        val ready = EVHardware.isCarPropertyManagerReady()
        if (!ready) {
            // Bluetooth, the clock and the position still hold: they are context, not vehicle
            // signals, and a rule made only of those must stay evaluable when the car layer
            // is not up. The vendor services are bound separately too, so their readings are
            // taken here as well rather than being lost with the AOSP layer.
            return Snapshot(
                readings = vendorReadings() + headUnitReadings(fix, wanted) + platformReadings(context),
                btMacs = btMacs,
                btAvailable = btAvailable,
                btOnboardMacs = btOnboardMacs,
                btHandsFreeMacs = btHandsFreeMacs,
                minutesOfDay = minutes,
                dayOfWeek = day,
                localDate = date,
                latitude = fix?.latitude,
                longitude = fix?.longitude,
                bridgeAvailable = false
            )
        }

        val readings = buildMap<String, Any> {
            val speed = EVHardware.getVehicleSpeedKmh()
            put(SnapshotKeys.KEY_SPEED_READABLE, speed != null)
            speed?.let { put(SnapshotKeys.KEY_SPEED_KMH, it) }

            EVHardware.getCurrentIgnitionState().takeIf { it > 0 }?.let { put(SnapshotKeys.KEY_IGNITION, it) }
            EVHardware.isVehicleInPark()?.let { put(SnapshotKeys.KEY_IN_PARK, it) }
            EVHardware.getOutsideTempCelsius()?.let { put(SnapshotKeys.KEY_OUTSIDE_TEMP, it) }

            EVHardware.getDriveMode()?.let { put(SnapshotKeys.KEY_DRIVE_MODE, it.value) }
            EVHardware.getRegenLevel()?.let { put(SnapshotKeys.KEY_REGEN_LEVEL, it.value) }

            EVHardware.seatHeatLeftOrNull()?.let { put(SnapshotKeys.KEY_SEAT_HEAT_L, it) }
            EVHardware.seatHeatRightOrNull()?.let { put(SnapshotKeys.KEY_SEAT_HEAT_R, it) }
            EVHardware.steeringHeatOnOrNull()?.let { put(SnapshotKeys.KEY_STEERING_HEAT, it) }

            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME, EVHardware.getMediaVolume())
            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME_MAX, EVHardware.getMediaVolumeMax())
            if (EVHardware.hasBrightnessControl()) {
                putIfReadable(SnapshotKeys.KEY_BRIGHTNESS, EVHardware.getScreenBrightnessPercent())
            }

            // The `…OrNull` readers, not the Boolean ones: those answer false for a signal
            // the firmware never returned, which the engine would read as "the feature is
            // off" and act on. Absent from the snapshot is the honest answer.
            EVHardware.overspeedAlarmOnOrNull()?.let { put(SnapshotKeys.KEY_OVERSPEED_ALARM, it) }
            EVHardware.speedLimitToneOnOrNull()?.let { put(SnapshotKeys.KEY_SPEED_LIMIT_TONE, it) }
            EVHardware.soundWarningOnOrNull()?.let { put(SnapshotKeys.KEY_SOUND_WARNING, it) }
            EVHardware.aebEnabledOrNull()?.let { put(SnapshotKeys.KEY_AEB_ENABLED, it) }
            EVHardware.aebModeOrNull()?.let { put(SnapshotKeys.KEY_AEB_MODE, it) }
            putIfReadable(SnapshotKeys.KEY_AEB_SENSITIVITY, EVHardware.getAebSensitivity())
            putIfReadable(SnapshotKeys.KEY_ELK_MODE, EVHardware.getElkMode())
            putIfReadable(SnapshotKeys.KEY_ELK_SENSITIVITY, EVHardware.getElkSensitivity())
            EVHardware.tsrOnOrNull()?.let { put(SnapshotKeys.KEY_TSR, it) }
            EVHardware.energySavingOnOrNull()?.let { put(SnapshotKeys.KEY_ENERGY_SAVING, it) }
            putIfReadable(SnapshotKeys.KEY_ACC_TJA_MODE, EVHardware.getAccTjaMode())
            putIfReadable(SnapshotKeys.KEY_LIMITER_MODE, EVHardware.getSpeedLimiterMode())

            // AOSP climate ids first — unverified, but present on firmware without the
            // vendor service. The vendor readings below overwrite them where they exist,
            // because that service is the one the car's own HVAC screen reads.
            EVHardware.getAcOn()?.let { put(SnapshotKeys.KEY_AC_ON, it) }
            EVHardware.getHvacAutoOn()?.let { put(SnapshotKeys.KEY_HVAC_AUTO, it) }
            EVHardware.getRecircOn()?.let { put(SnapshotKeys.KEY_RECIRC, it) }
            EVHardware.getFanSpeed()?.let { put(SnapshotKeys.KEY_FAN_SPEED, it) }
            EVHardware.getTemperatureSetCelsius()?.let { put(SnapshotKeys.KEY_TEMPERATURE_SET, it) }
            EVHardware.isAnyWindowOpen()?.let { put(SnapshotKeys.KEY_WINDOW_OPEN, it) }
            EVHardware.frontDoorOpenOrNull()?.let { put(SnapshotKeys.KEY_FRONT_DOOR_OPEN, it) }

            put(SnapshotKeys.KEY_FIRMWARE_GEN, FirmwareInfo.getGeneration().name)
            putAll(vendorReadings())
            putAll(headUnitReadings(fix, wanted))
            putAll(platformReadings(context))
        }

        return Snapshot(
            readings = readings,
            btMacs = btMacs,
            btAvailable = btAvailable,
            btOnboardMacs = btOnboardMacs,
            btHandsFreeMacs = btHandsFreeMacs,
            minutesOfDay = minutes,
            dayOfWeek = day,
            localDate = date,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            bridgeAvailable = true
        )
    }

    /**
     * Climate and charging from the SAIC vendor services.
     *
     * Bound separately from the AOSP car layer and read separately: a firmware where
     * `CarPropertyManager` never comes up can still answer these, and vice versa. Anything
     * the service does not answer stays out of the snapshot, same rule as everywhere else.
     */
    private fun vendorReadings(): Map<String, Any> = buildMap {
        SaicClimate.powerOn()?.let { put(SnapshotKeys.KEY_CLIMATE_ON, it) }
        SaicClimate.acOn()?.let { put(SnapshotKeys.KEY_AC_ON, it) }
        SaicClimate.autoOn()?.let { put(SnapshotKeys.KEY_HVAC_AUTO, it) }
        SaicClimate.recirculationOn()?.let { put(SnapshotKeys.KEY_RECIRC, it) }
        SaicClimate.fanLevel()?.let { put(SnapshotKeys.KEY_FAN_SPEED, it) }
        SaicClimate.driverTemp()?.let { put(SnapshotKeys.KEY_TEMPERATURE_SET, it) }
        SaicClimate.passengerTemp()?.let { put(SnapshotKeys.KEY_PASSENGER_TEMP, it) }
        SaicClimate.econOn()?.let { put(SnapshotKeys.KEY_ECON, it) }
        SaicClimate.frontDefrostOn()?.let { put(SnapshotKeys.KEY_FRONT_DEFROST, it) }
        SaicClimate.rearDefrostOn()?.let { put(SnapshotKeys.KEY_REAR_DEFROST, it) }
        // The AAOS ENV_OUTSIDE_TEMPERATURE property answers 0.0 on SWI68 rather than
        // failing, so the key is present and wrong. The vendor read is the only honest one.
        SaicClimate.outsideTempCelsius()?.let { put(SnapshotKeys.KEY_OUTSIDE_TEMP, it) }

        // Windows: the vendor read is a measured position, unlike the AOSP window id above
        // which no MG4 confirmed. Where it answers it also settles WINDOW_OPEN.
        SaicVehicleControl.widestWindowPercent()?.let {
            put(SnapshotKeys.KEY_WINDOW_PERCENT, it)
            put(SnapshotKeys.KEY_WINDOW_OPEN, it > 0)
        }
        // Each window as well as the widest: "close the windows" wants the worst case, a rule
        // about the driver's own glass wants that one.
        WINDOW_KEYS.forEach { (window, key) ->
            SaicVehicleControl.windowPercent(window)?.let { put(key, it) }
        }
        SaicVehicleControl.doorsLocked()?.let { put(SnapshotKeys.KEY_DOORS_LOCKED, it) }

        SaicCharging.stateOfChargePercent()?.let { put(SnapshotKeys.KEY_BATTERY_PERCENT, it) }
        SaicCharging.chargeLimitPercent()?.let { put(SnapshotKeys.KEY_CHARGE_LIMIT, it) }
        // The flag is "current is flowing", not "the status is non-zero": a finished charge, a
        // stopped one and a fault are all non-zero, and all three would make a "when charging"
        // rule fire on a car that is not charging. The state itself is kept alongside it —
        // "plugged in and idle" is a state a rule wants and the flag cannot express.
        SaicCharging.chargingStatus()?.let {
            put(SnapshotKeys.KEY_CHARGING_STATUS, it)
            put(SnapshotKeys.KEY_CHARGING, it in VehicleEnums.CHARGING_ACTIVE_STATES)
        }
        SaicCharging.scheduleEnabled()?.let { put(SnapshotKeys.KEY_CHARGE_SCHEDULE, it) }
        SaicCharging.scheduleStartMinutes()?.let { put(SnapshotKeys.KEY_CHARGE_WINDOW_START, it) }
        SaicCharging.scheduleStopMinutes()?.let { put(SnapshotKeys.KEY_CHARGE_WINDOW_STOP, it) }
        SaicCharging.batteryPreheatOn()?.let { put(SnapshotKeys.KEY_BATTERY_PREHEAT, it) }
        SaicCharging.rangeKm()?.let { put(SnapshotKeys.KEY_RANGE_KM, it) }
    }

    private val WINDOW_KEYS = listOf(
        SaicVehicleControl.Window.DRIVER to SnapshotKeys.KEY_WINDOW_DRIVER,
        SaicVehicleControl.Window.PASSENGER to SnapshotKeys.KEY_WINDOW_PASSENGER,
        SaicVehicleControl.Window.REAR_LEFT to SnapshotKeys.KEY_WINDOW_REAR_LEFT,
        SaicVehicleControl.Window.REAR_RIGHT to SnapshotKeys.KEY_WINDOW_REAR_RIGHT
    )

    /**
     * The head unit's own applications: the odometer, and the sky where the car is.
     *
     * Not part of [vendorReadings] because they are a different bind and a different failure:
     * a car whose vehicle hub answers perfectly may have no navigation adapter at all, and
     * the two must not take each other down.
     *
     * The weather needs a position, so it is only asked when there is a fix. No fix means the
     * reading is absent, which is the honest answer — the last known weather somewhere else is
     * not weather here.
     */
    private fun headUnitReadings(fix: CarLocation.Fix?, wanted: Set<String>?): Map<String, Any> = buildMap {
        SaicNav.totalMileageKm()?.let { put(SnapshotKeys.KEY_ODOMETER_KM, it) }
        // The one reading that costs real time — see `wanted`.
        if (wanted != null && SnapshotKeys.KEY_WEATHER_TEXT !in wanted) return@buildMap
        if (fix == null) return@buildMap
        SaicWeather.currentAt(fix.latitude, fix.longitude, weatherLanguage())
            ?.text
            ?.takeIf { it.isNotBlank() }
            ?.let { put(SnapshotKeys.KEY_WEATHER_TEXT, it) }
    }

    /**
     * The language the weather phrase comes back in.
     *
     * The rule was written on this head unit, in its language, so the phrase the user typed is
     * in that language too. Asking in anything else would make every weather rule stop matching
     * the day someone changed the display language — which is a real thing to do, but the rule
     * should then be edited, not silently broken.
     */
    private fun weatherLanguage(): String {
        val locale = java.util.Locale.getDefault()
        val country = locale.country.lowercase()
        return if (country.isBlank()) locale.language.lowercase()
        else "${locale.language.lowercase()}-$country"
    }

    /**
     * Android's own context: what is playing, which network, whether a call is up, how long
     * the drive has lasted.
     *
     * Read here rather than in the engine so they obey the one rule the whole snapshot obeys —
     * a reading the platform will not give is left out, and the condition on it comes back
     * unavailable instead of false.
     */
    private fun platformReadings(context: Context): Map<String, Any> = buildMap {
        DriveClock.minutes()?.let { put(SnapshotKeys.KEY_DRIVE_MINUTES, it) }
        PlatformContext.mediaPlaying(context)?.let { put(SnapshotKeys.KEY_MEDIA_PLAYING, it) }
        PlatformContext.inCall(context)?.let { put(SnapshotKeys.KEY_IN_CALL, it) }
        PlatformContext.wifiSsid(context)?.let { put(SnapshotKeys.KEY_WIFI_SSID, it) }
    }

    /** EVHardware getters return -1 when the layer is not ready: omit rather than store it. */
    private fun MutableMap<String, Any>.putIfReadable(key: String, value: Int) {
        if (value >= 0) put(key, value)
    }
}
