package com.mg4.tasker.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.mg4.control.tasker.ITaskerBridge
import com.mg4.hardware.AppLogger
import com.mg4.tasker.model.Snapshot
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client of the MG4Control bridge.
 *
 * Binds on demand and unbinds as soon as the cycle ends: MG4Tasker has no reason to hold
 * a permanent connection, it only works at vehicle start.
 *
 * ⚠️ [connect] BLOCKS. Call it from a background thread only — never from the main
 * thread, where the wait would freeze the vehicle screen.
 */
class BridgeClient(private val context: Context) {

    companion object {
        private const val TAG = "MG4Tasker.Bridge"

        /**
         * MG4Control initialises its vehicle layers at startup (android.car binding,
         * Katman reflection). Binding too early yields a reachable service whose reads
         * all return "unreadable". Give the bind time to complete.
         */
        private const val BIND_TIMEOUT_SECONDS = 15L
    }

    private var bridge: ITaskerBridge? = null
    private var connection: ServiceConnection? = null

    val isConnected: Boolean get() = bridge != null

    /** @return true if the bridge is reachable. false = MG4Control missing, stopped, or refused. */
    fun connect(): Boolean {
        if (bridge != null) return true

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bridge = ITaskerBridge.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bridge = null
            }

            override fun onNullBinding(name: ComponentName?) {
                // The service exists but refuses to hand out a binder: no point waiting.
                latch.countDown()
            }
        }

        val intent = Intent().apply {
            component = ComponentName(BridgeContract.MG4CONTROL_PACKAGE, BridgeContract.BRIDGE_SERVICE)
        }

        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // Different signature: MG4Tasker was not signed with the platform key.
            AppLogger.w(TAG, "bind refused (signature permission): ${e.message}")
            false
        }

        if (!bound) {
            runCatching { context.unbindService(conn) }
            return false
        }

        connection = conn
        latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (bridge == null) {
            AppLogger.w(TAG, "MG4Control did not answer within ${BIND_TIMEOUT_SECONDS}s")
            disconnect()
            return false
        }
        return true
    }

    fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        bridge = null
    }

    /**
     * Vehicle snapshot enriched with local context (time, day).
     *
     * Unreachable bridge → empty snapshot with `bridgeAvailable = false`. Every vehicle
     * condition then becomes unavailable and no rule fires, which is the intended
     * behaviour: without MG4Control we know nothing about the car.
     */
    fun readSnapshot(): Snapshot {
        val calendar = Calendar.getInstance()
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val day = calendar.get(Calendar.DAY_OF_WEEK)

        val remote = bridge ?: return Snapshot(
            minutesOfDay = minutes, dayOfWeek = day, bridgeAvailable = false
        )

        return try {
            val bundle = remote.readSnapshot()
            Snapshot(
                readings = bundle.toReadings(),
                btMacs = bundle.getStringArray(BridgeContract.KEY_BT_MACS)?.toSet() ?: emptySet(),
                minutesOfDay = minutes,
                dayOfWeek = day,
                bridgeAvailable = true
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "readSnapshot failed: ${e.message}")
            Snapshot(minutesOfDay = minutes, dayOfWeek = day, bridgeAvailable = false)
        }
    }

    /** MG4Control profiles as id → name. Empty when the bridge is unreachable. */
    fun listProfiles(): List<Pair<String, String>> {
        val remote = bridge ?: return emptyList()
        return try {
            val bundle = remote.listProfiles()
            val ids = bundle.getStringArray("ids") ?: return emptyList()
            val names = bundle.getStringArray("names") ?: return emptyList()
            ids.zip(names)
        } catch (e: Exception) {
            AppLogger.w(TAG, "listProfiles failed: ${e.message}")
            emptyList()
        }
    }

    fun applyProfile(profileId: String): Bundle? = try {
        bridge?.applyProfile(profileId)
    } catch (e: Exception) {
        AppLogger.w(TAG, "applyProfile failed: ${e.message}"); null
    }

    fun applyAction(actionType: String, params: Bundle): Bundle? = try {
        bridge?.applyAction(actionType, params)
    } catch (e: Exception) {
        AppLogger.w(TAG, "applyAction($actionType) failed: ${e.message}"); null
    }

    /**
     * Converts the Bundle into typed readings. Missing keys stay missing: that is the
     * "unreadable data" signal the whole engine rests on, and it must never be filled in
     * with default values.
     */
    private fun Bundle.toReadings(): Map<String, Any> = buildMap {
        for (key in keySet()) {
            if (key == BridgeContract.KEY_BT_MACS) continue
            @Suppress("DEPRECATION")
            when (val value = get(key)) {
                is Int, is Float, is Boolean, is String -> put(key, value)
                else -> Unit
            }
        }
    }
}
