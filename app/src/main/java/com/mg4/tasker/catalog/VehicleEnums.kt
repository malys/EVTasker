package com.mg4.tasker.catalog

import com.mg4.tasker.R
import com.mg4.tasker.model.EnumOption

/**
 * Numeric values the vehicle expects, copied from MG4Control.
 *
 * These are protocol values, not display indices: reordering or renumbering them changes
 * the setting applied to the car. Sources in MG4Control: `model/DriveMode.kt`,
 * `model/RegenLevel.kt`, and `hardware/MG4Hardware.kt` (objects `AebMode`, `Swi68Mode`,
 * plus the ELK / SAS comments).
 */
object VehicleEnums {

    /** DriveMode.value — MG4Control model/DriveMode.kt */
    val DRIVE_MODES = listOf(
        EnumOption(2, R.string.drive_eco),
        EnumOption(3, R.string.drive_normal),
        EnumOption(4, R.string.drive_sport),
        EnumOption(6, R.string.drive_snow),
        EnumOption(7, R.string.drive_custom)
    )

    /** RegenLevel.value — MG4Control model/RegenLevel.kt */
    val REGEN_LEVELS = listOf(
        EnumOption(0, R.string.regen_low),
        EnumOption(1, R.string.regen_medium),
        EnumOption(2, R.string.regen_high),
        EnumOption(3, R.string.regen_adaptive),
        EnumOption(5, R.string.regen_off),
        EnumOption(6, R.string.regen_one_pedal)
    )

    /** MG4Hardware.AebMode */
    val AEB_MODES = listOf(
        EnumOption(1, R.string.aeb_alert_only),
        EnumOption(2, R.string.aeb_alert_brake)
    )

    /** AEB / ELK sensitivity — 1 low, 2 standard, 3 high (0 means "not configured"). */
    val SENSITIVITIES = listOf(
        EnumOption(1, R.string.sensitivity_low),
        EnumOption(2, R.string.sensitivity_standard),
        EnumOption(3, R.string.sensitivity_high)
    )

    /** ELK — see MG4Hardware.getElkMode / setElkMode. */
    val ELK_MODES = listOf(
        EnumOption(1, R.string.elk_off),
        EnumOption(2, R.string.elk_warn),
        EnumOption(3, R.string.elk_assist),
        EnumOption(5, R.string.elk_full)
    )

    /** MG4Hardware.Swi68Mode — ACC/TJA. OFF is 0x4, not 0. */
    val ACC_TJA_MODES = listOf(
        EnumOption(4, R.string.acc_off),
        EnumOption(1, R.string.acc_acc),
        EnumOption(2, R.string.acc_tja)
    )

    /** SAS speed limiter — 0 off, 2 manual, 3 intelligent. */
    val LIMITER_MODES = listOf(
        EnumOption(0, R.string.limiter_off),
        EnumOption(2, R.string.limiter_manual),
        EnumOption(3, R.string.limiter_smart)
    )

    /** VehicleIgnitionState (standard AAOS) — useful values only. */
    val IGNITION_STATES = listOf(
        EnumOption(1, R.string.ignition_lock),
        EnumOption(2, R.string.ignition_off),
        EnumOption(3, R.string.ignition_acc),
        EnumOption(4, R.string.ignition_on),
        EnumOption(5, R.string.ignition_start)
    )

    /** Firmware generations — FirmwareInfo.Gen in MG4Control. */
    val FIRMWARE_GENS = listOf("SWI133", "SWI132", "SWI68", "SWI69", "SWI131", "SWI165")

    // Audio bounds — MG4Hardware AUDIO_TYPE_MIN/MAX and AUDIO_LEVEL_MIN/MAX.
    const val AUDIO_TYPE_MIN  = 0
    const val AUDIO_TYPE_MAX  = 3
    const val AUDIO_LEVEL_MIN = -9
    const val AUDIO_LEVEL_MAX = 9

    /** MG4Control's brightness floor: never black out the vehicle screen. */
    const val BRIGHTNESS_MIN = 5
    const val BRIGHTNESS_MAX = 100

    /** Fallback when the vehicle does not report its real maximum volume. */
    const val MEDIA_VOLUME_FALLBACK_MAX = 30
}
