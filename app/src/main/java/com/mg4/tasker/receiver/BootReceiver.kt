package com.mg4.tasker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4.hardware.AppLogger
import com.mg4.tasker.service.TaskerVehicleService

/**
 * Starts the persistent vehicle service at boot so MG4Tasker catches the very first
 * ignition without the app being opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i("MG4Tasker.Boot", "boot: ${intent.action} → starting vehicle service")
        TaskerVehicleService.start(context)
    }
}
