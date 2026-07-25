package com.mg4.tasker

import android.app.Application
import com.mg4.hardware.AppLogger
import com.mg4.tasker.debug.CrashLogger
import com.mg4.tasker.util.Notifier

class MG4TaskerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the chosen display language before any UI is built (default follows the OS).
        com.mg4.tasker.store.LanguageStore.apply(this)
        // Installed first: a crash during the rest of onCreate is exactly the kind of
        // failure that otherwise leaves nothing behind on a vehicle.
        CrashLogger.install(this)
        // The channel must exist before the run service starts in the foreground, which
        // can happen at ignition without any activity ever having been opened.
        Notifier.ensureChannel(this)
        AppLogger.i("App", "MG4Tasker started")
        // Start the persistent vehicle service so ignition triggers even before the UI is
        // opened. BootReceiver does the same at boot.
        com.mg4.tasker.service.TaskerVehicleService.start(this)
        // Compute the supported-feature set once per app version so the rule pickers can be
        // filled from a stored list instead of reflecting the catalogue on every open. Off
        // the main thread: MG4Hardware init and system-property reads block.
        kotlin.concurrent.thread(name = "mg4-tasker-support-check") {
            runCatching { com.mg4.tasker.store.SupportChecker.ensureChecked(this) }
        }
    }
}
