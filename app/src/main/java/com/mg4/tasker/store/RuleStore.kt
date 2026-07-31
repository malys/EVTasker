package com.mg4.tasker.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.hardware.AppLogger
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
        private const val TAG = "MG4_RULE_STORE"
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
        } catch (e: Exception) {
            // Corrupt JSON: zero rules beats crashing at vehicle start. Logged, because
            // silently reading zero rules is indistinguishable from having none — and the
            // next save would then persist that emptiness over the user's real rules.
            AppLogger.w(TAG, "getAll unreadable (${json.length} chars): ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    fun getById(id: String): Rule? = getAll().firstOrNull { it.id == id }

    /** Why [save] refused. [OK] is the only outcome that leaves the rule on disk. */
    enum class SaveResult { OK, QUOTA_REACHED, WRITE_FAILED, NOT_READ_BACK }

    /**
     * Writes the rule and checks it is actually there afterwards.
     *
     * The read-back is not paranoia: `commit()` returning true only says the preferences file
     * was written, and a rule that vanishes between the editor and the list is precisely the
     * failure being guarded against. Reporting *which* step failed is what makes it
     * actionable — a silent "nothing happened" is what left the previous report unfixable.
     */
    fun save(rule: Rule): SaveResult = synchronized(MUTATION_LOCK) {
        val rules = getAll().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
        } else {
            if (rules.size >= MAX_RULES) return SaveResult.QUOTA_REACHED
            rules.add(rule)
        }
        if (!persist(rules)) {
            AppLogger.w(TAG, "save('${rule.name}') → commit() refused the write")
            return SaveResult.WRITE_FAILED
        }
        if (getById(rule.id) == null) {
            AppLogger.w(TAG, "save('${rule.name}') → committed but not read back")
            return SaveResult.NOT_READ_BACK
        }
        AppLogger.i(TAG, "save('${rule.name}') → ${rules.size} rule(s) stored")
        SaveResult.OK
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

    /** @return whether the write reached disk. commit(), not apply(): the next mutation
     *  re-reads the blob immediately, and the caller needs a real answer. */
    private fun persist(rules: List<Rule>): Boolean = try {
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).commit()
    } catch (e: Exception) {
        // Storage unavailable (locked user, full disk): a rule silently lost here is the bug
        // being fixed, so it is logged and reported rather than swallowed.
        AppLogger.w(TAG, "persist failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
