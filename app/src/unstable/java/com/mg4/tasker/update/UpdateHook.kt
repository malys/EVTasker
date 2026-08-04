package com.mg4.tasker.update

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unstable channel: checks, downloads, verifies and installs a newer unstable APK.
 */
object UpdateHook {

    private const val TAG = "UpdateHook"
    private val running = AtomicBoolean(false)

    fun isSupported(): Boolean = true

    /** Fire-and-forget check. Network work runs off the main thread. */
    fun checkInBackground(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                OtaUpdater.purgeCachedApks(app)
                val current = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                    ?: return@Thread
                val update = OtaUpdater.check(current)
                if (update == null) {
                    Log.i(TAG, "No newer unstable than $current"); return@Thread
                }

                val apk = OtaUpdater.download(app, update) ?: return@Thread
                val installed = try { OtaUpdater.install(app, apk) } finally { apk.delete() }
                if (!installed) Log.w(TAG, "Automatic update installation failed")
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            } finally {
                running.set(false)
            }
        }, "ota-check").start()
    }
}
