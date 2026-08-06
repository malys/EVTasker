package com.mg4.tasker.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mg4.hardware.AppLogger

/**
 * Opening the head unit's map, with or without a destination.
 *
 * The standard `geo:` intent is tried first and is the only portable path — but the MG4's
 * navigation app does not publish a `geo:` intent filter. Every "Open map" button and every
 * `NAVIGATE_TO` action therefore resolved to nothing and failed silently: the code checked
 * `resolveActivity`, found none, and returned "no navigation app" on a car that has one on
 * its home screen.
 *
 * So after the URI intents come explicit components, taken from what the head unit's own
 * launcher and navigation widget do: they hard-code the same component names per market
 * rather than resolve an intent, which is the platform telling us that resolution is not
 * available here. An explicit component opens the map at its default view; the destination
 * is lost, which is worth far more than nothing happening at all.
 */
object MapApps {

    private const val TAG = "MG4Tasker.Map"

    /**
     * Navigation apps shipped on SAIC head units, in the order the vendor's own launcher
     * prefers them. Each entry is the pair the launcher and the navigation widget use.
     */
    private val COMPONENTS = listOf(
        // EU/overseas MG4 — the one this project targets.
        "com.saicmotor.navigation" to "com.saicmotor.navigation.MainActivity",
        "com.telenav.app.arp"      to "com.telenav.arp.module.map.MainActivity",
        "com.nng.igo.primong"      to "com.navngo.igo.javaclient.MainActivity",
        "com.mmi.navimaps_auto"    to "hr.mireo.arthur.common.App"
    )

    /** True when anything on this head unit can show a map. */
    fun isAvailable(context: Context): Boolean =
        resolvesGeo(context) || installedComponent(context) != null

    /**
     * Intents to try in order for [destination], most precise first.
     *
     * [destination] is an address or "latitude,longitude"; blank opens the map where it is.
     * Only the URI intents carry the destination — see the class note.
     */
    fun intents(context: Context, destination: String): List<Intent> {
        val trimmed = destination.trim()
        val uris = when {
            trimmed.isBlank() -> emptyList()
            else -> {
                val point = com.mg4.tasker.engine.ConditionEvaluator.parsePoint(trimmed)
                if (point != null) {
                    val (lat, lon) = point
                    listOf("geo:$lat,$lon?q=$lat,$lon", "google.navigation:q=$lat,$lon")
                } else {
                    val encoded = Uri.encode(trimmed)
                    listOf("geo:0,0?q=$encoded", "google.navigation:q=$encoded")
                }
            }
        }
        val fromUris = uris.map { uri ->
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.filter { it.resolveActivity(context.packageManager) != null }

        // MAIN/HOME rather than a bare component: it is what the vendor launcher sends, and
        // the navigation app treats it as "come to the foreground" instead of starting a
        // second copy of itself on top of a running guidance session.
        val fallback = installedComponent(context)?.let { component ->
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return fromUris + listOfNotNull(fallback)
    }

    /** Starts the first intent that works. Returns the URI/component that took it, or null. */
    fun open(context: Context, destination: String): String? {
        for (intent in intents(context, destination)) {
            try {
                context.startActivity(intent)
                return intent.data?.toString() ?: intent.component?.flattenToShortString()
            } catch (e: Exception) {
                AppLogger.w(TAG, "open(${intent.data ?: intent.component}): ${e.message}")
            }
        }
        return null
    }

    private fun resolvesGeo(context: Context): Boolean =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test"))
            .resolveActivity(context.packageManager) != null

    private fun installedComponent(context: Context): ComponentName? =
        COMPONENTS.firstNotNullOfOrNull { (pkg, cls) ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                ComponentName(pkg, cls)
            } catch (_: Exception) {
                null
            }
        }
}
