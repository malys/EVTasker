package com.mg4.tasker.model

import androidx.annotation.StringRes
import com.mg4.tasker.R
import com.mg4.tasker.catalog.VehicleEnums
import com.mg4.tasker.model.FirmwareGen.SWI131
import com.mg4.tasker.model.FirmwareGen.SWI132
import com.mg4.tasker.model.FirmwareGen.SWI133
import com.mg4.tasker.model.FirmwareGen.SWI165
import com.mg4.tasker.model.FirmwareGen.SWI68
import com.mg4.tasker.model.FirmwareGen.SWI69
import com.mg4.tasker.model.ValueSpec.Companion.number

/** Grouping in the action picker. */
enum class ActionGroup(@StringRes val labelRes: Int) {
    PROFILE(R.string.group_profile),
    DRIVING(R.string.group_driving),
    COMFORT(R.string.group_comfort),
    AUDIO(R.string.group_audio),
    ADAS(R.string.group_adas),
    SYSTEM(R.string.group_system)
}

/**
 * Catalogue of executable actions.
 *
 * [bridgeAction] is the identifier sent to `TaskerBridgeService.applyAction`. It is null
 * for actions handled locally by MG4Tasker (launch an app, notify), which never touch the
 * vehicle and therefore have no business in the bridge.
 *
 * [gated] marks writes that change road behaviour. MG4Control refuses them while the car
 * is moving or when its speed is unreadable. The editor shows the mark so the user knows
 * up front that such an action only applies when stopped, instead of discovering a
 * refusal in the history afterwards.
 *
 * The [SupportedOn] annotation declares firmware support (from MG4Control routing); it
 * drives the generated README matrix and the editor's runtime filter. Local actions carry
 * no annotation — they are firmware-independent.
 *
 * Climate/window WRITES are deliberately absent: MG4Control exposes those signals for
 * reading only, unverified, so there is no honest write path yet (see the Climate
 * conditions and the diagnostic screen).
 *
 * Also deliberately absent:
 *   • `VEHICLE_POWER_OFF` — cutting the vehicle must stay an explicit human gesture.
 *   • `SET_SOUND_FIELD`   — MG4Hardware.getSoundFieldType() always returns -1, so the
 *                           state is never readable back and a rule's effect would be
 *                           unverifiable.
 */
enum class ActionType(
    @StringRes val labelRes: Int,
    val group: ActionGroup,
    val spec: ValueSpec,
    val bridgeAction: String?,
    val gated: Boolean = false
) {

    // ── Profile ──────────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    APPLY_PROFILE(
        R.string.act_apply_profile, ActionGroup.PROFILE,
        ValueSpec(ValueKind.PROFILE), bridgeAction = "APPLY_PROFILE", gated = true
    ),

    // ── Driving (gated) ──────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_DRIVE_MODE(
        R.string.act_drive_mode, ActionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.DRIVE_MODES),
        "SET_DRIVE_MODE", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_REGEN_LEVEL(
        R.string.act_regen, ActionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.REGEN_LEVELS),
        "SET_REGEN_LEVEL", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ONE_PEDAL(
        R.string.act_one_pedal, ActionGroup.DRIVING,
        ValueSpec.BOOL, "SET_ONE_PEDAL", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ENERGY_SAVING(
        R.string.act_energy_saving, ActionGroup.DRIVING,
        ValueSpec.BOOL, "SET_ENERGY_SAVING", gated = true
    ),

    // ── Comfort (not gated: does not alter road behaviour) ───────────────────
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_SEAT_HEAT_LEFT(
        R.string.act_seat_heat_l, ActionGroup.COMFORT,
        number(0, 3), "SET_SEAT_HEAT_LEFT"
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_SEAT_HEAT_RIGHT(
        R.string.act_seat_heat_r, ActionGroup.COMFORT,
        number(0, 3), "SET_SEAT_HEAT_RIGHT"
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_STEERING_HEAT(
        R.string.act_steering_heat, ActionGroup.COMFORT,
        ValueSpec.BOOL, "SET_STEERING_HEAT"
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_SCREEN_BRIGHTNESS(
        R.string.act_brightness, ActionGroup.COMFORT,
        number(VehicleEnums.BRIGHTNESS_MIN, VehicleEnums.BRIGHTNESS_MAX, R.string.unit_percent),
        "SET_SCREEN_BRIGHTNESS"
    ),

    // ── Audio ────────────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_MEDIA_VOLUME(
        R.string.act_media_volume, ActionGroup.AUDIO,
        ValueSpec.dynamicNumber(0, VehicleEnums.MEDIA_VOLUME_FALLBACK_MAX),
        "SET_MEDIA_VOLUME"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_AUDIO_BALANCE(
        R.string.act_balance, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_AUDIO_BALANCE"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_AUDIO_FADER(
        R.string.act_fader, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_AUDIO_FADER"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_TONE_CONTROL(
        R.string.act_tone, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_TONE_CONTROL"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_BOSE_SOUND_TYPE(
        R.string.act_bose, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_BOSE_SOUND_TYPE"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_3D_EFFECT(
        R.string.act_3d, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_3D_EFFECT"
    ),
    @SupportedOn(SWI133, SWI132)
    SET_SPEED_VOLUME(
        R.string.act_speed_volume, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_SPEED_VOLUME"
    ),

    // ── ADAS (gated) ─────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_ENABLED(
        R.string.act_aeb, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_AEB_ENABLED", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_MODE(
        R.string.act_aeb_mode, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.AEB_MODES),
        "SET_AEB_MODE", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_SENSITIVITY(
        R.string.act_aeb_sensitivity, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.SENSITIVITIES),
        "SET_AEB_SENSITIVITY", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ELK_MODE(
        R.string.act_elk_mode, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.ELK_MODES),
        "SET_ELK_MODE", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ELK_SENSITIVITY(
        R.string.act_elk_sensitivity, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.SENSITIVITIES),
        "SET_ELK_SENSITIVITY", gated = true
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ACC_TJA_MODE(
        R.string.act_acc_tja, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.ACC_TJA_MODES),
        "SET_ACC_TJA_MODE", gated = true
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_LIMITER_MODE(
        R.string.act_limiter, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.LIMITER_MODES),
        "SET_LIMITER_MODE", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_TSR(
        R.string.act_tsr, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_TSR", gated = true
    ),
    @SupportedOn(SWI133, SWI132)
    SET_OVERSPEED_ALARM(
        R.string.act_overspeed, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_OVERSPEED_ALARM", gated = true
    ),
    @SupportedOn(SWI133, SWI132)
    SET_SPEED_LIMIT_TONE(
        R.string.act_speed_limit_tone, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_SPEED_LIMIT_TONE", gated = true
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_SOUND_WARNING(
        R.string.act_sound_warning, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_SOUND_WARNING", gated = true
    ),
    @SupportedOn(SWI132)
    SET_LAS_WARNING_SOUND(
        R.string.act_las_sound, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_LAS_WARNING_SOUND"
    ),
    @SupportedOn(SWI132)
    SET_LAS_WARNING_VIBRATION(
        R.string.act_las_vibration, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_LAS_WARNING_VIBRATION"
    ),

    // ── System (local, no vehicle access, firmware-independent) ──────────────
    LAUNCH_APP(
        R.string.act_launch_app, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.APP), bridgeAction = null
    ),
    SHOW_NOTIFICATION(
        R.string.act_notify, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.TEXT), bridgeAction = null
    );

    companion object {
        fun byGroup(): Map<ActionGroup, List<ActionType>> = entries.groupBy { it.group }
    }
}
