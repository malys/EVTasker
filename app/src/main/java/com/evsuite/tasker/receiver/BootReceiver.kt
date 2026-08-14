package com.evsuite.tasker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evsuite.hardware.AppLogger
import com.evsuite.tasker.service.TaskerVehicleService

/**
 * Starts the persistent vehicle service at boot so EVTasker catches the very first
 * ignition without the app being opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i("EVTasker.Boot", "boot: ${intent.action} → starting vehicle service")
        TaskerVehicleService.start(context)
    }
}
