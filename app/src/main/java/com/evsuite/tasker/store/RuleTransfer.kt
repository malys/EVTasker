package com.evsuite.tasker.store

import com.google.gson.Gson
import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.CompareOp
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.MAX_ELSE_IF
import com.evsuite.tasker.model.MatchMode
import com.evsuite.tasker.model.Rule
import com.evsuite.tasker.model.RuleTrigger

/**
 * The rules file format — what an export writes and an import reads back.
 *
 * The file is parsed into DTOs with nullable fields rather than straight into [Rule]. A file
 * on a USB stick is untrusted input: Gson fills a missing or unknown value with null, and a
 * null in a non-null Kotlin field surfaces as an NPE somewhere deep in the engine instead of
 * a message at import time. The DTO layer also pins the wire format, so renaming a model
 * field does not silently invalidate every export a user ever wrote.
 *
 * Enum values travel as their **names**. Renaming a `ConditionType` / `ActionType` constant
 * therefore breaks older files — the import reports [Reason.UNKNOWN_ENTRY] rather than
 * dropping the entry, because a rule whose action this build cannot perform must not be
 * applied to a car in a form the file did not describe.
 */
object RuleTransfer {

    const val FORMAT = "evtasker-rules"

    /** The format this build writes and reads. Version 2 added the "else if" / "else" branches. */
    const val VERSION = 2

    /**
     * What a file whose rules are all plain if/then still claims.
     *
     * A build without branches refuses anything above its own version — rightly, since it
     * would otherwise apply the first branch of a rule alone and ignore the rest. Claiming 2
     * for a file that carries no branch would make it refuse backups it understands
     * perfectly, so the version follows the content rather than the build.
     */
    const val VERSION_BASE = 1

    const val FILE_EXTENSION = "json"

    /** Comfortably above [RuleStore.MAX_RULES] rules with long names; larger is not a rules file. */
    const val MAX_BYTES = 256L * 1024

    /** Why a file that claims to be a rules file cannot be imported. */
    enum class Reason { VERSION, UNKNOWN_ENTRY, MALFORMED, TOO_MANY, EMPTY }

    sealed interface Result {
        data class Ok(val rules: List<Rule>) : Result

        /** Somebody else's JSON. A scan skips these silently — not an error to report. */
        data object NotARulesFile : Result

        /** A rules file this build refuses. [detail] carries the offending name, if any. */
        data class Invalid(val reason: Reason, val detail: String = "") : Result
    }

    fun encode(rules: List<Rule>): String = Gson().toJson(
        Envelope(
            format = FORMAT,
            version = if (rules.any { it.hasAlternatives }) VERSION else VERSION_BASE,
            rules = rules.map { rule ->
                RuleDto(
                    id = rule.id,
                    name = rule.name,
                    enabled = rule.enabled,
                    match = rule.match.name,
                    // The raw field, not firesOn: a rule that never chose a trigger exports
                    // without the key, so the round trip is an identity and a file written
                    // before triggers existed comes back unchanged.
                    trigger = rule.trigger?.name,
                    conditions = rule.conditions.map { conditionDto(it) },
                    actions = rule.actions.map { actionDto(it) },
                    // The raw fields again: a rule with no branch exports without the keys,
                    // so its file is byte-for-byte what earlier builds wrote.
                    elseIf = rule.elseIf?.map { branch ->
                        BranchDto(
                            match = branch.match.name,
                            conditions = branch.conditions.map { conditionDto(it) },
                            actions = branch.actions.map { actionDto(it) }
                        )
                    },
                    elseActions = rule.elseActions?.map { actionDto(it) }
                )
            }
        )
    )

    private fun conditionDto(condition: Condition) = ConditionDto(
        type = condition.type.name,
        op = condition.op.name,
        number = condition.number,
        flag = condition.flag,
        text = condition.text,
        minutesFrom = condition.minutesFrom,
        minutesTo = condition.minutesTo,
        days = condition.days
    )

    private fun actionDto(action: Action) = ActionDto(
        type = action.type.name,
        number = action.number,
        flag = action.flag,
        text = action.text
    )

