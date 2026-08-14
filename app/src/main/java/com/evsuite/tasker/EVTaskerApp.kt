package com.evsuite.tasker

import android.app.Application
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.diag.CrashLogger
import com.evsuite.tasker.util.Notifier

class EVTaskerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the chosen display language before any UI is built (default follows the OS).
        com.evsuite.tasker.store.LanguageStore.apply(this)
        // Installed first: a crash during the rest of onCreate is exactly the kind of
        // failure that otherwise leaves nothing behind on a vehicle.
        CrashLogger.install(this, "EVTasker")
        // The channel must exist before the run service starts in the foreground, which
        // can happen at ignition without any activity ever having been opened.
        Notifier.ensureChannel(this)
        AppLogger.i("App", "EVTasker started")
        // Start the persistent vehicle service so ignition triggers even before the UI is
        // opened. BootReceiver does the same at boot.
        com.evsuite.tasker.service.TaskerVehicleService.start(this)
        // Compute the supported-feature set once per app version so the rule pickers can be
        // filled from a stored list instead of reflecting the catalogue on every open. Off
        // the main thread: EVHardware init and system-property reads block.
        kotlin.concurrent.thread(name = "mg4-tasker-support-check") {
            com.evsuite.hardware.FirmwareInfo.initWithContext(this)
            val firmware = com.evsuite.hardware.FirmwareInfo.getGeneration()
            val exact = com.evsuite.hardware.FirmwareInfo.getDetectedString()
            AppLogger.i(
                "App",
                "EVTasker ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
                    "firmware=$firmware exact=$exact"
            )
            runCatching { com.evsuite.tasker.store.SupportChecker.ensureChecked(this) }
        }
    }
}
