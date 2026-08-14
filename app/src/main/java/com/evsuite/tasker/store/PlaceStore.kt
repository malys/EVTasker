package com.evsuite.tasker.store

import android.content.Context

/**
 * Places the driver has named, for the location condition and the navigation action.
 *
 * EVTasker's own list rather than the head unit's navigation favourites: the SAIC map app
 * publishes no provider and no intent for its favourites, so there is nothing to read. What
 * it does have is the car's current position, which is where every one of these entries
 * comes from — "save this place" at the wheel, reused later as a rule's point or
 * destination. Same purpose as picking a phone-book entry instead of typing a number.
 *
 * Stored as `name` → "latitude,longitude" in the preference map itself: the whole record is
 * two strings, so a serialiser would only add a dependency and a schema to migrate.
 */
object PlaceStore {

    private const val PREFS = "ev_tasker_places"

    /** One saved place. [point] is "latitude,longitude", the same form the rules store. */
    data class Place(val name: String, val point: String)

    /** Every saved place, by name, case-insensitively ordered so the list reads alphabetically. */
    fun all(context: Context): List<Place> =
        prefs(context).all
            .mapNotNull { (name, value) ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { Place(name, it) }
            }
            .sortedBy { it.name.lowercase() }

    /**
     * Saves [point] under [name], replacing any place of that name.
     *
     * @return false when either half is blank — a nameless place cannot be picked again,
     * and an empty point is not a place.
     */
    fun save(context: Context, name: String, point: String): Boolean {
        val key = name.trim()
        val value = point.trim()
        if (key.isBlank() || value.isBlank()) return false
        prefs(context).edit().putString(key, value).apply()
        return true
    }

    fun remove(context: Context, name: String) {
        prefs(context).edit().remove(name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
