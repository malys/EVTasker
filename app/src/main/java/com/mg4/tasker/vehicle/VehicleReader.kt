package com.mg4.tasker.vehicle

import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.catalog.SnapshotKeys
import com.mg4.hardware.saic.SaicCharging
import com.mg4.hardware.saic.SaicClimate
import com.mg4.hardware.saic.SaicVehicleControl
import com.mg4.tasker.model.Snapshot
import com.mg4.tasker.util.CarLocation
import java.util.Calendar

/**
 * Builds a [Snapshot] by reading the vehicle **directly** through MG4Hardware.
 *
 * This is what makes MG4Tasker independent: no bridge, no MG4Control. A getter returning
 * null / -1 (layer not ready, property absent) is simply left out of the snapshot — the
 * engine treats a missing key as "unreadable", never as a value.
 *
 * [Snapshot.bridgeAvailable] is reused to mean "the vehicle layer is available": false when
 * MG4Hardware is not ready yet, so no rule fires on empty data.
 */
object VehicleReader {

    fun read(btMacs: Set<String>, btAvailable: Boolean, fix: CarLocation.Fix? = null): Snapshot {
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

        val ready = MG4Hardware.isCarPropertyManagerReady()
        if (!ready) {
            // Bluetooth, the clock and the position still hold: they are context, not vehicle
            // signals, and a rule made only of those must stay evaluable when the car layer
            // is not up. The vendor services are bound separately too, so their readings are
            // taken here as well rather than being lost with the AOSP layer.
            return Snapshot(
                readings = vendorReadings(),
                btMacs = btMacs,
                btAvailable = btAvailable,
                minutesOfDay = minutes,
                dayOfWeek = day,
                localDate = date,
                latitude = fix?.latitude,
                longitude = fix?.longitude,
                bridgeAvailable = false
            )
        }

        val readings = buildMap<String, Any> {
            val speed = MG4Hardware.getVehicleSpeedKmh()
            put(SnapshotKeys.KEY_SPEED_READABLE, speed != null)
            speed?.let { put(SnapshotKeys.KEY_SPEED_KMH, it) }

            MG4Hardware.getCurrentIgnitionState().takeIf { it > 0 }?.let { put(SnapshotKeys.KEY_IGNITION, it) }
            MG4Hardware.isVehicleInPark()?.let { put(SnapshotKeys.KEY_IN_PARK, it) }
            MG4Hardware.getOutsideTempCelsius()?.let { put(SnapshotKeys.KEY_OUTSIDE_TEMP, it) }

            MG4Hardware.getDriveMode()?.let { put(SnapshotKeys.KEY_DRIVE_MODE, it.value) }
            MG4Hardware.getRegenLevel()?.let { put(SnapshotKeys.KEY_REGEN_LEVEL, it.value) }

            MG4Hardware.seatHeatLeftOrNull()?.let { put(SnapshotKeys.KEY_SEAT_HEAT_L, it) }
            MG4Hardware.seatHeatRightOrNull()?.let { put(SnapshotKeys.KEY_SEAT_HEAT_R, it) }
            MG4Hardware.steeringHeatOnOrNull()?.let { put(SnapshotKeys.KEY_STEERING_HEAT, it) }

            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME, MG4Hardware.getMediaVolume())
            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME_MAX, MG4Hardware.getMediaVolumeMax())
            if (MG4Hardware.hasBrightnessControl()) {
                putIfReadable(SnapshotKeys.KEY_BRIGHTNESS, MG4Hardware.getScreenBrightnessPercent())
            }

            // The `…OrNull` readers, not the Boolean ones: those answer false for a signal
            // the firmware never returned, which the engine would read as "the feature is
            // off" and act on. Absent from the snapshot is the honest answer.
            MG4Hardware.overspeedAlarmOnOrNull()?.let { put(SnapshotKeys.KEY_OVERSPEED_ALARM, it) }
            MG4Hardware.speedLimitToneOnOrNull()?.let { put(SnapshotKeys.KEY_SPEED_LIMIT_TONE, it) }
            MG4Hardware.soundWarningOnOrNull()?.let { put(SnapshotKeys.KEY_SOUND_WARNING, it) }
            MG4Hardware.aebEnabledOrNull()?.let { put(SnapshotKeys.KEY_AEB_ENABLED, it) }
            MG4Hardware.aebModeOrNull()?.let { put(SnapshotKeys.KEY_AEB_MODE, it) }
            putIfReadable(SnapshotKeys.KEY_AEB_SENSITIVITY, MG4Hardware.getAebSensitivity())
            putIfReadable(SnapshotKeys.KEY_ELK_MODE, MG4Hardware.getElkMode())
            putIfReadable(SnapshotKeys.KEY_ELK_SENSITIVITY, MG4Hardware.getElkSensitivity())
            MG4Hardware.tsrOnOrNull()?.let { put(SnapshotKeys.KEY_TSR, it) }
            MG4Hardware.energySavingOnOrNull()?.let { put(SnapshotKeys.KEY_ENERGY_SAVING, it) }
            putIfReadable(SnapshotKeys.KEY_ACC_TJA_MODE, MG4Hardware.getAccTjaMode())
            putIfReadable(SnapshotKeys.KEY_LIMITER_MODE, MG4Hardware.getSpeedLimiterMode())

            // AOSP climate ids first — unverified, but present on firmware without the
            // vendor service. The vendor readings below overwrite them where they exist,
            // because that service is the one the car's own HVAC screen reads.
            MG4Hardware.getAcOn()?.let { put(SnapshotKeys.KEY_AC_ON, it) }
            MG4Hardware.getHvacAutoOn()?.let { put(SnapshotKeys.KEY_HVAC_AUTO, it) }
            MG4Hardware.getRecircOn()?.let { put(SnapshotKeys.KEY_RECIRC, it) }
            MG4Hardware.getFanSpeed()?.let { put(SnapshotKeys.KEY_FAN_SPEED, it) }
            MG4Hardware.getTemperatureSetCelsius()?.let { put(SnapshotKeys.KEY_TEMPERATURE_SET, it) }
            MG4Hardware.isAnyWindowOpen()?.let { put(SnapshotKeys.KEY_WINDOW_OPEN, it) }

            put(SnapshotKeys.KEY_FIRMWARE_GEN, FirmwareInfo.getGeneration().name)
            putAll(vendorReadings())
        }

        return Snapshot(
            readings = readings,
            btMacs = btMacs,
            btAvailable = btAvailable,
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

        // Windows: the vendor read is a measured position, unlike the AOSP window id above
        // which no MG4 confirmed. Where it answers it also settles WINDOW_OPEN.
        SaicVehicleControl.widestWindowPercent()?.let {
            put(SnapshotKeys.KEY_WINDOW_PERCENT, it)
            put(SnapshotKeys.KEY_WINDOW_OPEN, it > 0)
        }
        SaicVehicleControl.doorsLocked()?.let { put(SnapshotKeys.KEY_DOORS_LOCKED, it) }

        SaicCharging.stateOfChargePercent()?.let { put(SnapshotKeys.KEY_BATTERY_PERCENT, it) }
        SaicCharging.chargeLimitPercent()?.let { put(SnapshotKeys.KEY_CHARGE_LIMIT, it) }
        // 0 means "not charging" in the vendor status; anything else is a charging state.
        SaicCharging.chargingStatus()?.let { put(SnapshotKeys.KEY_CHARGING, it != 0) }
    }

    /** MG4Hardware getters return -1 when the layer is not ready: omit rather than store it. */
    private fun MutableMap<String, Any>.putIfReadable(key: String, value: Int) {
        if (value >= 0) put(key, value)
    }
}
