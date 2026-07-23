package com.mg4.tasker.update

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Unstable channel: checks GitHub pre-releases and downloads a newer unstable APK.
 *
 * The install itself stays manual — the user taps the downloaded file. The app is not
 * privileged enough to install silently, and asking for that privilege to save one tap on a
 * test channel is not a trade worth making.
 */
object UpdateHook {

    private const val TAG = "UpdateHook"

    fun isSupported(): Boolean = true

    /** Fire-and-forget check. Network work runs off the main thread. */
    fun checkInBackground(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                val current = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                    ?: return@Thread
                val update = OtaUpdater.check(current)
                if (update == null) {
                    Log.i(TAG, "No newer unstable than $current"); return@Thread
                }

                // Anything already downloaded is verified before the user is pointed at it:
                // a file in public Downloads can be swapped by another app.
                val existing = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    OtaUpdater.downloadFileName(update.versionName)
                )
                if (existing.isFile) {
                    if (!OtaUpdater.signatureMatchesRunningApp(app, existing)) {
                        val deleted = existing.delete()
                        Log.w(TAG, "Rejected a foreign-signed update (deleted=$deleted)")
                    } else {
                        Log.i(TAG, "Update already downloaded and verified: ${existing.name}")
                    }
                    return@Thread
                }

                OtaUpdater.download(app, update)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Downloading unstable ${update.versionName}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }, "ota-check").start()
    }
}
