package com.mg4.tasker.ui

import android.content.Context
import com.mg4.tasker.R
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.ValueKind
import java.util.Calendar
import java.util.Locale

/**
 * Renders rules as readable sentences.
 *
 * A text summary rather than a grid of fields: at vehicle start, "Outside temperature is
 * below 5 °C" reads at a glance, where "OUTSIDE_TEMP | LT | 5.0" needs mental
 * translation.
 *
 * [btNames] and [profileNames] turn stored identifiers (MAC address, profile id) into
 * names the user knows; failing that, the raw identifier is shown rather than nothing —
 * so a rule pointing at an unpaired device stays identifiable.
 */
class Labels(
    private val context: Context,
    private val btNames: Map<String, String> = emptyMap(),
    private val profileNames: Map<String, String> = emptyMap()
) {

    fun describe(condition: Condition): String {
        val name = context.getString(condition.type.labelRes)
        return when (condition.type.spec.kind) {
            ValueKind.BOOL ->
                "$name : ${context.getString(if (condition.flag) R.string.value_enabled else R.string.value_disabled)}"

            ValueKind.NUMBER -> {
                val unit = condition.type.spec.unitRes.takeIf { it != 0 }?.let { " " + context.getString(it) } ?: ""
                "$name ${operator(condition.op)} ${formatNumber(condition.number)}$unit"
            }

            ValueKind.ENUM -> {
                val value = if (condition.type.snapshotKey == BridgeContract.KEY_FIRMWARE_GEN) {
                    condition.text
                } else {
                    optionLabel(condition.type.spec.options, condition.number.toInt())
                }
                "$name ${operator(condition.op)} $value"
            }

            ValueKind.BT_DEVICE ->
                "$name : ${btNames[condition.text] ?: condition.text}"

            ValueKind.TIME_RANGE ->
                "$name ${formatTime(condition.minutesFrom)} – ${formatTime(condition.minutesTo)}"

            ValueKind.DAYS ->
                "$name : ${condition.days.sortedBy { weekOrder(it) }.joinToString(", ") { dayLabel(it) }}"

            else -> name
        }
    }

    fun describe(action: Action): String {
        val name = context.getString(action.type.labelRes)
        return when (action.type.spec.kind) {
            ValueKind.BOOL ->
                "$name : ${context.getString(if (action.flag) R.string.value_enabled else R.string.value_disabled)}"

            ValueKind.NUMBER -> {
                val unit = action.type.spec.unitRes.takeIf { it != 0 }?.let { " " + context.getString(it) } ?: ""
                "$name : ${action.number}$unit"
            }

            ValueKind.ENUM ->
                "$name : ${optionLabel(action.type.spec.options, action.number)}"

            ValueKind.PROFILE ->
                "$name : ${profileNames[action.text] ?: action.text}"

            ValueKind.APP ->
                "$name : ${appLabel(action.text)}"

            ValueKind.TEXT ->
                "$name : ${action.text}"

            else -> name
        }
    }

    /** One history line: what was attempted, and what the vehicle made of it. */
    fun describe(result: ActionResult): String =
        "${context.getString(result.actionType.labelRes)} — ${verdictLabel(result.verdict)}"

    fun verdictLabel(verdict: String): String = context.getString(
        when (verdict) {
            BridgeContract.VERDICT_ALLOWED       -> R.string.verdict_allowed
            BridgeContract.VERDICT_MOVING        -> R.string.verdict_moving
            BridgeContract.VERDICT_UNKNOWN_SPEED -> R.string.verdict_unknown_speed
            BridgeContract.VERDICT_UNSUPPORTED   -> R.string.verdict_unsupported
            BridgeContract.VERDICT_NO_BRIDGE     -> R.string.verdict_no_bridge
            else                                 -> R.string.verdict_error
        }
    )

    fun operator(op: CompareOp): String = context.getString(
        when (op) {
            CompareOp.EQ -> R.string.op_eq
            CompareOp.NE -> R.string.op_ne
            CompareOp.LT -> R.string.op_lt
            CompareOp.LE -> R.string.op_le
            CompareOp.GT -> R.string.op_gt
            CompareOp.GE -> R.string.op_ge
        }
    )

    fun dayLabel(calendarDay: Int): String = context.getString(
        when (calendarDay) {
            Calendar.MONDAY    -> R.string.day_mon
            Calendar.TUESDAY   -> R.string.day_tue
            Calendar.WEDNESDAY -> R.string.day_wed
            Calendar.THURSDAY  -> R.string.day_thu
            Calendar.FRIDAY    -> R.string.day_fri
            Calendar.SATURDAY  -> R.string.day_sat
            else               -> R.string.day_sun
        }
    )

    fun formatTime(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)

    private fun optionLabel(options: List<com.mg4.tasker.model.EnumOption>, value: Int): String =
        options.firstOrNull { it.value == value }?.let { context.getString(it.labelRes) }
            ?: value.toString()

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else String.format(Locale.getDefault(), "%.1f", value)

    /** Calendar.SUNDAY is 1: without reordering, Sunday would show up first. */
    private fun weekOrder(calendarDay: Int) = if (calendarDay == Calendar.SUNDAY) 8 else calendarDay
}
