package com.mg4.tasker.model

import androidx.annotation.StringRes

/**
 * The kind of value a condition or action manipulates.
 *
 * This is what keeps the UI small: the editor knows no individual condition or action, it
 * only knows how to draw one control per [ValueKind]. Adding a catalogue entry is
 * therefore one enum line, not another screen.
 */
enum class ValueKind {
    /** On/off switch. */
    BOOL,
    /** Bounded slider (see [ValueSpec.min] / [ValueSpec.max]). */
    NUMBER,
    /** Closed list of named values ([ValueSpec.options]). */
    ENUM,
    /** Paired Bluetooth device, identified by MAC address. */
    BT_DEVICE,
    /** Start → end time range. */
    TIME_RANGE,
    /** Day-of-week selection. */
    DAYS,
    /** MG4Control driving profile, identified by id. */
    PROFILE,
    /** Installed application, identified by package name. */
    APP,
    /** Free text (notification message). */
    TEXT,
    /** Nothing to enter. */
    NONE
}

/** One named value of a [ValueKind.ENUM]. */
data class EnumOption(val value: Int, @StringRes val labelRes: Int)

/**
 * Description of the input control.
 *
 * [max] is -1 when the bound is only known at runtime — the maximum media volume depends
 * on the firmware and is readable only from the vehicle snapshot. The editor substitutes
 * the real value, falling back to [fallbackMax] when the car does not answer.
 */
data class ValueSpec(
    val kind: ValueKind,
    val min: Int = 0,
    val max: Int = 0,
    @StringRes val unitRes: Int = 0,
    val options: List<EnumOption> = emptyList(),
    val fallbackMax: Int = 0
) {
    companion object {
        val NONE = ValueSpec(ValueKind.NONE)
        val BOOL = ValueSpec(ValueKind.BOOL)

        fun number(min: Int, max: Int, @StringRes unitRes: Int = 0) =
            ValueSpec(ValueKind.NUMBER, min = min, max = max, unitRes = unitRes)

        /** Upper bound resolved at runtime from the vehicle snapshot. */
        fun dynamicNumber(min: Int, fallbackMax: Int, @StringRes unitRes: Int = 0) =
            ValueSpec(ValueKind.NUMBER, min = min, max = -1, unitRes = unitRes, fallbackMax = fallbackMax)

        fun enum(vararg options: EnumOption) =
            ValueSpec(ValueKind.ENUM, options = options.toList())
    }
}
