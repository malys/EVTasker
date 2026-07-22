package com.mg4.tasker.util

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Bluetooth devices paired with the vehicle.
 *
 * Read locally rather than through the bridge: the pairing list is ordinary system data,
 * and routing it through IPC would have widened the contract for nothing. The
 * "connected right now" state does come from the bridge — MG4Control tracks that.
 */
object BtDevices {

    data class Device(val name: String, val mac: String)

    fun bonded(context: Context): List<Device> {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return emptyList()
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
}
