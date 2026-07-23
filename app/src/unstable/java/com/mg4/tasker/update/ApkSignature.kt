package com.mg4.tasker.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * Signing-certificate fingerprints, for verifying an OTA APK was signed by the same key as
 * the running app. UNSTABLE builds only.
 *
 * Every method fails closed: an unreadable archive or a missing signature returns an empty
 * set, so a mismatch is indistinguishable from "could not verify" — the caller rejects both.
 */
object ApkSignature {

    private const val TAG = "ApkSignature"

    /** SHA-256 fingerprints of the certificates signing the APK file at [path]. */
    fun of(context: Context, path: String): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(path, flags)
        fingerprints(info?.let { @Suppress("DEPRECATION") it.signatures })
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read archive signature: ${e.message}"); emptySet()
    }

    /** SHA-256 fingerprints of the certificates signing the installed running app. */
    fun ofPackage(context: Context): Set<String> = try {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        fingerprints(@Suppress("DEPRECATION") info.signatures)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read own signature: ${e.message}"); emptySet()
    }

    private fun fingerprints(sigs: Array<android.content.pm.Signature>?): Set<String> {
        if (sigs.isNullOrEmpty()) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
}
