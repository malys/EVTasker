package com.evsuite.tasker.store

import android.content.Context

/**
 * Master switch: turns automation (ignition → rules) off without uninstalling the app or
 * touching the rules themselves. RulesFragment's "Test now" stays active — that is an
 * explicit manual action, not automation.
 */
object AppState {

    private const val PREFS = "ev_tasker_settings"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
    private const val KEY_WRITE_CONSENT_VERSION = "write_consent_version"
    private const val KEY_EXPERT_RULES_ENABLED = "expert_rules_enabled"
    private const val KEY_WEATHER_FALLBACK_PLACE = "weather_fallback_place"
    const val WRITE_CONSENT_VERSION = 1

    fun isAutomationEnabled(context: Context): Boolean =
        hasCurrentWriteConsent(context) &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOMATION_ENABLED, false)

    fun setAutomationEnabled(context: Context, enabled: Boolean) {
        val accepted = !enabled || hasCurrentWriteConsent(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled && accepted).apply()
    }

    fun hasCurrentWriteConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WRITE_CONSENT_VERSION, 0) == WRITE_CONSENT_VERSION

    fun acceptCurrentWriteConsent(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_WRITE_CONSENT_VERSION, WRITE_CONSENT_VERSION).apply()
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
     * Name of the saved place weather conditions use when the car has had no position for a
     * day. A name rather than a copy of the point: saving the place again moves the fallback
     * with it, and a deleted place simply stops resolving. Null when none is chosen.
     */
    fun weatherFallbackPlace(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WEATHER_FALLBACK_PLACE, null)

    fun setWeatherFallbackPlace(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WEATHER_FALLBACK_PLACE, name).apply()
    }
}