    fun decode(json: String): Result {
        val envelope = try {
            Gson().fromJson(LegacyRuleJson.migrate(json), Envelope::class.java)
        } catch (_: Exception) {
            // Gson throws a family of unchecked types on bad input (syntax, IO, number
            // format). None of them distinguishes "corrupt rules file" from "not ours".
            return Result.NotARulesFile
        } ?: return Result.NotARulesFile

        if (envelope.format != FORMAT) return Result.NotARulesFile

        val version = envelope.version ?: return Result.Invalid(Reason.MALFORMED)
        if (version > VERSION) return Result.Invalid(Reason.VERSION)

        val dtos = envelope.rules ?: return Result.Invalid(Reason.MALFORMED)
        if (dtos.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (dtos.size > RuleStore.MAX_RULES) return Result.Invalid(Reason.TOO_MANY)

        val rules = ArrayList<Rule>(dtos.size)
        for (dto in dtos) {
            when (val converted = toRule(dto)) {
                is Result.Ok -> rules += converted.rules
                else -> return converted
            }
        }
        // Duplicate ids would make the store's id lookups ambiguous: two rules, one editable.
        if (rules.distinctBy { it.id }.size != rules.size) return Result.Invalid(Reason.MALFORMED)

        // A file carrying cases while claiming the version that predates them is telling a
        // build without cases that it may apply those rules — which it would do by keeping
        // the "if" and dropping everything after it. The number and the content have to agree.
        if (version < VERSION && rules.any { it.hasAlternatives }) {
            return Result.Invalid(Reason.MALFORMED)
        }

        return Result.Ok(rules)
    }

    /** One rule, or the reason it was refused. [Result.Ok] always holds exactly one rule here. */
    private fun toRule(dto: RuleDto?): Result {
        if (dto == null) return Result.Invalid(Reason.MALFORMED)

        val id = dto.id?.takeIf { it.isNotBlank() } ?: return Result.Invalid(Reason.MALFORMED)
        val name = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.Invalid(Reason.MALFORMED)
        val match = enumByName<MatchMode>(dto.match) ?: return Result.Invalid(Reason.MALFORMED)

        val conditions = when (val parsed = parseConditions(dto.conditions)) {
            is Parsed.Bad -> return parsed.result
            is Parsed.Ok -> parsed.value
        }

        val actions = when (val parsed = parseActions(dto.actions)) {
            is Parsed.Bad -> return parsed.result
            is Parsed.Ok -> parsed.value
        }

        // A chain nobody can read is refused at the door rather than saved and then shown as
        // twenty cases on a screen meant to be read at a glance.
        val rawBranches = dto.elseIf.orEmpty()
        if (rawBranches.size > MAX_ELSE_IF) return Result.Invalid(Reason.TOO_MANY)

        val elseIf = ArrayList<Branch>(rawBranches.size)
        for (raw in rawBranches) {
            if (raw == null) return Result.Invalid(Reason.MALFORMED)
            val branchMatch = enumByName<MatchMode>(raw.match) ?: return Result.Invalid(Reason.MALFORMED)
            val branchConditions = when (val parsed = parseConditions(raw.conditions)) {
                is Parsed.Bad -> return parsed.result
                is Parsed.Ok -> parsed.value
            }
            val branchActions = when (val parsed = parseActions(raw.actions)) {
                is Parsed.Bad -> return parsed.result
                is Parsed.Ok -> parsed.value
            }
            elseIf += Branch(branchMatch, branchConditions, branchActions)
        }

        val elseActions = dto.elseActions?.let {
            when (val parsed = parseActions(it)) {
                is Parsed.Bad -> return parsed.result
                is Parsed.Ok -> parsed.value
            }
        }

        // An unknown trigger name is malformed, not "assume start": a rule imported from a
        // newer build would otherwise fire at the wrong moment, silently.
        val trigger = dto.trigger?.let {
            enumByName<RuleTrigger>(it) ?: return Result.Invalid(Reason.MALFORMED)
        }
        val rule = Rule(
            id = id,
            name = name,
            enabled = dto.enabled ?: true,
            match = match,
            trigger = trigger,
            conditions = conditions,
            actions = actions,
            // Absent stays absent: a re-export of an imported file must be the same file.
            elseIf = elseIf.takeIf { dto.elseIf != null },
            elseActions = elseActions
        )
        // Same bar the editor enforces: no condition means "always", no action means nothing,
        // and a rule addressed by a button must name one in every case it can run.
        if (!rule.isComplete()) return Result.Invalid(Reason.MALFORMED)
        if (!rule.buttonAddressingIsSound) return Result.Invalid(Reason.MALFORMED)

        return Result.Ok(listOf(rule))
    }

    /** A parsed list, or the refusal that stops the whole file. */
    private sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Bad(val result: Result) : Parsed<Nothing>
    }

