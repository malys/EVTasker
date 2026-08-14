package com.evsuite.tasker.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.evsuite.profile.api.IProfileControl
import com.evsuite.tasker.bridge.BridgeContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The **only** thing EVTasker still needs EVProfile for: EVProfile driving profiles —
 * applying one, or handing the choice to the driver through EVProfile's own picker.
 * Everything else is done directly through EVHardware.
 *
 * Binds EVProfile's signature-protected bridge on demand and unbinds after use. When
 * EVProfile is not installed (or a different signature), [connect] fails and every call
 * returns empty/null — the "apply profile" action is simply unavailable.
 *
 * ⚠️ [connect] blocks; call off the main thread.
 */
class ProfileBridge(private val context: Context) {

    companion object {
        private const val TAG = "EVTasker.Profile"
        private const val BIND_TIMEOUT_SECONDS = 8L

        /** True if EVProfile is installed, under any of its channels (the profile actions depend on it). */
        fun isEVProfileInstalled(context: Context): Boolean = installedPackage(context) != null

        /**
         * The EVProfile application id present on this car, or null.
         *
         * Resolved rather than assumed: the offline and unstable builds carry a suffixed id
         * and are the same app. Preference order is [BridgeContract.EVPROFILE_PACKAGES] —
         * with two channels installed, the stable one is the one bound.
         */
        fun installedPackage(context: Context): String? =
            BridgeContract.EVPROFILE_PACKAGES.firstOrNull { pkg ->
                try {
                    context.packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
    }

    private var bridge: IProfileControl? = null
    private var connection: ServiceConnection? = null

    fun connect(): Boolean {
        if (bridge != null) return true
        val pkg = installedPackage(context) ?: return false

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bridge = IProfileControl.Stub.asInterface(binder); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { bridge = null }
            override fun onNullBinding(name: ComponentName?) { latch.countDown() }
        }
        val intent = Intent().apply {
            component = ComponentName(pkg, BridgeContract.BRIDGE_SERVICE)
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

    /** EVProfile profiles as id → name. Empty when EVProfile is absent/unreachable. */
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

    /** Asks EVProfile to put its profile picker in front of the driver. */
    fun showProfilePicker(): Bundle? = try {
        bridge?.showProfilePicker()
    } catch (e: Exception) {
        Log.w(TAG, "showProfilePicker failed: ${e.message}"); null
    }
}
