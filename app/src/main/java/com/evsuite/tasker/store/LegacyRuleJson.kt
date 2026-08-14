package com.evsuite.tasker.store

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Renames catalog entries that changed name, before Gson ever sees them.
 *
 * `WEBHOOK_GET` and `WEBHOOK_POST` became a single `WEBHOOK` whose verb rides on
 * [com.evsuite.tasker.model.Action.flag]. Without this pass, a rule saved by an older build names
 * an entry the catalog no longer has: Gson deserialises the unknown name to null and
 * [RuleStore] then drops the whole rule — the user's webhook rule silently disappears on
 * update, and an exported file made before the merge refuses to import.
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
                obj.entrySet().forEach { changed = rewrite(it.value) || changed }
            }
        }
        return changed
    }
}
