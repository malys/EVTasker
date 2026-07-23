package com.mg4.tasker.vehicle

import com.mg4.hardware.FirmwareInfo
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.catalog.SnapshotKeys
import com.mg4.tasker.model.Snapshot
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

    fun read(btMacs: Set<String>): Snapshot {
        val calendar = Calendar.getInstance()
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val day = calendar.get(Calendar.DAY_OF_WEEK)

        val ready = MG4Hardware.isCarPropertyManagerReady()
        if (!ready) {
            return Snapshot(minutesOfDay = minutes, dayOfWeek = day, bridgeAvailable = false)
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

            putIfReadable(SnapshotKeys.KEY_SEAT_HEAT_L, MG4Hardware.getSeatHeatLeft())
            putIfReadable(SnapshotKeys.KEY_SEAT_HEAT_R, MG4Hardware.getSeatHeatRight())
            put(SnapshotKeys.KEY_STEERING_HEAT, MG4Hardware.isSteeringHeatOn())

            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME, MG4Hardware.getMediaVolume())
            putIfReadable(SnapshotKeys.KEY_MEDIA_VOLUME_MAX, MG4Hardware.getMediaVolumeMax())
            if (MG4Hardware.hasBrightnessControl()) {
                putIfReadable(SnapshotKeys.KEY_BRIGHTNESS, MG4Hardware.getScreenBrightnessPercent())
            }

            put(SnapshotKeys.KEY_OVERSPEED_ALARM, MG4Hardware.isOverspeedAlarmOn())
            put(SnapshotKeys.KEY_SPEED_LIMIT_TONE, MG4Hardware.isSpeedLimitToneOn())
            put(SnapshotKeys.KEY_SOUND_WARNING, MG4Hardware.isSoundWarningOn())
            put(SnapshotKeys.KEY_AEB_ENABLED, MG4Hardware.isAebEnabled())
            putIfReadable(SnapshotKeys.KEY_AEB_MODE, MG4Hardware.getAebMode())
            putIfReadable(SnapshotKeys.KEY_AEB_SENSITIVITY, MG4Hardware.getAebSensitivity())
            putIfReadable(SnapshotKeys.KEY_ELK_MODE, MG4Hardware.getElkMode())
            putIfReadable(SnapshotKeys.KEY_ELK_SENSITIVITY, MG4Hardware.getElkSensitivity())
            put(SnapshotKeys.KEY_TSR, MG4Hardware.isTsrOn())
            put(SnapshotKeys.KEY_ENERGY_SAVING, MG4Hardware.isEnergySavingOn())
            putIfReadable(SnapshotKeys.KEY_ACC_TJA_MODE, MG4Hardware.getAccTjaMode())
            putIfReadable(SnapshotKeys.KEY_LIMITER_MODE, MG4Hardware.getSpeedLimiterMode())

            MG4Hardware.getAcOn()?.let { put(SnapshotKeys.KEY_AC_ON, it) }
            MG4Hardware.getHvacAutoOn()?.let { put(SnapshotKeys.KEY_HVAC_AUTO, it) }
            MG4Hardware.getRecircOn()?.let { put(SnapshotKeys.KEY_RECIRC, it) }
            MG4Hardware.getFanSpeed()?.let { put(SnapshotKeys.KEY_FAN_SPEED, it) }
            MG4Hardware.getTemperatureSetCelsius()?.let { put(SnapshotKeys.KEY_TEMPERATURE_SET, it) }
            MG4Hardware.isAnyWindowOpen()?.let { put(SnapshotKeys.KEY_WINDOW_OPEN, it) }

            put(SnapshotKeys.KEY_FIRMWARE_GEN, FirmwareInfo.getGeneration().name)
        }

        return Snapshot(
            readings = readings,
            btMacs = btMacs,
            minutesOfDay = minutes,
            dayOfWeek = day,
            bridgeAvailable = true
        )
    }

    /** MG4Hardware getters return -1 when the layer is not ready: omit rather than store it. */
    private fun MutableMap<String, Any>.putIfReadable(key: String, value: Int) {
        if (value >= 0) put(key, value)
    }
}
