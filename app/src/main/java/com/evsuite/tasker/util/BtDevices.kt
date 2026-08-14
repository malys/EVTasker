package com.evsuite.tasker.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.evsuite.hardware.AppLogger

/**
 * Bluetooth devices paired with, and connected to, the vehicle.
 *
 * Read locally rather than through the bridge: this is ordinary system data, and routing it
 * over IPC would have widened the contract for nothing.
 */
object BtDevices {

    private const val TAG = "EVTasker.Bt"

    data class Device(val name: String, val mac: String)

    fun bonded(context: Context): List<Device> {
        val adapter = adapter(context) ?: return emptyList()
        return try {
            (adapter.bondedDevices ?: emptySet<BluetoothDevice>())
                .filter { it.name != null }
                .map { Device(it.name, it.address) }
                .sortedBy { it.name }
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT denied: the editor shows "no device" rather than
            // crashing while someone is creating a rule.
            emptyList()
        }
    }

    fun bondedNamesByMac(context: Context): Map<String, String> =
        bonded(context).associate { it.mac to it.name }

    /**
     * True when the Bluetooth state is knowable at all: an adapter exists and the radio is on.
     *
     * With the radio off, a rule about a connected phone is *unavailable*, not false — the
     * same "unreadable ≠ false" rule the vehicle signals follow. Reporting "not connected"
     * instead would fire every `phone NOT connected` rule on a car whose Bluetooth is simply
     * switched off.
     */
    fun isAvailable(context: Context): Boolean =
        try { adapter(context)?.isEnabled == true } catch (_: SecurityException) { false }

