package com.mg4.tasker.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mg4.hardware.AppLogger
import com.mg4.hardware.MG4Hardware
import com.mg4.hardware.VehicleWriteGate
import com.mg4.tasker.store.AppState
import com.mg4.tasker.util.Notifier
import com.mg4.tasker.vehicle.BtTracker
import com.mg4.tasker.vehicle.ProfileBridge
import com.mg4.tasker.vehicle.RuleCycle
import kotlin.concurrent.thread

/**
 * MG4Tasker's own persistent vehicle service — this is what makes the app independent.
 *
 * It initialises MG4Hardware, listens for ignition directly (no MG4Control broadcast), and
 * on each RUN evaluates the rules and applies actions straight through MG4Hardware. It also
 * tracks connected Bluetooth devices for the context conditions.
 *
 * MG4Control is optional. When it is present the service warns once that two apps can write
 * the car (accepted concurrency risk), and the "apply profile" action becomes usable.
 */
class TaskerVehicleService : Service() {

    companion object {
        private const val TAG = "MG4Tasker.Vehicle"
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TaskerVehicleService::class.java))
        }
    }

    private var ignitionListener: ((Int) -> Unit)? = null
    private var btReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifier.FOREGROUND_NOTIFICATION_ID, Notifier.buildForegroundNotification(this))
        AppLogger.i(TAG, "onCreate — initialising vehicle layer")

        VehicleWriteGate.messageProvider = { decision ->
            when (decision) {
                VehicleWriteGate.Decision.REFUSED_MOVING        -> getString(com.mg4.tasker.R.string.verdict_moving)
                VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED -> getString(com.mg4.tasker.R.string.verdict_unknown_speed)
                VehicleWriteGate.Decision.ALLOWED               -> null
            }
        }
        MG4Hardware.init(applicationContext)
        registerBtReceiver()
        registerIgnitionListener()
        warnIfMG4ControlPresent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        ignitionListener?.let { MG4Hardware.unregisterVehicleConditionListener(it) }
        btReceiver?.let { runCatching { unregisterReceiver(it) } }
    }

    private fun registerIgnitionListener() {
        val listener: (Int) -> Unit = { state ->
            if (state == MG4Hardware.CarIgnitionItem.RUN) {
                if (AppState.isAutomationEnabled(this)) {
                    AppLogger.i(TAG, "Ignition RUN → evaluating rules")
                    thread(name = "mg4-tasker-cycle") { RuleCycle.run(this, "IGNITION_ON") }
                } else {
                    AppLogger.i(TAG, "Ignition RUN but automation disabled — ignored")
                }
            }
        }
        ignitionListener = listener
        MG4Hardware.registerVehicleConditionListener(listener)
    }

    private fun registerBtReceiver() {
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val mac = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> BtTracker.add(mac)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> BtTracker.remove(mac)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun warnIfMG4ControlPresent() {
        if (ProfileBridge.isMG4ControlInstalled(this)) {
            AppLogger.w(TAG, "MG4Control is installed — both apps can write the vehicle")
            Notifier.showRuleMessage(this, getString(com.mg4.tasker.R.string.warn_mg4control_present))
        }
    }
}
