package com.evsuite.tasker.util

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.Uri
import com.evsuite.hardware.AppLogger

/**
 * Sending a text message through the phone the car is paired with.
 *
 * The head unit has no SIM, so nothing here can send a message on its own, and the vendor
 * hands-free service is no help either: its `IBtCall` interface carries twenty-six
 * transactions and not one of them is a message. What the car does have is the Bluetooth
 * **Message Access Profile** in its client role — the head unit's own Bluetooth settings
 * manage it as "MAP Client" (profile 18, UUIDs MAP/MNS/MAS), which is how a car reads and
 * replies to the messages of the phone it is paired with.
 *
 * So the message is handed to that profile, and the *phone* sends it. Two consequences worth
 * knowing before writing a rule around this:
 *
 *  - the profile has to be up, which needs the phone to advertise MNS and to have granted the
 *    car access to its messages. A phone that only allows calls and contacts answers nothing
 *    here, and so does an iPhone, which does not expose message sending over MAP;
 *  - `sendMessage` is hidden API, reachable because this app is platform-signed — the same
 *    reason `isConnected()` and the client-side profile constants are reachable in
 *    [BtDevices]. It is called by reflection so a platform without it fails as an unsupported
 *    action rather than as a missing class at startup.
 *
 * Nothing here retries. A message whose send call failed may still have left the phone, and
 * the one thing worse than a message that did not go out is the same message going out twice.
 */
object BtMessaging {

    private const val TAG = "EVTasker.Sms"

    /**
     * `BluetoothProfile.MAP_CLIENT`, which is `@hide` — hence the literal, exactly as the
     * client-side profile constants in [BtDevices] are literals.
     */
    private const val PROFILE_MAP_CLIENT = 18

    /** Whether the message left for the phone, and what to put in the history when it did not. */
    data class Result(val ok: Boolean, val detail: String)

    /**
     * Asks for the MAP proxy so the first rule that sends a message already has one.
     *
     * Same reason [BtDevices.warmUp] exists: the proxy arrives on a callback, so the first
     * question asked of it cannot be answered.
     */
    fun warmUp(context: Context) {
        BtDevices.proxy(context, PROFILE_MAP_CLIENT)
    }

    /** True when a paired phone is connected on the message profile right now. */
    fun isAvailable(context: Context): Boolean = messagingPhone(context) != null

    /** The phone that would carry the message, for the diagnostic report. */
    fun connectedPhoneName(context: Context): String? =
        messagingPhone(context)?.let { device ->
            try { device.name } catch (_: SecurityException) { null }
        }

    /**
     * Hands [message] to the paired phone, addressed to [number].
     *
     * @return [Result.ok] false with the reason when the profile is absent, no phone is
     * connected on it, the platform has no `sendMessage`, or the stack refused the message.
     */
    fun send(context: Context, number: String, message: String): Result {
        val proxy = BtDevices.proxy(context, PROFILE_MAP_CLIENT)
            ?: return Result(false, "message profile not bound on this car")
        val device = connectedDevices(proxy).firstOrNull()
            ?: return Result(false, "no phone connected on the message profile")
        val send = proxy.javaClass.methods.firstOrNull {
            it.name == "sendMessage" && it.parameterTypes.size == 5
        } ?: return Result(false, "sendMessage() absent on this platform")

        // "tel:" is the scheme the profile reads the number back out of — it builds the
        // recipient's vCard from the scheme-specific part.
        val recipient = Uri.parse("tel:" + ContactDirectory.normalize(number))
        val contacts = recipientArgument(send.parameterTypes[1], recipient)
            ?: return Result(false, "sendMessage() takes an unknown recipient type")

        return try {
            val accepted = send.invoke(proxy, device, contacts, message, null, null) as? Boolean ?: false
            val phone = try { device.name } catch (_: SecurityException) { null }
            if (accepted) {
                AppLogger.i(TAG, "message handed to ${phone ?: device.address}")
                Result(true, "sent via ${phone ?: "the paired phone"}")
            } else {
                Result(false, "the phone refused the message")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "sendMessage(): ${e.message}")
            Result(false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /**
     * The recipient list in the shape this platform's `sendMessage` wants.
     *
     * It took a `Uri[]` up to Android 11 and a `Collection<Uri>` after it. Reading the
     * parameter type is what keeps one call working on both without asking which Android this
     * is — and returns null on a third shape rather than passing an argument that would only
     * fail inside the stack.
     */
    private fun recipientArgument(parameterType: Class<*>, recipient: Uri): Any? = when {
        parameterType.isArray -> arrayOf(recipient)
        parameterType.isAssignableFrom(List::class.java) -> listOf(recipient)
        else -> null
    }

    private fun messagingPhone(context: Context): BluetoothDevice? {
        val proxy = BtDevices.proxy(context, PROFILE_MAP_CLIENT) ?: return null
        return connectedDevices(proxy).firstOrNull()
    }

    private fun connectedDevices(proxy: BluetoothProfile): List<BluetoothDevice> = try {
        proxy.connectedDevices.orEmpty()
    } catch (e: Exception) {
        AppLogger.d(TAG, "getConnectedDevices($PROFILE_MAP_CLIENT): ${e.message}")
        emptyList()
    }
}
