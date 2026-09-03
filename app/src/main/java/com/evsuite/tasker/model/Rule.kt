package com.evsuite.tasker.model

import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.ActionType
import java.util.UUID

/** Comparison operator for numeric conditions. */
enum class CompareOp { EQ, NE, LT, LE, GT, GE }

/** How a rule's conditions combine. */
enum class MatchMode { ALL, ANY }

/**
 * What makes a rule run.
 *
 * The vehicle service receives every ignition transition and samples the gear while the
 * ignition is in RUN. [GEAR_PARK] means a confirmed non-P → P transition, not merely that the
 * service started while the car was already parked.
 *
 * At [IGNITION_OFF] the car is powering down. Settings that persist (charge limit, door
 * locks, windows) land; anything the vehicle drops with the ignition may not, and the
 * history reports what each action returned rather than assuming.
 */
enum class RuleTrigger { IGNITION_ON, GEAR_PARK, IGNITION_OFF }

/**
 * One configured condition.
 *
 * The value fields form a flat union rather than a sealed hierarchy: Gson serialises flat
 * data classes without an adapter, and [ConditionType.spec] already says which field is
 * authoritative. A sealed hierarchy would mean writing and maintaining a TypeAdapter for
 * no difference in use.
 */
data class Condition(
    val type: ConditionType,
    val op: CompareOp = CompareOp.EQ,
    /** Numeric threshold — temperature, speed, volume, level… */
    val number: Float = 0f,
    /** Expected value for boolean conditions. */
    val flag: Boolean = true,
    /** Bluetooth MAC address, or firmware generation identifier. */
    val text: String = "",
    // DATE also uses text, as an ISO-8601 local date (yyyy-MM-dd), for stable persistence.
    /** [ConditionType.TIME_OF_DAY] — minutes since midnight. */
    val minutesFrom: Int = 0,
    val minutesTo: Int = 0,
    /** [ConditionType.DAY_OF_WEEK] — java.util.Calendar.MONDAY…SUNDAY values. */
    val days: List<Int> = emptyList()
)

/** One configured action. */
data class Action(
    val type: ActionType,
    /** Numeric value or enum value, depending on [ActionType.spec]. */
    val number: Int = 0,
    val flag: Boolean = true,
    /** Profile id, package name, notification text, destination or phone number. */
    val text: String = "",
    /** Optional webhook POST body. Nullable so rules saved before this field remain safe. */
    val payload: String? = null,
    /** Human-readable contact name; execution deliberately uses [text], the stored number. */
    val displayName: String? = null,
    /** [ActionType.SET_CHARGE_WINDOW] — minutes since midnight, start and end. */
    val minutesFrom: Int = 0,
    val minutesTo: Int = 0,
    /**
     * [ActionType.ASK_CONFIRM] — whether a question nobody answered lets the rule carry on.
     *
     * Its own field rather than [flag], which every editor save already writes for every
     * action: an [ActionType.ASK_CONFIRM] saved before this existed carries `flag = true`
     * because that is the model default, and reading the permissive meaning off it would
     * silently turn every confirmation ever written into one that proceeds on silence.
     *
     * Non-null with the cautious default, so an absent field — which is what Gson finds in
     * every rule saved before now — reads as the behaviour those rules were written for.
     */
    val yesOnNoAnswer: Boolean = false
)

/**
 * One "if" of a rule: what to check, and what to do when it holds.
 *
 * The first branch is [Rule.match]/[Rule.conditions]/[Rule.actions] themselves rather than
 * the head of a list: every rule ever saved carries those three fields, and moving them into
 * a list would hand Gson a null where the first branch used to be. So a rule with no "else
 * if" and no "else" is byte-for-byte what it was before branches existed.
 *
 * Each branch carries its own [match]: "all of these" for the first case and "any of these"
 * for the fallback is a normal thing to write, and a single mode for the whole rule would
 * make the second case unexpressible.
 */
data class Branch(
    val match: MatchMode = MatchMode.ALL,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList()
)

/**
 * How long one evaluation cycle may spend waiting, every [ActionType.DELAY] of every rule
 * of that cycle counted together.
 *
 * A single wait is capped by the catalogue; a chain of them is not, and the rules of a cycle
 * run one after the other, so a rule that waits also delays the ones evaluated after it. Two
 * minutes: past that, the actions still to come would be applied against a snapshot taken
 * before the driver had left the car, for an ignition transition that is long over.
 */
const val DELAY_BUDGET_MS = 120_000L

/**
 * How many "else if" cases one rule may carry.
 *
 * Not a storage limit — a reading one. A branch chain is read top to bottom to know which
 * case wins, and past a handful the answer stops being visible in one glance on a screen at
 * arm's length. Four alternatives plus the "if" and the "else" is already six cases.
 */
const val MAX_ELSE_IF = 4