    private fun parseConditions(raws: List<ConditionDto?>?): Parsed<List<Condition>> {
        val conditions = ArrayList<Condition>()
        for (raw in raws.orEmpty()) {
            if (raw == null) return Parsed.Bad(Result.Invalid(Reason.MALFORMED))
            val type = enumByName<ConditionType>(raw.type)
                ?: return Parsed.Bad(Result.Invalid(Reason.UNKNOWN_ENTRY, raw.type ?: ""))
            val op = enumByName<CompareOp>(raw.op) ?: return Parsed.Bad(Result.Invalid(Reason.MALFORMED))
            val number = raw.number ?: 0f
            if (!number.isFinite()) return Parsed.Bad(Result.Invalid(Reason.MALFORMED))
            val days = raw.days.orEmpty()
            // java.util.Calendar.SUNDAY..SATURDAY. An out-of-range day would make the
            // condition unmatchable while still looking configured in the editor.
            if (days.any { it == null || it !in 1..7 }) return Parsed.Bad(Result.Invalid(Reason.MALFORMED))
            conditions += Condition(
                type = type,
                op = op,
                number = number,
                flag = raw.flag ?: true,
                text = raw.text.orEmpty(),
                minutesFrom = raw.minutesFrom ?: 0,
                minutesTo = raw.minutesTo ?: 0,
                days = days.filterNotNull()
            )
        }
        return Parsed.Ok(conditions)
    }

    private fun parseActions(raws: List<ActionDto?>?): Parsed<List<Action>> {
        val actions = ArrayList<Action>()
        for (raw in raws.orEmpty()) {
            if (raw == null) return Parsed.Bad(Result.Invalid(Reason.MALFORMED))
            val type = enumByName<ActionType>(raw.type)
                ?: return Parsed.Bad(Result.Invalid(Reason.UNKNOWN_ENTRY, raw.type ?: ""))
            actions += Action(
                type = type,
                number = raw.number ?: 0,
                flag = raw.flag ?: true,
                text = raw.text.orEmpty(),
                minutesFrom = raw.minutesFrom ?: 0,
                minutesTo = raw.minutesTo ?: 0
            )
        }
        return Parsed.Ok(actions)
    }

    private inline fun <reified E : Enum<E>> enumByName(name: String?): E? =
        if (name == null) null else enumValues<E>().firstOrNull { it.name == name }

    // ---------- Wire format ----------
    // Every field nullable and defaulted: Gson leaves absent keys null, and the defaults give
    // Kotlin the no-arg constructor Gson needs.

    private class Envelope(
        val format: String? = null,
        val version: Int? = null,
        val rules: List<RuleDto?>? = null
    )

    private class RuleDto(
        val id: String? = null,
        val name: String? = null,
        val enabled: Boolean? = null,
        val match: String? = null,
        /** Absent in files written before triggers existed — those rules fire at start. */
        val trigger: String? = null,
        val conditions: List<ConditionDto?>? = null,
        val actions: List<ActionDto?>? = null,
        /** Absent in files written before branches existed — those rules have no other case. */
        val elseIf: List<BranchDto?>? = null,
        val elseActions: List<ActionDto?>? = null
    )

    private class BranchDto(
        val match: String? = null,
        val conditions: List<ConditionDto?>? = null,
        val actions: List<ActionDto?>? = null
    )

    private class ConditionDto(
        val type: String? = null,
        val op: String? = null,
        val number: Float? = null,
        val flag: Boolean? = null,
        val text: String? = null,
        val minutesFrom: Int? = null,
        val minutesTo: Int? = null,
        val days: List<Int?>? = null
    )

    private class ActionDto(
        val type: String? = null,
        val number: Int? = null,
        val flag: Boolean? = null,
        val text: String? = null,
        val minutesFrom: Int? = null,
        val minutesTo: Int? = null
    )
}
