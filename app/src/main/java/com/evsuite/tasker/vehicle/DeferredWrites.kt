package com.evsuite.tasker.vehicle

import android.content.Context
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.VehicleWriteGate
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.bridge.BridgeContract
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.ActionResult
import com.evsuite.tasker.model.EngineRun
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.RuleOutcome
import com.evsuite.tasker.model.RuleRun
import com.evsuite.tasker.store.HistoryStore
import kotlin.concurrent.thread

/**
 * Legacy delayed-write queue, runtime-disabled for the legal and safety audit.
 *
 * Gate refusals are final: production call sites must not enqueue or later reapply a vehicle
 * write. The implementation remains temporarily so its former policy stays testable while it
 * is removed in a dedicated cleanup.
 */
object DeferredWrites {

    private const val TAG = "EVTasker.Deferred"
    private const val REAPPLY_ENABLED = false

    /** Marks the history entry, the way "MANUAL" marks a test run. */
    const val TRIGGER = "DEFERRED"

    /**
     * How often to ask the gate again. Slow on purpose: the answer only changes when the car
     * stops, and a driver who has stopped is going to be stopped for more than ten seconds.
     */
    private const val POLL_MS = 10_000L

    /** After this, the moment the rule was about is gone. */
    private const val EXPIRY_MS = 15 * 60_000L

    internal class Pending(
        val ruleId: String,
        val ruleName: String,
        val action: Action,
        val queuedAt: Long,
    )

    /**
     * Keyed by rule and action type, so a rule re-refused on the next ignition replaces its
     * own entry instead of stacking a second copy of the same write.
     */
    private val pending = LinkedHashMap<Pair<String, ActionType>, Pending>()

    @Volatile
    private var draining = false

    /** For the diagnostic report. This remains zero while reapplication is disabled. */
    val size: Int get() = synchronized(pending) { pending.size }

    /** Refuses all attempts to enqueue while delayed vehicle writes are disabled. */
    fun offer(context: Context, run: EngineRun, rules: List<Rule>) {
        if (!REAPPLY_ENABLED) {
            AppLogger.i(TAG, "Gate refusals are final; deferred vehicle writes are disabled")
            return
        }
        val kept = refusalsIn(run, rules, System.currentTimeMillis())
        if (kept.isEmpty()) return
        synchronized(pending) {
            kept.forEach { pending[it.ruleId to it.action.type] = it }
        }
        AppLogger.i(TAG, "${kept.size} write(s) deferred until the vehicle stops ($size queued)")
        startPolling(context.applicationContext)
    }

    /**
     * Which of a cycle's results are worth keeping. Separated from [offer] because it is the
     * part with a decision in it, and the part a test can reach without a vehicle.
     */
    internal fun refusalsIn(run: EngineRun, rules: List<Rule>, now: Long): List<Pending> {
        val byId = rules.associateBy { it.id }
        return run.ruleRuns.flatMap { ruleRun ->
            val rule = byId[ruleRun.ruleId] ?: return@flatMap emptyList()
            ruleRun.actionResults
                .filter { it.isGateRefusal() }
                // The profile actions need a live EVProfile bind belonging to the cycle that
                // opened it; re-applying one later would mean holding that bind open for
                // nothing. And a picker deferred to the next standstill would appear long
                // after the moment that asked for it, in front of a driver who is no longer
                // expecting a question. Both stay refused.
                .filter {
                    it.actionType != ActionType.APPLY_PROFILE &&
                        it.actionType != ActionType.SHOW_PROFILE_PICKER
                }
                .mapNotNull { result ->
                    // The branch that ran, not the rule's first one: a refusal from an
                    // "else if" or from the "else" has to be found where it came from.
                    rule.actionsFor(ruleRun.firedBranch).firstOrNull { it.type == result.actionType }
                        ?.let { Pending(rule.id, rule.name, it, now) }
                }
        }
    }

    /**
     * Drops everything, e.g. at switch-off.
     *
     * The car is stopped then, so these writes *could* be applied — and that is exactly what
     * makes it the wrong moment. A rule that asked for a drive mode during a drive is asking
     * about that drive; landing it as the vehicle powers down would change the setting the
     * next driver finds, which nobody requested.
     */
    fun clear() {
        val dropped = synchronized(pending) { pending.size.also { pending.clear() } }
        if (dropped > 0) AppLogger.i(TAG, "$dropped deferred write(s) dropped — the drive is over")
    }

    private fun ActionResult.isGateRefusal(): Boolean =
        verdict == BridgeContract.VERDICT_MOVING || verdict == BridgeContract.VERDICT_UNKNOWN_SPEED

    @Synchronized
    private fun startPolling(context: Context) {
        if (draining) return
        draining = true
        thread(name = "mg4-tasker-deferred") {
            try {
                while (true) {
                    Thread.sleep(POLL_MS)
                    expire()
                    if (stopIfEmpty()) return@thread
                    if (VehicleWriteGate.decideNow() != VehicleWriteGate.Decision.ALLOWED) continue
                    applyAll(context)
                    if (stopIfEmpty()) return@thread
                }
            } catch (_: InterruptedException) {
                synchronized(this) { draining = false }
            }
        }
    }

    /**
     * Ends the poll, under the same lock [startPolling] takes.
     *
     * Deciding to stop and clearing the flag have to be one step: between "the queue is
     * empty" and "no poller is running", an [offer] would see a poller that is about to
     * disappear and leave its entry with nothing to drain it.
     */
    @Synchronized
    private fun stopIfEmpty(): Boolean {
        if (size > 0) return false
        draining = false
        return true
    }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        val dropped = synchronized(pending) {
            val stale = pending.filterValues { it.queuedAt < cutoff }.keys
            stale.forEach { pending.remove(it) }
            stale.size
        }
        if (dropped > 0) AppLogger.i(TAG, "$dropped deferred write(s) expired")
    }

    /**
     * Applies the queue and records what happened.
     *
     * Entries are removed before being attempted: one shot each. A write that fails on its
     * own terms has been answered, and re-queueing it would turn one refusal into a loop
     * that writes to the car every ten seconds.
     */
    private fun applyAll(context: Context) {
        val batch = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        if (batch.isEmpty()) return

        val executor = DirectExecutor(context, profileBridge = null)
        val runs = batch.groupBy { it.ruleId }.map { (_, entries) ->
            RuleRun(
                ruleId = entries.first().ruleId,
                ruleName = entries.first().ruleName,
                outcome = RuleOutcome.FIRED,
                actionResults = entries.map { executor.execute(it.action) }
            )
        }
        HistoryStore(context).append(
            EngineRun(
                timestamp = System.currentTimeMillis(),
                trigger = TRIGGER,
                bridgeAvailable = false,
                ruleRuns = runs
            )
        )
        val applied = runs.sumOf { run -> run.actionResults.count { it.ok } }
        AppLogger.i(TAG, "vehicle stopped — $applied/${batch.size} deferred write(s) applied")
    }
}
