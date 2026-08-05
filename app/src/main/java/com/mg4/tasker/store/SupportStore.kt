package com.mg4.tasker.store

import android.content.Context
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.BuildConfig

/**
 * Persisted record of which catalogue entries this vehicle supports.
 *
 * The support set is a pure function of (firmware generation, the @SupportedOn annotations
 * shipped in this build). It does not change until the car changes or the app is updated,
 * so it is computed once — on first launch and after each update — and cached here, rather
 * than reflected over the whole catalogue every time a picker opens (see [SupportChecker]).
 *
 * State is app-specific (its own SharedPreferences file) and survives reboots.
 */
object SupportStore {

    private const val PREFS = "mg4_tasker_support"
    private const val KEY_VERSION    = "checked_version"
    private const val KEY_GEN        = "checked_gen"
    private const val KEY_TIME       = "checked_at"
    private const val KEY_CONDITIONS = "supported_conditions"
    private const val KEY_ACTIONS    = "supported_actions"

    /** No record yet, or the record predates the current app version (annotations may have moved). */
    fun needsCheck(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_VERSION)) return true
        return prefs.getInt(KEY_VERSION, -1) != BuildConfig.VERSION_CODE
    }

    fun save(context: Context, gen: String?, conditions: Set<String>, actions: Set<String>) {
        prefs(context).edit()
            .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_GEN, gen)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putStringSet(KEY_CONDITIONS, conditions)
            .putStringSet(KEY_ACTIONS, actions)
            .apply()
    }

    /** Supported ConditionType names, or null when never checked (caller must not filter). */
    fun supportedConditions(context: Context): Set<String>? =
        stored(context, KEY_CONDITIONS)?.let { known(it, ConditionType.entries.map { e -> e.name }) }

    fun supportedActions(context: Context): Set<String>? =
        stored(context, KEY_ACTIONS)?.let { known(it, ActionType.entries.map { e -> e.name }) }

    private fun stored(context: Context, key: String): Set<String>? =
        prefs(context).takeIf { it.contains(key) }?.getStringSet(key, emptySet())?.toSet()

    /**
     * Drops names the current catalogue no longer defines.
     *
     * The record survives an update, and an update can remove entries — the four
     * `STAR_*_PRESS` conditions became one `PHYSICAL_BUTTON`. Until the next check runs,
     * the stored set still names the removed ones, which the Diagnostic screen counted as
     * supported conditions the app cannot offer any more.
     */
    private fun known(stored: Set<String>, catalogue: List<String>): Set<String> =
        stored.intersect(catalogue.toSet())

    /** Firmware and timestamp of the last check, for the Diagnostic screen; null when never run. */
    fun lastCheck(context: Context): Info? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_TIME)) return null
        return Info(prefs.getString(KEY_GEN, null), prefs.getLong(KEY_TIME, 0L))
    }

    data class Info(val gen: String?, val checkedAt: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
