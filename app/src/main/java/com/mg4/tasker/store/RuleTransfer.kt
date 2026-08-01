package com.mg4.tasker.store

import com.google.gson.Gson
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import com.mg4.tasker.model.RuleTrigger

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

    const val FORMAT = "mg4tasker-rules"
    const val VERSION = 1
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
            version = VERSION,
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
                    conditions = rule.conditions.map {
                        ConditionDto(
                            type = it.type.name,
                            op = it.op.name,
                            number = it.number,
                            flag = it.flag,
                            text = it.text,
                            minutesFrom = it.minutesFrom,
                            minutesTo = it.minutesTo,
                            days = it.days
                        )
                    },
                    actions = rule.actions.map {
                        ActionDto(
                            type = it.type.name,
                            number = it.number,
                            flag = it.flag,
                            text = it.text
                        )
                    }
                )
            }
        )
    )

    fun decode(json: String): Result {
        val envelope = try {
            Gson().fromJson(json, Envelope::class.java)
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

        return Result.Ok(rules)
    }

    /** One rule, or the reason it was refused. [Result.Ok] always holds exactly one rule here. */
    private fun toRule(dto: RuleDto?): Result {
        if (dto == null) return Result.Invalid(Reason.MALFORMED)

        val id = dto.id?.takeIf { it.isNotBlank() } ?: return Result.Invalid(Reason.MALFORMED)
        val name = dto.name?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.Invalid(Reason.MALFORMED)
        val match = enumByName<MatchMode>(dto.match) ?: return Result.Invalid(Reason.MALFORMED)

        val conditions = ArrayList<Condition>()
        for (raw in dto.conditions.orEmpty()) {
            if (raw == null) return Result.Invalid(Reason.MALFORMED)
            val type = enumByName<ConditionType>(raw.type)
                ?: return Result.Invalid(Reason.UNKNOWN_ENTRY, raw.type ?: "")
            val op = enumByName<CompareOp>(raw.op) ?: return Result.Invalid(Reason.MALFORMED)
            val number = raw.number ?: 0f
            if (!number.isFinite()) return Result.Invalid(Reason.MALFORMED)
            val days = raw.days.orEmpty()
            // java.util.Calendar.SUNDAY..SATURDAY. An out-of-range day would make the
            // condition unmatchable while still looking configured in the editor.
            if (days.any { it == null || it !in 1..7 }) return Result.Invalid(Reason.MALFORMED)
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

        val actions = ArrayList<Action>()
        for (raw in dto.actions.orEmpty()) {
            if (raw == null) return Result.Invalid(Reason.MALFORMED)
            val type = enumByName<ActionType>(raw.type)
                ?: return Result.Invalid(Reason.UNKNOWN_ENTRY, raw.type ?: "")
            actions += Action(
                type = type,
                number = raw.number ?: 0,
                flag = raw.flag ?: true,
                text = raw.text.orEmpty(),
                minutesFrom = raw.minutesFrom ?: 0,
                minutesTo = raw.minutesTo ?: 0
            )
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
            actions = actions
        )
        // Same bar the editor enforces: no condition means "always", no action means nothing.
        if (!rule.isComplete()) return Result.Invalid(Reason.MALFORMED)

        return Result.Ok(listOf(rule))
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
