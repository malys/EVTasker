package com.evsuite.tasker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.evsuite.hardware.AppLogger

/**
 * Where the car is, for the "near a place" condition.
 *
 * A rule cycle cannot wait on a satellite lock — it runs at ignition and has to finish — so
 * it reads a cached fix ([lastKnown]). What fills that cache is [startTracking]: a live GPS
 * subscription held by the vehicle service, the same provider path EVABRPUploader uses.
 * Without it `getLastKnownLocation` is whatever some other app happened to leave behind,
 * which on a head unit with no other GPS client is nothing at all — the fix came back null
 * and every "near a place" condition evaluated as unavailable.
 */
object CarLocation {

    private const val TAG = "EVTasker.Loc"

    /** How old a fix may be and still describe where the car is now. */
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    /**
     * Cadence of the service's subscription. Far below [MAX_AGE_MS], so the cache is never
     * the reason a condition is unanswerable, and far above a navigation app's, because
     * nothing here needs to know which lane the car is in.
     */
    private const val TRACK_INTERVAL_MS = 60_000L

    /** How long an inactive subscription may stay inactive before the watchdog re-arms it. */
    const val TRACK_RETRY_INTERVAL_MS = 60_000L

    data class Fix(val latitude: Double, val longitude: Double)

    @Volatile private var liveFix: android.location.Location? = null

    /** False while no subscription is delivering — drives the watchdog re-arm. */
    @Volatile private var trackingActive = false

    @Volatile private var lastTrackRequestMs = 0L

    /**
     * Either grade counts. Since API 31 the user can grant "approximate" when fine was
     * asked for, and an approximate fix still answers a wide radius — refusing to use it
     * would turn a working rule into a broken one over a distinction the user did not make.
     */
    fun hasPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** @return the most recent usable fix, or null when there is none. */
    fun lastKnown(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return null
        return try {
            val now = System.currentTimeMillis()
            (manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                + listOfNotNull(liveFix))
                .filter { now - it.time <= MAX_AGE_MS }
                .maxByOrNull { it.time }
                ?.let { Fix(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "location denied: ${e.message}")
            null
        }
    }

    // ---------- Live tracking (vehicle service) ----------

    private val trackListener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            liveFix = location
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            AppLogger.i(TAG, "$provider enabled — re-arming")
            trackingActive = false
        }

        override fun onProviderDisabled(provider: String) {
            AppLogger.w(TAG, "$provider disabled — conditions on position go unanswerable")
            trackingActive = false
        }
    }

    /**
     * Subscribes to GPS updates for as long as the vehicle service lives. Safe to call
     * repeatedly: the previous subscription is removed first, so the watchdog can re-arm
     * without stacking listeners.
     */
    fun startTracking(context: Context) {
        if (!hasPermission(context)) {
            AppLogger.w(TAG, "no location permission — position tracking not started")
            lastTrackRequestMs = System.currentTimeMillis()
            return
        }
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return
        try {
            runCatching { manager.removeUpdates(trackListener) }
            trackingActive = false
            lastTrackRequestMs = System.currentTimeMillis()
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                AppLogger.w(TAG, "GPS provider disabled — will retry")
                return
            }
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, TRACK_INTERVAL_MS, 0f,
                trackListener, Looper.getMainLooper()
            )
            trackingActive = true
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { liveFix = it }
            AppLogger.i(TAG, "position tracking armed (${TRACK_INTERVAL_MS / 1000} s)")
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "location denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "location unavailable: ${e.message}")
        }
    }

    /**
     * Re-arms the subscription when it is not delivering. Called from the service's own
     * sampler: a subscription placed once at boot is lost with the provider that was off at
     * the time, and nothing else would ever ask again.
     */
    fun ensureTracking(context: Context) {
        if (trackingActive) return
        if (System.currentTimeMillis() - lastTrackRequestMs < TRACK_RETRY_INTERVAL_MS) return
        startTracking(context)
    }

    fun stopTracking(context: Context) {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return
        runCatching { manager.removeUpdates(trackListener) }
        trackingActive = false
    }

    /** Whether the service's subscription is live, for the diagnostic screen. */
    fun isTracking(): Boolean = trackingActive

    /**
     * Requests a real GPS fix, using the same provider path as EVABRPUploader. The callback
     * is completed at most once and falls back to the freshest cached fix after [timeoutMs].
     */
    fun requestCurrent(context: Context, timeoutMs: Long = 8_000, callback: (Fix?) -> Unit) {
        if (!hasPermission(context)) return callback(null)
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return callback(null)
        val main = Handler(Looper.getMainLooper())
        var delivered = false
        lateinit var listener: LocationListener
        fun finish(location: android.location.Location?) {
            if (delivered) return
            delivered = true
            runCatching { manager.removeUpdates(listener) }
            location?.let { liveFix = it }
            callback(location?.let { Fix(it.latitude, it.longitude) } ?: lastKnown(context))
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: android.location.Location) = finish(location)
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return finish(null)
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()
            )
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { cached ->
                if (System.currentTimeMillis() - cached.time <= MAX_AGE_MS) liveFix = cached
            }
            main.postDelayed({ finish(liveFix) }, timeoutMs)
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "location denied: ${e.message}")
            finish(null)
        } catch (e: Exception) {
            AppLogger.w(TAG, "location unavailable: ${e.message}")
            finish(null)
        }
    }
}
