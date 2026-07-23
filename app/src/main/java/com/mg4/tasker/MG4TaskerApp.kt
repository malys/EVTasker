package com.mg4.tasker

import android.app.Application
import com.mg4.hardware.AppLogger
import com.mg4.tasker.debug.CrashLogger
import com.mg4.tasker.util.Notifier

class MG4TaskerApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
    }
}
