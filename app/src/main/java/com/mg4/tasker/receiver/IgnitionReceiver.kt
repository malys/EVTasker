package com.mg4.tasker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.debug.AppLogger
import com.mg4.tasker.service.TaskerRunService
import com.mg4.tasker.store.AppState

/**
 * Wake-up on the ignition notification emitted by MG4Control.
 *
 * The receiver does nothing itself: it delegates to [TaskerRunService]. The real work
 * (binding the bridge, vehicle writes) exceeds the time a receiver is granted.
 *
 * The action check is not redundant with the manifest filter: the receiver is exported to
 * accept a broadcast from another process, so it only handles what it explicitly expects.
 */
class IgnitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MG4Tasker.Ignition"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BridgeContract.ACTION_IGNITION_ON) return
        if (!AppState.isAutomationEnabled(context)) {
            AppLogger.i(TAG, "automation disabled — ignition ignored")
            return
        }
        AppLogger.i(TAG, "ignition reported by MG4Control → starting cycle")
        TaskerRunService.start(context, TaskerRunService.TRIGGER_IGNITION)
    }
}