    /**
     * MAC addresses connected **right now**, asked of the stack rather than remembered.
     *
     * The ACL_CONNECTED broadcast the vehicle service listens to only reports transitions
     * happening while the service is up. A phone that reconnects during boot — the normal
     * case at ignition, which is exactly when rules run — produces no transition the service
     * can see, so a set fed only by broadcasts stays empty and every Bluetooth rule reads as
     * "not connected". This asks instead; the broadcasts still keep it fresh in between.
     *
     * `isConnected()` is hidden API, reachable because this app is platform-signed. When it
     * is not reachable this returns nothing rather than guessing from the paired list, and
     * the broadcast-fed set remains the only answer — today's behaviour.
     */
    fun connected(context: Context): Set<String> {
        val adapter = adapter(context) ?: return emptySet()
        return try {
            if (!adapter.isEnabled) return emptySet()
            (adapter.bondedDevices ?: emptySet<BluetoothDevice>())
                .filter { isConnected(it) }
                .map { it.address }
                .toSet()
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "connected(): BLUETOOTH_CONNECT denied — ${e.message}")
            emptySet()
        }
    }

    /**
     * The hands-free profile a **car** plays: it is the client, the phone is the gateway.
     *
     * `BluetoothProfile.HEADSET` (1) is the gateway side — what a phone exposes to a headset.
     * The MG4's own call stack binds `BluetoothHeadsetClient`, so asking the adapter for the
     * active HEADSET device on this head unit answers about a role it never takes, and the
     * `phone is hands-free` condition could only ever come back empty. The constant is hidden
     * (`@SystemApi`), hence the literal.
     */
    private const val PROFILE_HEADSET_CLIENT = 16

    /** Same reasoning for media: the car is the A2DP sink, the phone is the source. */
    private const val PROFILE_A2DP_SINK = 11

    /** Profiles to ask, in the order that best answers "which phone is the driver using". */
    private val PROFILES = intArrayOf(PROFILE_HEADSET_CLIENT, PROFILE_A2DP_SINK, BluetoothProfile.HEADSET)

    /**
     * Proxies acquired once and kept for the life of the process.
     *
     * `getProfileProxy` answers on a callback, so the first question cannot be answered
     * synchronously. Holding the proxies is what makes every later question free — and
     * [warmUp] asks for them at service start, long before the first rule cycle.
     */
    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()
    private val proxyRequested = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** Set once `getActiveDevices` has been shown not to exist, so we stop reflecting on it. */
    @Volatile private var activeDevicesApiMissing = false

    /**
     * Asks the Bluetooth stack for its profile proxies so the first rule cycle already has
     * an answer. Call once, early; safe to repeat.
     */
    fun warmUp(context: Context) {
        val adapter = adapter(context) ?: return
        PROFILES.forEach { requestProxy(context, adapter, it) }
    }

    /**
     * MAC addresses the head unit has made active, or null when unknowable.
     *
     * The distinction matters as much as everywhere else: an empty set means "the head unit
     * has chosen no phone", null means "we could not ask", and only the second must leave a
     * rule unevaluated rather than answering no.
     *
     * Two routes, because one of them does not exist on this car. `getActiveDevices(int)` is
     * the direct question, but it was only added in Android 11 and the MG4 head unit runs
     * API 28 — the diagnostic log shows it failing as `getActiveDevices [int]`, a missing
     * method, on every cycle. So the fallback is the profile proxy, which API 28 does have:
     * whichever phone is *connected on the hands-free profile* is the one the car is using.
     *
     * `HEADSET_CLIENT` rather than `HEADSET`: a car is the hands-free client and the phone is
     * the gateway. Asking for the active HEADSET device asks about a role the head unit never
     * takes — its own call stack binds `BluetoothHeadsetClient`. The constant is hidden
     * (`@SystemApi`), hence the literal.
     *
     * The first profile that answers with a device wins; one that answers "none" does not
     * stop the search, because a car with no call in progress still knows whose phone plays
     * the music.
     */
    fun activeHandsFree(context: Context): Set<String>? {
        val adapter = adapter(context) ?: return null
        if (try { !adapter.isEnabled } catch (_: SecurityException) { true }) return null
        var answered = false
        for (profile in PROFILES) {
            val macs = activeDevices(adapter, profile) ?: connectedOnProfile(context, adapter, profile)
            if (macs == null) continue
            answered = true
            if (macs.isNotEmpty()) return macs
        }
        return if (answered) emptySet() else null
    }

    /** Active devices for one profile, or null when the platform would not answer for it. */
    private fun activeDevices(adapter: BluetoothAdapter, profile: Int): Set<String>? {
        if (activeDevicesApiMissing) return null
        return try {
            val devices = BluetoothAdapter::class.java
                .getMethod("getActiveDevices", Int::class.javaPrimitiveType)
                .invoke(adapter, profile) as? List<*>
            devices?.filterIsInstance<BluetoothDevice>()?.map { it.address }?.toSet()
        } catch (e: NoSuchMethodException) {
            // Android 10 and below. Noted once: the fallback below is the normal path there,
            // and logging a missing method every cycle only hides the real Bluetooth lines.
            activeDevicesApiMissing = true
            AppLogger.i(TAG, "getActiveDevices() absent on this platform — using profile proxies")
            null
        } catch (e: Exception) {
            AppLogger.d(TAG, "getActiveDevices($profile) refused: ${e.message}")
            null
        }
    }

    /** Devices connected on one profile, or null while the proxy is not (yet) available. */
    private fun connectedOnProfile(context: Context, adapter: BluetoothAdapter, profile: Int): Set<String>? {
        val proxy = proxies[profile] ?: run { requestProxy(context, adapter, profile); return null }
        return try {
            proxy.connectedDevices.map { it.address }.toSet()
        } catch (e: Exception) {
            AppLogger.d(TAG, "getConnectedDevices($profile): ${e.message}")
            null
        }
    }

    private fun requestProxy(context: Context, adapter: BluetoothAdapter, profile: Int) {
        if (proxyRequested.putIfAbsent(profile, true) != null) return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(which: Int, proxy: BluetoothProfile) {
                proxies[which] = proxy
                AppLogger.i(TAG, "profile proxy $which ready")
            }
            override fun onServiceDisconnected(which: Int) {
                proxies.remove(which)
                // Allow a later re-acquisition: the stack drops proxies when the radio cycles.
                proxyRequested.remove(which)
            }
        }
        val ok = try {
            adapter.getProfileProxy(context.applicationContext, listener, profile)
        } catch (e: Exception) {
            AppLogger.d(TAG, "getProfileProxy($profile): ${e.message}"); false
        }
        if (!ok) proxyRequested.remove(profile)
    }

    private fun isConnected(device: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
        AppLogger.d(TAG, "isConnected() unreachable on this platform: ${e.message}")
        false
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
