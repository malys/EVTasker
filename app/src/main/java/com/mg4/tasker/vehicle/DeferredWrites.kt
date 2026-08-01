package com.mg4.tasker.vehicle

import android.content.Context
import com.mg4.hardware.AppLogger
import com.mg4.hardware.VehicleWriteGate
import com.mg4.hardware.catalog.ActionType
import com.mg4.tasker.bridge.BridgeContract
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.ActionResult
import com.mg4.tasker.model.EngineRun
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleOutcome
import com.mg4.tasker.model.RuleRun
import com.mg4.tasker.store.HistoryStore
import kotlin.concurrent.thread

/**
 * Gated writes the car refused because it was moving, applied at the next standstill.
 *
 * The problem this solves is the ordinary one: rules fire when the ignition comes on, and by
 * then the car is usually already rolling down the drive. "Eco mode when my phone connects"
 * was therefore refused about as often as it worked, and the driver's only clue was a line in
 * the history. Nothing was wrong with the rule — it was simply asked one moment too late.
 *
 * So the refusal is remembered instead of thrown away, and re-attempted at the first red
 * light. Three limits keep that honest:
 *
 *  * **Only gate refusals.** REFUSED_MOVING and REFUSED_UNKNOWN_SPEED are the two verdicts
 *    that mean "not now" rather than "no". An unsupported action or a failed write is not
 *    retried here — waiting does not add a missing firmware feature.
 *  * **[EXPIRY_MS], and never across an ignition cycle.** A rule that fired for this drive
 *    belongs to this drive. Applying it half an hour later, or the next morning, would be
 *    the app acting on its own initiative rather than on a rule.
 *  * **Nothing is hidden.** Each drain writes a [HistoryStore] entry tagged [TRIGGER], so a
 *    setting that changed at a red light is explained in the same place as everything else.
 *
 * The poller only exists while something is queued: it is a single thread started on the
 * first entry that returns as soon as the queue empties. Idle cost is one empty map — which
 * is the point, because everything outside the rule engine has to stay cheap.
 */
object DeferredWrites {

    private const val TAG = "MG4Tasker.Deferred"

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

    /** For the diagnostic report: how many writes are waiting for the car to stop. */
    val size: Int get() = synchronized(pending) { pending.size }

    /**
     * Remembers every gate refusal in [run] and starts the poller if anything was kept.
     *
     * Takes the whole cycle rather than one action at a time because the rule's name has to
     * travel with it: "drive mode, deferred" is a puzzle, "Eco on the motorway — drive mode,
     * deferred" is an explanation.
     */
    fun offer(context: Context, run: EngineRun, rules: List<Rule>) {
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
                // APPLY_PROFILE needs a live MG4Control bind belonging to the cycle that
                // opened it; re-applying it later would mean holding that bind open for
                // nothing. It stays refused.
                .filter { it.actionType != ActionType.APPLY_PROFILE }
                .mapNotNull { result ->
                    rule.actions.firstOrNull { it.type == result.actionType }
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
