package com.evsuite.tasker.ui

import android.content.Context
import com.evsuite.hardware.AppLogger
import com.evsuite.hardware.catalog.ActionType
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * The driver's yes/no, fetched from a rule that is mid-execution.
 *
 * [com.evsuite.hardware.catalog.ActionType.ASK_CONFIRM] is the one action whose answer decides
 * whether the rest of the rule runs, so the call has to block: the engine runs a branch's
 * actions in sequence on one thread, and "stop here" is only expressible by not returning
 * until the answer is in. That thread already blocks for
 * [com.evsuite.hardware.catalog.ActionType.DELAY], under the same cycle budget.
 *
 * The wait is bounded because the alternative is a rule holding the cycle open until the
 * next ignition. Silence is a no, not a yes: the actions behind a confirmation are the ones
 * the user did not want applied unattended.
 */
object ConfirmPrompt {

    private const val TAG = "EVTasker.Confirm"

    /**
     * How long the question stays on screen when the caller names no wait of its own — the
     * catalogue default, so the editor's slider and a rule saved before it existed agree.
     */
    val TIMEOUT_MS = ActionType.ASK_CONFIRM_DEFAULT_SECONDS * 1_000L

    /**
     * The wait an [ActionType.ASK_CONFIRM] action asks for, in milliseconds.
     *
     * `0` is what every rule saved before the wait was configurable carries. It means "no
     * value", not "no time" — clamping it into the range would shorten those rules to the
     * floor without anyone asking.
     */
    fun timeoutMsFor(seconds: Int): Long {
        val spec = ActionType.ASK_CONFIRM.spec
        val resolved = seconds.takeIf { it > 0 } ?: ActionType.ASK_CONFIRM_DEFAULT_SECONDS
        return resolved.coerceIn(spec.min, spec.max) * 1_000L
    }

    enum class Answer { YES, NO, NO_ANSWER }

    /**
     * Rendezvous rather than a buffer: an answer only counts while a rule is waiting for it.
     * A tap that lands after the timeout has nothing left to decide and is dropped.
     */
    private val answers = SynchronousQueue<Answer>()

    private val gate = Any()
    private var waiting = false
    private var dismiss: (() -> Unit)? = null

    /**
     * Shows [question] and blocks until the driver answers or [timeoutMs] elapses.
     *
     * One prompt at a time: two rules asking at once would stack two full-screen windows
     * over the driver, and the second answer would be attributed to whichever rule happened
     * to be waiting. A second call while one is open is [Answer.NO_ANSWER] — its rule stops
     * rather than proceeding on someone else's yes.
     */
    fun ask(context: Context, question: String, timeoutMs: Long = TIMEOUT_MS): Answer {
        synchronized(gate) {
            if (waiting) {
                AppLogger.w(TAG, "another confirmation is already on screen")
                return Answer.NO_ANSWER
            }
            waiting = true
        }
        return try {
            context.startActivity(ConfirmActionActivity.intent(context, question, timeoutMs))
            answers.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: Answer.NO_ANSWER
        } catch (e: Exception) {
            AppLogger.w(TAG, "ask: ${e.message}")
            Answer.NO_ANSWER
        } finally {
            val close = synchronized(gate) {
                waiting = false
                dismiss.also { dismiss = null }
            }
            close?.invoke()
        }
    }

    /**
     * Claims the open prompt for the window that is showing it.
     *
     * @return false when no rule is waiting — the window outlived the rule that asked (the
     * timeout fired first) and has nothing to answer, so it closes itself instead of
     * standing there collecting a tap that would go nowhere.
     */
    fun register(onTimeout: () -> Unit): Boolean = synchronized(gate) {
        if (!waiting) return false
        dismiss = onTimeout
        true
    }

    /** Hands the driver's answer to the waiting rule. Ignored when none is waiting. */
    fun answer(answer: Answer) {
        answers.offer(answer)
    }
}
