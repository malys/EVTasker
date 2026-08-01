package com.mg4.tasker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .filter { now - it.time <= MAX_AGE_MS }
                .maxByOrNull { it.time }
                ?.let { Fix(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            AppLogger.w(TAG, "location denied: ${e.message}")
            null
        }
    }
}
