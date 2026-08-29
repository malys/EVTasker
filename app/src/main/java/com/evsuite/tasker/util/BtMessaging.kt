package com.evsuite.tasker.util

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.evsuite.hardware.AppLogger
import java.lang.reflect.InvocationTargetException

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

    /**
     * Why a message cannot be sent — or that it can.
     *
     * The two failures are not the same problem and do not have the same remedy. A head unit
     * whose Bluetooth stack carries no MAP client will never send a message however the phone
     * is configured; a head unit that has one and no phone on it is waiting for the driver to
     * allow message access in the phone's own Bluetooth settings. Reporting both as "no phone
     * on the message profile" sent people to look at the phone for a problem in the car.
     */
    enum class Availability { READY, PROFILE_UNAVAILABLE, NO_PHONE }

    fun availability(context: Context): Availability {
        val proxy = BtDevices.proxy(context, PROFILE_MAP_CLIENT)
            ?: return Availability.PROFILE_UNAVAILABLE
        return if (connectedDevices(proxy).isEmpty()) Availability.NO_PHONE else Availability.READY
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
        // The message profile is the phone's SMS stack seen from the car, and the Bluetooth
        // stack enforces SEND_SMS on the call accordingly. Without it the send used to fail
        // inside reflection and land in the history as "InvocationTargetException", which
        // named neither the cause nor the remedy.
        if (!hasSendPermission(context)) {
            return Result(false, "sending messages is not allowed (SEND_SMS denied)")
        }
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
            // Reflection wraps whatever the stack threw; the wrapper says nothing, the cause
            // says everything — a refused permission, a profile that went down mid-call.
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            AppLogger.w(TAG, "sendMessage(): ${cause.message}")
            Result(false, cause.javaClass.simpleName + (cause.message?.let { ": $it" } ?: ""))
        }
    }

    /** Whether the platform would let this app hand a message to the paired phone at all. */
    fun hasSendPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

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
