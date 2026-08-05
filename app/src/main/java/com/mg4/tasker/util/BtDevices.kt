package com.mg4.tasker.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.mg4.hardware.AppLogger

/**
 * Bluetooth devices paired with, and connected to, the vehicle.
 *
 * Read locally rather than through the bridge: this is ordinary system data, and routing it
 * over IPC would have widened the contract for nothing.
 */
object BtDevices {

    private const val TAG = "MG4Tasker.Bt"

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
     * MAC addresses the head unit has made active for hands-free, or null when unknowable.
     *
     * The distinction matters as much as everywhere else: an empty set means "the head unit
     * has chosen no phone", null means "we could not ask", and only the second must leave a
     * rule unevaluated rather than answering no.
     *
     * `BluetoothAdapter.getActiveDevices(int)` is a system API, reachable with the platform
     * signature and BLUETOOTH_PRIVILEGED. Asked of the adapter rather than through a
     * `BluetoothHeadset` proxy: the proxy is acquired asynchronously and would have to be
     * held open for the life of the service to answer a question asked once per cycle.
     */
    fun activeHandsFree(context: Context): Set<String>? {
        val adapter = adapter(context) ?: return null
        return try {
            if (!adapter.isEnabled) return null
            val devices = BluetoothAdapter::class.java
                .getMethod("getActiveDevices", Int::class.javaPrimitiveType)
                .invoke(adapter, BluetoothProfile.HEADSET) as? List<*>
                ?: return null
            devices.filterIsInstance<BluetoothDevice>().map { it.address }.toSet()
        } catch (e: Exception) {
            AppLogger.d(TAG, "getActiveDevices() unreachable on this platform: ${e.message}")
            null
        }
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
