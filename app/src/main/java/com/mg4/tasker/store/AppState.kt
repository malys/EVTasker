package com.mg4.tasker.store

import android.content.Context

/**
 * Master switch: turns automation (ignition → rules) off without uninstalling the app or
 * touching the rules themselves. RulesFragment's "Test now" stays active — that is an
 * explicit manual action, not automation.
 */
object AppState {

    private const val PREFS = "mg4_tasker_settings"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"

    fun isAutomationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOMATION_ENABLED, true)

    fun setAutomationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply()
    }
}
