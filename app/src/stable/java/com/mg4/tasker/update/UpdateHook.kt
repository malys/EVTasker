package com.mg4.tasker.update

import android.content.Context

/**
 * Stable channel: no self-update, by construction.
 *
 * This is not a disabled feature — the updater class is not in the APK at all. A stable
 * build cannot be made to fetch and install code by flipping a preference, and there is no
 * update URL in it to attack.
 */
object UpdateHook {
    /** Does nothing. Stable users install updates themselves. */
    fun checkInBackground(context: Context) {}
    fun isSupported(): Boolean = false
}