/**
 * A rule: conditions, how they combine, and actions — then, optionally, other cases.
 *
 * The branches are tried in the order they are written and **at most one of them runs**, as
 * in any if / else if / else: the first whose conditions hold wins, and [elseActions] is what
 * happens when none did. That is what makes "cold: preheat, mild: ventilate, otherwise:
 * nothing" one rule instead of three that each have to exclude the others' ranges by hand.
 *
 * No "last run" field here: rules are evaluated once per addressed event and the history
 * lives in [com.evsuite.tasker.store.HistoryStore]. Mixing the two would rewrite the rules
 * file on every trigger.
 */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val match: MatchMode = MatchMode.ALL,
    /**
     * Nullable on purpose. Gson builds instances without calling the constructor, so a
     * Kotlin default never applies to a field absent from stored JSON — a non-null type here
     * would hand the engine a null and crash on every rule saved before this existed. Read
     * it through [firesOn].
     */
    val trigger: RuleTrigger? = null,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    /**
     * The "else if" cases, tried in order after the first branch failed to match.
     *
     * Nullable for the same reason as [trigger]: Gson builds instances without calling the
     * constructor, so this field is null — not an empty list — for every rule saved before
     * branches existed. Read it through [elseIfBranches].
     */
    val elseIf: List<Branch>? = null,
    /**
     * What runs when no branch matched. Absent or empty means "then nothing", which is what
     * every rule written before branches did. Nullable for the reason above; read through
     * [otherwise].
     */
    val elseActions: List<Action>? = null
) {
    /** The "else if" cases, never null. */
    val elseIfBranches: List<Branch> get() = elseIf.orEmpty()

    /** The "else" actions, never null. */
    val otherwise: List<Action> get() = elseActions.orEmpty()

    /**
     * Every conditional case in evaluation order: the rule's own conditions first, then the
     * "else if" ones. [otherwise] is not in here — it has no conditions and is only reached
     * once all of these have failed to match.
     */
    val branches: List<Branch> get() = listOf(Branch(match, conditions, actions)) + elseIfBranches

    /** Whether this rule has anything beyond its first branch — what makes it worth naming a branch in the history. */
    val hasAlternatives: Boolean get() = elseIfBranches.isNotEmpty() || otherwise.isNotEmpty()

    /**
     * The actions the engine ran, given the branch it reported. Used to trace a result back
     * to the action that produced it — a lookup in [actions] would miss everything an "else
     * if" or the "else" did.
     */
    fun actionsFor(branch: Int?): List<Action> = when (branch) {
        null -> actions
        BRANCH_ELSE -> otherwise
        else -> branches.getOrNull(branch)?.actions ?: actions
    }

    /**
     * A rule with no condition would apply on every start without the user asking; a rule
     * with no action does nothing. Both are refused at save time rather than silently
     * ignored at run time, and every branch is held to the same bar — an "else if" with no
     * condition is the "else", and one with no action is a case that swallows the ones
     * written after it.
     *
     * A branch made only of waits counts as having no action: it holds the cycle for its
     * duration and changes nothing. An absent "else" is fine — "and otherwise nothing" is a
     * complete sentence.
     */
    fun isComplete(): Boolean =
        branches.all { it.conditions.isNotEmpty() && it.actions.doesSomething() } &&
            (otherwise.isEmpty() || otherwise.doesSomething())

    /**
     * The longest this rule can ask to wait — what the editor checks against
     * [DELAY_BUDGET_MS] before saving.
     *
     * The maximum across the branches rather than their sum: exactly one branch runs, so
     * summing them would refuse a rule that can never wait that long.
     *
     * The engine enforces the budget at run time anyway, because a cycle spends it across
     * every rule it evaluates. Refusing the rule here as well is what tells the user now,
     * rather than through a wait cut short in the history after the drive.
     */
    val totalDelayMs: Long get() =
        (branches.map { it.actions.delayMs() } + otherwise.delayMs()).max()

    /** [trigger], defaulting to vehicle start — what every rule written before this did. */
    val firesOn: RuleTrigger get() = trigger ?: RuleTrigger.IGNITION_ON

    /**
     * Button conditions are event sources themselves; the selected vehicle trigger is ignored.
     *
     * Every branch counts: the event addresses the rule, and a button named only in an
     * "else if" still has to bring the rule its press.
     */
    val hasPhysicalButtonCondition: Boolean get() =
        branches.any { branch -> branch.conditions.any { it.type.eventDriven } }

    /**
     * Whether a rule addressed by a button press says so in every case it can run.
     *
     * A button condition does not only test something, it decides which event stream brings
     * the rule its cycles: naming one anywhere takes the whole rule out of the vehicle-trigger
     * cycles and hands it every press instead ([hasPhysicalButtonCondition]). A case that
     * does not name a button is then evaluated on presses of buttons it never mentioned, and
     * an "else" — which tests nothing at all — would run on every press of every button.
     *
     * So a rule that names a button must name one in each of its cases and carry no "else".
     * Refused at save and at import rather than silently rewired, because the failure is a
     * write to the car on an event the user did not ask about.
     */
    val buttonAddressingIsSound: Boolean get() = !hasPhysicalButtonCondition ||
        (otherwise.isEmpty() && branches.all { branch -> branch.conditions.any { it.type.eventDriven } })
}

/** Whether a list of actions changes anything, or is only waiting. */
private fun List<Action>.doesSomething(): Boolean = any { it.type != ActionType.DELAY }

/** What this list of actions asks to wait for, clamped exactly as the engine clamps it. */
private fun List<Action>.delayMs(): Long = filter { it.type == ActionType.DELAY }
    .sumOf { it.number.coerceIn(ActionType.DELAY.spec.min, ActionType.DELAY.spec.max) * 1000L }
