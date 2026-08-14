package com.evsuite.tasker.store

import android.content.Context
import com.google.gson.Gson
import com.evsuite.tasker.model.EngineRun

/**
 * History of triggers.
 *
 * This is the answer to "why did my rule do nothing?". Without it, a speed-gate refusal
 * and an unreadable value would be indistinguishable from a badly written rule.
 *
 * Capped at [MAX_RUNS] as a ring: on a vehicle, storage is not the constraint — screen
 * readability is.
 */
class HistoryStore(context: Context) {

    companion object {
        private const val PREFS = "ev_tasker_history"
        private const val KEY_RUNS = "runs_json"
        const val MAX_RUNS = 30

        private val MUTATION_LOCK = Any()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Newest first. */
    fun getAll(): List<EngineRun> {
        val json = prefs.getString(KEY_RUNS, null) ?: return emptyList()
        return try {
            // An ARRAY, not TypeToken<List<EngineRun>>: the anonymous-TypeToken idiom needs a
            // generic signature that R8 dropped, which made every release build read back an
            // empty history. See RuleStore.read().
            gson.fromJson(json, Array<EngineRun>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun append(run: EngineRun) = synchronized(MUTATION_LOCK) {
        val runs = (listOf(run) + getAll()).take(MAX_RUNS)
        prefs.edit().putString(KEY_RUNS, gson.toJson(runs)).commit()
        Unit
    }

    fun clear() = synchronized(MUTATION_LOCK) {
        prefs.edit().remove(KEY_RUNS).commit()
        Unit
    }
}
