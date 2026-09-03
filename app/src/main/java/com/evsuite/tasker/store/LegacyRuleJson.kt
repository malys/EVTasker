package com.evsuite.tasker.store

import com.evsuite.hardware.catalog.VehicleEnums
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Repairs rules saved by an older build, before Gson ever sees them.
 *
 * `WEBHOOK_GET` and `WEBHOOK_POST` became a single `WEBHOOK` whose verb rides on
 * [com.evsuite.tasker.model.Action.flag]. Without this pass, a rule saved by an older build names
 * an entry the catalog no longer has: Gson deserialises the unknown name to null and
 * [RuleStore] then drops the whole rule — the user's webhook rule silently disappears on
 * update, and an exported file made before the merge refuses to import.
 *
 * The glass actions are the second case. They used to carry a raw vendor command in 0..7,
 * which nobody could read and which let a rule ask for a value the service accepts, drops and
 * reports as applied — an on-vehicle capture showed exactly that, five runs of "ALLOWED" with
 * the window still fully open throughout. They now carry a state: closed or open. A saved
 * command has to be reinterpreted, and the honest reading is the user's intent rather than the
 * vendor's numbering: **every glass write was inert before this** (`writeProven` refused them
 * until a probe had watched a command move the glass), so no saved number ever moved a window,
 * and there is no correct behaviour to preserve — only an intention to recover. People typed
 * these as if they were percentages, so that is how they are read back: 100 meant open, 0 and
 * 7 meant closed.
 *
 * `SELECT_RADIO_BAND` is the third. Band, frequency and playback were one instruction split
 * across two entries, and the split was the wrong seam — a band could not carry a station and
 * a station could not reach DAB. They merged into `TUNE_RADIO`, which reads the band from the
 * same `number` the band action already stored, so the rewrite is the name and nothing else.
 *
 * The rewrite is by name, wherever it sits: an action in the "if", in an "else if" or in the
 * "else" reaches the reader through the same object shape, so the walk is recursive rather
 * than a list of paths that a later branch shape could outgrow.
 */
internal object LegacyRuleJson {

    /** The old name, and the [flag] value that preserves its verb. */
    private val RENAMED_ACTIONS = mapOf(
        "WEBHOOK_GET" to false,
        "WEBHOOK_POST" to true
    )

    /** Actions whose `number` changed meaning from a vendor command to a window state. */
    private val GLASS_ACTIONS = setOf(
        "SET_WINDOWS", "SET_WINDOW_DRIVER", "SET_WINDOW_PASSENGER",
        "SET_WINDOW_REAR_LEFT", "SET_WINDOW_REAR_RIGHT"
    )

    /**
     * Anything at or above this was typed as "open" by someone thinking in percent.
     *
     * The old control was a 0..7 command, so a saved 100 came from a rule written against an
     * even older percentage control, and a saved 7 came from someone reaching for the top of
     * the command range to mean "close". Both are intentions, neither was ever obeyed.
     */
    private const val OPEN_FROM = 50

    /** The migrated JSON, or [json] unchanged when there is nothing to rename. */
    fun migrate(json: String): String = runCatching {
        val root = JsonParser.parseString(json)
        if (rewrite(root)) root.toString() else json
    }.getOrDefault(json)

    /** True when anything was renamed below [element]. */
    private fun rewrite(element: JsonElement): Boolean {
        var changed = false
        when {
            element.isJsonArray -> element.asJsonArray.forEach { changed = rewrite(it) || changed }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                val flag = RENAMED_ACTIONS[type]
                if (flag != null) {
                    obj.add("type", JsonPrimitive("WEBHOOK"))
                    obj.add("flag", JsonPrimitive(flag))
                    changed = true
                }
                if (type == "SELECT_RADIO_BAND") {
                    obj.add("type", JsonPrimitive("TUNE_RADIO"))
                    // No frequency: the band alone is what the old action meant, and it is
                    // what the merged one reads as "put the tuner on this band".
                    obj.add("text", JsonPrimitive(""))
                    // The old band switch always took the audio focus (`tune(andPlay = true)`),
                    // so the rule played the radio whether or not anyone wrote that down.
                    obj.add("flag", JsonPrimitive(true))
                    changed = true
                }
                if (type in GLASS_ACTIONS && rewriteGlassNumber(obj)) changed = true
                obj.entrySet().forEach { changed = rewrite(it.value) || changed }
            }
        }
        return changed
    }

    /**
     * @return true when the stored command was replaced by a window state.
     *
     * A value that is already a state (0 or 1) is left alone, so the pass is idempotent — it
     * runs on every load, and a rule saved after the change must not be rewritten again. That
     * makes 0 ambiguous on purpose: as a command it meant "no movement", as a state it means
     * closed, and closed is what a rule asking for nothing should settle on.
     */
    private fun rewriteGlassNumber(obj: com.google.gson.JsonObject): Boolean {
        val number = obj.get("number")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?.takeIf { it.isNumber }?.asInt ?: return false
        if (number == VehicleEnums.WINDOW_CLOSE || number == VehicleEnums.WINDOW_OPEN) return false
        val state = if (number >= OPEN_FROM) VehicleEnums.WINDOW_OPEN else VehicleEnums.WINDOW_CLOSE
        obj.add("number", JsonPrimitive(state))
        return true
    }
}
