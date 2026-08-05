package com.mg4.tasker.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.mg4.control.api.IProfileControl
import com.mg4.tasker.bridge.BridgeContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The **only** thing MG4Tasker still needs MG4Control for: MG4Control driving profiles —
 * applying one, or handing the choice to the driver through MG4Control's own picker.
 * Everything else is done directly through MG4Hardware.
 *
 * Binds MG4Control's signature-protected bridge on demand and unbinds after use. When
 * MG4Control is not installed (or a different signature), [connect] fails and every call
 * returns empty/null — the "apply profile" action is simply unavailable.
 *
 * ⚠️ [connect] blocks; call off the main thread.
 */
class ProfileBridge(private val context: Context) {

    companion object {
        private const val TAG = "MG4Tasker.Profile"
        private const val BIND_TIMEOUT_SECONDS = 8L

        /** True if MG4Control is installed (the profile actions depend on it). */
        fun isMG4ControlInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(BridgeContract.MG4CONTROL_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private var bridge: IProfileControl? = null
    private var connection: ServiceConnection? = null

    fun connect(): Boolean {
        if (bridge != null) return true
        if (!isMG4ControlInstalled(context)) return false

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bridge = IProfileControl.Stub.asInterface(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { bridge = null }
            override fun onNullBinding(name: ComponentName?) { latch.countDown() }
        }
        val intent = Intent().apply {
            component = ComponentName(BridgeContract.MG4CONTROL_PACKAGE, BridgeContract.BRIDGE_SERVICE)
        }
        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bind refused (signature): ${e.message}"); false
        }
        if (!bound) { runCatching { context.unbindService(conn) }; return false }
        connection = conn
        latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (bridge == null) { disconnect(); return false }
        return true
    }

    fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null; bridge = null
    }

    /** MG4Control profiles as id → name. Empty when MG4Control is absent/unreachable. */
    fun listProfiles(): List<Pair<String, String>> {
        val remote = bridge ?: return emptyList()
        return try {
            val b = remote.listProfiles()
            val ids = b.getStringArray("ids") ?: return emptyList()
            val names = b.getStringArray("names") ?: return emptyList()
            ids.zip(names)
        } catch (e: Exception) {
            Log.w(TAG, "listProfiles failed: ${e.message}"); emptyList()
        }
    }

    fun applyProfile(profileId: String): Bundle? = try {
        bridge?.applyProfile(profileId)
    } catch (e: Exception) {
        Log.w(TAG, "applyProfile failed: ${e.message}"); null
    }

    /** Asks MG4Control to put its profile picker in front of the driver. */
    fun showProfilePicker(): Bundle? = try {
        bridge?.showProfilePicker()
    } catch (e: Exception) {
        Log.w(TAG, "showProfilePicker failed: ${e.message}"); null
    }
}
