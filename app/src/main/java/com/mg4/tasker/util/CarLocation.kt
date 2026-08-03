package com.mg4.tasker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.LocationListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.mg4.hardware.AppLogger

/**
 * Where the car is, for the "near a place" condition.
 *
 * Last known fix only — never a live request. A rule cycle runs once at ignition and has to
 * finish; waiting on a satellite lock would hold it open for as long as the sky decides. A
 * stale fix is also the right answer for this question: the car has not moved since the
 * engine was last off, which is exactly when the fix was taken.
 */
object CarLocation {

    private const val TAG = "MG4Tasker.Loc"

    /** How old a fix may be and still describe where the car is now. */
    private const val MAX_AGE_MS = 30 * 60 * 1000L

    data class Fix(val latitude: Double, val longitude: Double)

    @Volatile private var liveFix: android.location.Location? = null

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

    /**
     * Requests a real GPS fix, using the same provider path as MG4ABRPUploader. The callback
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
