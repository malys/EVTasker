package com.mg4.tasker.store

import android.content.Context
import com.google.gson.Gson
import com.mg4.hardware.AppLogger
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Branch
import com.mg4.tasker.model.Condition
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

    /** What the stored blob turned out to be. Unreadable is NOT the same as "no rules". */
    private sealed interface Stored {
        @JvmInline value class Rules(val rules: List<Rule>) : Stored
        object Unreadable : Stored
    }

    /**
     * Deserialises as an ARRAY, not via `TypeToken<List<Rule>>`.
     *
     * The anonymous-TypeToken idiom needs the class's generic signature to survive the
     * shrinker, and it did not: R8 dropped the anonymous subclass outright, so every release
     * build threw "TypeToken must be created with a type argument" and read back zero rules —
     * a rule saved on the car then vanished from the list. `Array<Rule>::class.java` is a
     * plain Class carrying no generics at all, so no keep rule can undo it. The JSON is
     * identical either way, so blobs written by earlier builds still load.
     */
    private fun read(): Stored {
        val json = prefs.getString(KEY_RULES, null) ?: return Stored.Rules(emptyList())
        return try {
            val parsed = gson.fromJson(json, Array<Rule>::class.java)?.toList() ?: emptyList()
            Stored.Rules(parsed.filter { it.isHonourable() })
        } catch (e: Exception) {
            AppLogger.w(TAG, "getAll unreadable (${json.length} chars): ${e.javaClass.simpleName}: ${e.message}")
            Stored.Unreadable
        }
    }

    /**
     * False for a rule naming a catalog entry this build no longer has.
     *
     * Same Gson property as [Rule.trigger]: an unknown enum name deserialises to null, whatever
     * the Kotlin type says. A condition or action whose type is gone cannot be evaluated, shown
     * or repaired, and letting it through hands a null [com.mg4.hardware.catalog.ConditionType]
     * to the engine and the rule list — a crash on the first run after the update.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun Rule.isHonourable(): Boolean {
        // Every case, not only the first one: a removed entry hidden in an "else if" or in
        // the "else" reaches the engine exactly the same way, and the null only shows up when
        // that branch is the one that matches — on the car, on some later drive.
        val honourable = match != null && conditions.honourable() && actions.honourable() &&
            (elseIf == null || elseIf.all { it.honourable() }) &&
            (elseActions == null || elseActions.honourable())
        if (!honourable) AppLogger.w(TAG, "rule '$name' dropped: it uses a catalog entry this build removed")
        return honourable
    }

    /**
     * Gson fills an unknown enum name, an absent list and a null array entry all with null,
     * whatever the Kotlin type says — so each level is checked rather than trusted.
     */
    @Suppress("SENSELESS_COMPARISON")
    @JvmName("conditionsHonourable")
    private fun List<Condition>.honourable(): Boolean =
        // The operator too, not only the type: it is read by an exhaustive `when` in
        // ConditionEvaluator, which a null walks straight past into a crash.
        this != null && all { it != null && it.type != null && it.op != null }

    @Suppress("SENSELESS_COMPARISON")
    @JvmName("actionsHonourable")
    private fun List<Action>.honourable(): Boolean =
        this != null && all { it != null && it.type != null }

    @Suppress("SENSELESS_COMPARISON")
    private fun Branch?.honourable(): Boolean =
        this != null && match != null && conditions.honourable() && actions.honourable()

    /** Unreadable storage reads as zero rules: that beats crashing at vehicle start. */
    fun getAll(): List<Rule> = (read() as? Stored.Rules)?.rules ?: emptyList()

    fun getById(id: String): Rule? = getAll().firstOrNull { it.id == id }

    /** Why [save] refused. [OK] is the only outcome that leaves the rule on disk. */
    enum class SaveResult { OK, QUOTA_REACHED, WRITE_FAILED, NOT_READ_BACK, STORE_UNREADABLE }

    /**
     * Writes the rule and checks it is actually there afterwards.
     *
     * The read-back is not paranoia: `commit()` returning true only says the preferences file
     * was written, and a rule that vanishes between the editor and the list is precisely the
     * failure being guarded against. Reporting *which* step failed is what makes it
     * actionable — a silent "nothing happened" is what left the previous report unfixable.
     */
    fun save(rule: Rule): SaveResult = synchronized(MUTATION_LOCK) {
        // Never write on top of a blob that could not be read: every mutation here is a
        // read-modify-write, so saving over an unreadable set silently replaces the user's
        // rules with just this one. That is how the reported bug destroyed a rule. Import
        // ([replaceAll]) stays the deliberate way to overwrite.
        val current = read()
        if (current !is Stored.Rules) {
            AppLogger.w(TAG, "save('${rule.name}') → refused: stored rules are unreadable")
            return SaveResult.STORE_UNREADABLE
        }
        val rules = current.rules.toMutableList()
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

    // Both are read-modify-write like [save], so both refuse an unreadable blob for the same
    // reason: rewriting it would throw away rules that are still on disk and merely unparsed.

    fun delete(ruleId: String) = synchronized(MUTATION_LOCK) {
        val current = read()
        if (current is Stored.Rules) persist(current.rules.filterNot { it.id == ruleId })
        Unit
    }

    fun setEnabled(ruleId: String, enabled: Boolean) = synchronized(MUTATION_LOCK) {
        val current = read()
        if (current is Stored.Rules) {
            persist(current.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
        }
        Unit
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
