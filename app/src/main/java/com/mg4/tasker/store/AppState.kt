package com.mg4.tasker.store

import android.content.Context
import com.mg4.hardware.VehicleWriteGate

/**
 * Master switch: turns automation (ignition → rules) off without uninstalling the app or
 * touching the rules themselves. RulesFragment's "Test now" stays active — that is an
 * explicit manual action, not automation.
 */
object AppState {

    private const val PREFS = "mg4_tasker_settings"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
    private const val KEY_WRITE_THRESHOLD = "write_threshold_kmh"
    private const val KEY_EXPERT_RULES_ENABLED = "expert_rules_enabled"

    fun isAutomationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, true)

    fun setAutomationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply()
    }

    /** Controls editor affordances only; stored rules and engine behaviour never depend on it. */
    fun areExpertRulesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPERT_RULES_ENABLED, false)

    fun setExpertRulesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPERT_RULES_ENABLED, enabled).apply()
    }

    /**
     * Speed up to which a gated write is allowed, in km/h.
     *
     * Zero — standstill only — is the default, and the value that needs no justification.
     * Above it the app stops being the one that refuses; the vehicle still refuses whatever
     * it refuses, so raising this does not make a drive-mode change succeed at 40 km/h, it
     * only stops MG4Tasker from declining first. Capped by
     * [VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH].
     */
    fun writeThresholdKmh(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_WRITE_THRESHOLD, 0f)
            .coerceIn(0f, VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH)

    fun setWriteThresholdKmh(context: Context, kmh: Float) {
        val clamped = kmh.coerceIn(0f, VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_WRITE_THRESHOLD, clamped).apply()
        VehicleWriteGate.allowUpToKmh = clamped
    }

    /**
     * Pushes the stored threshold into the gate.
     *
     * The gate lives in the shared library and starts at zero on every process start, so
     * something has to hand it the user's choice — done wherever the vehicle layer is
     * initialised, before any rule can run.
     */
    fun applyWriteThreshold(context: Context) {
        VehicleWriteGate.allowUpToKmh = writeThresholdKmh(context)
    }
}
