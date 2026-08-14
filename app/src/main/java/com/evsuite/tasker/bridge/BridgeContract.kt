package com.evsuite.tasker.bridge

/**
 * Keys of the protocol shared with `com.evsuite.profile.api.ProfileControlService`.
 *
 * ⚠️ Cross-application contract: these strings are duplicated on the other side and
 * nothing checks them at compile time. Any change must be made in BOTH projects.
 *
 * The failure mode is deliberately benign: a mismatched key simply goes missing from the
 * snapshot, which surfaces as an "unreadable data" condition on screen — never as a rule
 * firing on a wrong value.
 */
object BridgeContract {

    /**
     * Every application id EVProfile ships under, most "official" first.
     *
     * The suffixes come from EVProfile's own flavors — `.offline` for the build with no
     * network code, `.unstable` for pre-releases — and they install side by side. Looking
     * only for the stable id reported "EVProfile not installed" on a car that had it, which
     * removed the profile actions and the profile picker from the rule editor entirely.
     *
     * The service class name does not carry the suffix: only the application id does.
     */
    val EVPROFILE_PACKAGES = listOf(
        "com.evsuite.profile",
        "com.evsuite.profile.offline",
        "com.evsuite.profile.unstable",
        "com.evsuite.profile.offline.unstable"
    )

    const val BRIDGE_SERVICE     = "com.evsuite.profile.api.ProfileControlService"
    const val PERMISSION_BRIDGE  = "com.evsuite.profile.permission.CONTROL_PROFILES"

    // ── Snapshot ─────────────────────────────────────────────────────────────
    const val KEY_SPEED_KMH        = "speedKmh"
    const val KEY_SPEED_READABLE   = "speedReadable"
    const val KEY_IGNITION         = "ignition"
    const val KEY_IN_PARK          = "inPark"
    const val KEY_OUTSIDE_TEMP     = "outsideTempC"
    const val KEY_DRIVE_MODE       = "driveMode"
    const val KEY_REGEN_LEVEL      = "regenLevel"
    const val KEY_SEAT_HEAT_L      = "seatHeatLeft"
    const val KEY_SEAT_HEAT_R      = "seatHeatRight"
    const val KEY_STEERING_HEAT    = "steeringHeat"
    const val KEY_MEDIA_VOLUME     = "mediaVolume"
    const val KEY_MEDIA_VOLUME_MAX = "mediaVolumeMax"
    const val KEY_BRIGHTNESS       = "brightnessPct"
    const val KEY_OVERSPEED_ALARM  = "overspeedAlarm"
    const val KEY_SPEED_LIMIT_TONE = "speedLimitTone"
    const val KEY_SOUND_WARNING    = "soundWarning"
    const val KEY_AEB_ENABLED      = "aebEnabled"
    const val KEY_AEB_MODE         = "aebMode"
    const val KEY_AEB_SENSITIVITY  = "aebSensitivity"
    const val KEY_ELK_MODE         = "elkMode"
    const val KEY_ELK_SENSITIVITY  = "elkSensitivity"
    const val KEY_TSR              = "tsr"
    const val KEY_ENERGY_SAVING    = "energySaving"
    const val KEY_ACC_TJA_MODE     = "accTjaMode"
    const val KEY_LIMITER_MODE     = "limiterMode"
    const val KEY_AC_ON            = "acOn"
    const val KEY_HVAC_AUTO        = "hvacAuto"
    const val KEY_RECIRC           = "recirc"
    const val KEY_FAN_SPEED        = "fanSpeed"
    const val KEY_TEMPERATURE_SET  = "temperatureSetC"
    const val KEY_WINDOW_OPEN      = "windowOpen"
    const val KEY_FIRMWARE_GEN     = "firmwareGen"
    const val KEY_BT_MACS          = "btConnectedMacs"
    const val KEY_HAS_AUDIO        = "hasAudioControl"
    const val KEY_HAS_BRIGHTNESS   = "hasBrightness"

    // ── Action result ────────────────────────────────────────────────────────
    const val KEY_OK      = "ok"
    const val KEY_VERDICT = "verdict"
    const val KEY_DETAIL  = "detail"
    const val PARAM_VALUE = "value"

    const val VERDICT_ALLOWED       = "ALLOWED"
    const val VERDICT_MOVING        = "REFUSED_MOVING"
    const val VERDICT_UNKNOWN_SPEED = "REFUSED_UNKNOWN_SPEED"
    const val VERDICT_UNSUPPORTED   = "UNSUPPORTED"
    const val VERDICT_ERROR         = "ERROR"

    /** Local verdict, never returned by the bridge: EVProfile is unreachable. */
    const val VERDICT_NO_BRIDGE = "NO_BRIDGE"

    /**
     * Local verdict: the driver answered no to
     * [com.evsuite.hardware.catalog.ActionType.ASK_CONFIRM], or answered nothing at all.
     *
     * The only verdict that decides more than its own action — the engine stops the branch
     * on it. It is deliberately not [VERDICT_UNSUPPORTED] or [VERDICT_ERROR]: nothing broke
     * and nothing was refused by the car, the rule was simply declined.
     */
    const val VERDICT_DECLINED = "DECLINED"
}
