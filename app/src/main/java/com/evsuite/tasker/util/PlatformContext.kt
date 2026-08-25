package com.evsuite.tasker.util

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log

/**
 * The context signals that come from Android rather than from the car.
 *
 * Every reader answers null for "cannot tell", the same contract the vehicle readers keep:
 * a head unit that declines to name its network must leave the condition unavailable, not
 * report a different network.
 *
 * Nothing here is cached. These are cheap system calls and a rule cycle reads them once.
 */
object PlatformContext {

    private const val TAG = "EVTasker.Ctx"

    /**
     * Whether audio is coming out of the speakers, whichever app is producing it.
     *
     * `isMusicActive` is the platform's own answer and needs no permission and no
     * notification-listener access — which is the whole reason this is a one-line reading
     * rather than a MediaSessionManager subscription.
     */
    fun mediaPlaying(context: Context): Boolean? = audioManager(context)?.isMusicActive

    /**
     * Whether a call is routed through the head unit.
     *
     * The audio mode, not the telephony state: the head unit has no SIM, calls arrive over
     * the hands-free profile, and the mode is what changes when one is connected. It cannot
     * distinguish a ringing phone from a call in progress, and the catalogue entry says so.
     */
    fun inCall(context: Context): Boolean? = audioManager(context)?.let {
        it.mode == AudioManager.MODE_IN_CALL || it.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * The name of the joined Wi-Fi network, null when there is none or the platform will not
     * say.
     *
     * The platform wraps the name in quotes and answers a documented placeholder when it is
     * withholding it; both are filtered here so a rule never compares against `<unknown ssid>`
     * or against a name with quotes the user did not type.
     */
    @Suppress("DEPRECATION")
    fun wifiSsid(context: Context): String? = try {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.connectionInfo?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    } catch (e: Exception) {
        Log.w(TAG, "wifiSsid: ${e.message}")
        null
    }

    private fun audioManager(context: Context): AudioManager? = try {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (e: Exception) {
        Log.w(TAG, "audioManager: ${e.message}")
        null
    }
}
