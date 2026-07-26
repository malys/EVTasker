package com.mg4.tasker.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.tasker.model.Rule

/**
 * Rule persistence (JSON in shared preferences).
 *
 * The lock is per PROCESS, not per instance: the UI and the run service each build their
 * own RuleStore over the same file, and every mutation is a read-modify-write on a single
 * blob. Without it, two simultaneous saves silently lose one.
 * (Same reasoning as ProfileManager in MG4Control.)
 */
class RuleStore(context: Context) {

    companion object {
        private const val PREFS = "mg4_tasker_rules"
        private const val KEY_RULES = "rules_json"
        const val MAX_RULES = 20

        private val MUTATION_LOCK = Any()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAll(): List<Rule> {
        val json = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Rule>>() {}.type
            gson.fromJson<List<Rule>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            // Corrupt JSON: zero rules beats crashing at vehicle start.
            emptyList()
        }
    }

    fun getById(id: String): Rule? = getAll().firstOrNull { it.id == id }

    /** @return false when the quota is reached (creation only). */
    fun save(rule: Rule): Boolean = synchronized(MUTATION_LOCK) {
        val rules = getAll().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
        } else {
            if (rules.size >= MAX_RULES) return false
            rules.add(rule)
        }
        persist(rules)
        true
    }

    /**
     * Import: the file becomes the whole rule set. The quota is enforced by
     * [RuleTransfer.decode], which refuses an oversized file with a message the user can act
     * on — checking it again here could only produce a silent truncation.
     */
    fun replaceAll(rules: List<Rule>) = synchronized(MUTATION_LOCK) {
        persist(rules)
    }

    fun delete(ruleId: String) = synchronized(MUTATION_LOCK) {
        persist(getAll().filterNot { it.id == ruleId })
    }

    fun setEnabled(ruleId: String, enabled: Boolean) = synchronized(MUTATION_LOCK) {
        persist(getAll().map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    private fun persist(rules: List<Rule>) {
        // commit(), not apply(): the next mutation re-reads the blob immediately.
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).commit()
    }
}
