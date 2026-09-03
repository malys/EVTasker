package com.evsuite.tasker.store

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.Rule
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an unanswered confirmation does, and how a rule carries that answer.
 *
 * The setting is permissive in one direction only, which is why it has a field of its own:
 * reading it off `Action.flag` — which every editor save writes, for every action, with the
 * model default `true` — would have turned every confirmation ever written into one that
 * proceeds on silence. That is the failure this file exists to keep out.
 */
class ConfirmSilenceRuleTest {

    private val gson = Gson()

    private fun confirmFrom(json: String): Action =
        gson.fromJson(LegacyRuleJson.migrate(json), Array<Rule>::class.java)[0].actions[0]

    @Test
    fun `silence stops the rule unless the rule says otherwise`() {
        // The model default is the cautious one, so an action built anywhere in the app —
        // editor, import, test — starts by needing a deliberate yes.
        assertFalse(Action(type = ActionType.ASK_CONFIRM, text = "Open the windows?").yesOnNoAnswer)
    }

    @Test
    fun `a confirmation saved before the setting existed keeps needing a yes`() {
        // The dangerous shape: flag is true because it is the model default and the editor
        // writes it for every action, so a reading that used flag would flip this rule to
        // "acts on silence" the moment the app updated.
        val action = confirmFrom(
            """[{"id":"r1","name":"Arrival","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"ASK_CONFIRM","number":20,"flag":true,"text":"Unlock?"}]}]"""
        )
        assertEquals(ActionType.ASK_CONFIRM, action.type)
        assertTrue("flag is what the old editor wrote", action.flag)
        assertFalse("silence must still stop the rule", action.yesOnNoAnswer)
    }

    @Test
    fun `a rule that says silence is a yes is read back as one`() {
        val action = confirmFrom(
            """[{"id":"r1","name":"Arrival","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"ASK_CONFIRM","number":20,"text":"Close up?","yesOnNoAnswer":true}]}]"""
        )
        assertTrue(action.yesOnNoAnswer)
    }

    @Test
    fun `an exported rule carries the setting to the next car`() {
        // Without the field on the wire, a rule that acts on silence would import as one that
        // stops on it — the same question, the opposite behaviour, and nothing saying so.
        val rules = listOf(
            Rule(
                id = "r1",
                name = "Leaving",
                conditions = listOf(Condition(type = ConditionType.IN_PARK)),
                actions = listOf(
                    Action(
                        type = ActionType.ASK_CONFIRM,
                        number = 30,
                        text = "Lock the doors?",
                        yesOnNoAnswer = true
                    )
                )
            )
        )

        assertEquals(RuleTransfer.Result.Ok(rules), RuleTransfer.decode(RuleTransfer.encode(rules)))
    }

    @Test
    fun `a file exported before the setting imports as needing a yes`() {
        val exported = RuleTransfer.encode(
            listOf(
                Rule(
                    id = "r1",
                    name = "Leaving",
                    conditions = listOf(Condition(type = ConditionType.IN_PARK)),
                    actions = listOf(
                        Action(type = ActionType.ASK_CONFIRM, number = 30, text = "Lock?", yesOnNoAnswer = true)
                    )
                )
            )
        ).replace("\"yesOnNoAnswer\":true", "\"unrelated\":true")

        val decoded = RuleTransfer.decode(exported) as RuleTransfer.Result.Ok
        assertFalse(decoded.rules[0].actions[0].yesOnNoAnswer)
    }
}
