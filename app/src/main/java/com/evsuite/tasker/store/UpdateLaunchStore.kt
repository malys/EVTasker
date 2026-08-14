package com.evsuite.tasker.store

import android.content.Context
import com.evsuite.tasker.BuildConfig

/**
 * Remembers the app version last shown in the main UI.
 *
 * A package replacement may restart background components before the driver opens the app,
 * so this marker is deliberately owned by [com.evsuite.tasker.ui.MainActivity], not by
 * Application or the vehicle service. The first user-visible launch after an upgrade can
 * therefore be routed to Diagnostic without ever opening an activity in the background.
 */
object UpdateLaunchStore {

    private const val PREFS = "ev_tasker_ui_version"
    private const val KEY_LAST_SHOWN_VERSION = "last_shown_version"

    /**
     * Records [currentVersion] and returns true only when a different version was recorded.
     * A fresh install has no previous version and keeps the normal Rules landing screen.
     */
    fun shouldOpenDiagnostic(
        context: Context,
        currentVersion: Int = BuildConfig.VERSION_CODE,
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hadPreviousVersion = prefs.contains(KEY_LAST_SHOWN_VERSION)
        val previousVersion = prefs.getInt(KEY_LAST_SHOWN_VERSION, currentVersion)
        prefs.edit().putInt(KEY_LAST_SHOWN_VERSION, currentVersion).apply()
        return hadPreviousVersion && previousVersion != currentVersion
    }
}
