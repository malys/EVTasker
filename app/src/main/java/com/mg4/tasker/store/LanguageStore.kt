package com.mg4.tasker.store

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App display language.
 *
 * Default ([SYSTEM]) follows the OS language, falling back to English (the default
 * resources) when the OS language has no translation. The user can override it with a fixed
 * language; the choice is stored here and re-applied on every launch.
 *
 * Uses the AppCompat per-app locales API: setting it recreates the running activities with
 * the new resources, so no manual restart is needed.
 */
object LanguageStore {

    /** Sentinel tag for "follow the OS language". */
    const val SYSTEM = "system"

    private const val PREFS = "mg4_tasker_settings"
    private const val KEY_LANGUAGE = "app_language"

    /** Stored choice: a BCP-47 tag ("en", "fr") or [SYSTEM]. */
    fun getTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, tag).apply()
        apply(tag)
    }

    /** Applies the stored choice. Call once on app start. */
    fun apply(context: Context) = apply(getTag(context))

    private fun apply(tag: String) {
        val locales =
            if (tag == SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
