package com.evsuite.tasker.store

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.CompareOp
import com.evsuite.tasker.model.Condition
import com.evsuite.tasker.model.MAX_ELSE_IF
import com.evsuite.tasker.model.MatchMode
import com.evsuite.tasker.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rules file comes off a USB stick, so every refusal path matters as much as the happy one:
 * an accepted file is applied to a car.
 */
class RuleTransferTest {

    private fun rule(id: String = "r1", name: String = "Cold morning") = Rule(
        id = id,
        name = name,
        enabled = false,
        match = MatchMode.ANY,
        conditions = listOf(
            Condition(
                type = ConditionType.OUTSIDE_TEMP,
                op = CompareOp.LT,
                number = 4.5f
            ),
            Condition(
                type = ConditionType.DAY_OF_WEEK,
                days = listOf(2, 3, 4, 5, 6)
            )
        ),
        actions = listOf(Action(type = ActionType.SET_STEERING_HEAT, number = 2))
    )

    private fun decodeOf(json: String) = RuleTransfer.decode(json)

    private fun invalidReason(json: String): RuleTransfer.Reason {
        val result = decodeOf(json)
        return (result as? RuleTransfer.Result.Invalid)?.reason
            ?: throw AssertionError("expected Invalid, got $result")
    }

    @Test
    fun `round trip preserves every field`() {
        val rules = listOf(
            rule().copy(
                actions = listOf(
                    Action(
                        type = ActionType.WEBHOOK,
                        text = "https://example.invalid/hook",
                        payload = "{\"ready\":true}",
                        displayName = "Example webhook",
                        minutesFrom = 1320,
                        minutesTo = 360
                    )
                )
            ),
            rule(id = "r2", name = "Weekend")
        )

        val decoded = decodeOf(RuleTransfer.encode(rules))

        assertEquals(RuleTransfer.Result.Ok(rules), decoded)
    }

    @Test
    fun `a text message keeps its recipient, its contact label and its body`() {
        // The message rides in the field a webhook body uses, and the contact label in the
        // one the call action fills. An export that dropped either would import as an action
        // addressed to a number with nothing to say.
        val rules = listOf(
            rule().copy(
                actions = listOf(
                    Action(
                        type = ActionType.SEND_SMS,
                        text = "+33600000000",
                        displayName = "Alex — Mobile — +33600000000",
                        payload = "On my way"
                    )
                )
            )
        )

        assertEquals(RuleTransfer.Result.Ok(rules), decodeOf(RuleTransfer.encode(rules)))
    }

    @Test
    fun `an MG4Tasker export remains importable after the rename`() {
        val legacy = RuleTransfer.encode(listOf(rule()))
            .replace("\"format\":\"evtasker-rules\"", "\"format\":\"mg4tasker-rules\"")

        assertEquals(RuleTransfer.Result.Ok(listOf(rule())), decodeOf(legacy))
    }

    @Test
    fun `json from another app is not a rules file`() {
        assertEquals(RuleTransfer.Result.NotARulesFile, decodeOf("""{"some":"other file"}"""))
    }

    @Test
    fun `garbage is not a rules file`() {
        assertEquals(RuleTransfer.Result.NotARulesFile, decodeOf("not json at all {{{"))
    }

    @Test
    fun `a newer format version is refused rather than partly read`() {
        val json = """{"format":"evtasker-rules","version":3,"rules":[]}"""

        assertEquals(RuleTransfer.Reason.VERSION, invalidReason(json))
    }

    @Test
    fun `an unknown action names the entry this build does not know`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"SET_WARP_DRIVE","number":1}]}]}
        """.trimIndent()

        val result = decodeOf(json) as RuleTransfer.Result.Invalid

        assertEquals(RuleTransfer.Reason.UNKNOWN_ENTRY, result.reason)
        assertEquals("SET_WARP_DRIVE", result.detail)
    }

    @Test
    fun `an unknown condition is refused, not dropped`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"MOON_PHASE","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.UNKNOWN_ENTRY, invalidReason(json))
    }

    @Test
    fun `an empty rule list is refused`() {
        val json = """{"format":"evtasker-rules","version":1,"rules":[]}"""

        assertEquals(RuleTransfer.Reason.EMPTY, invalidReason(json))
    }

    @Test
    fun `more rules than the quota is refused`() {
        val many = (0..RuleStore.MAX_RULES).map { rule(id = "r$it", name = "Rule $it") }

        assertEquals(RuleTransfer.Reason.TOO_MANY, invalidReason(RuleTransfer.encode(many)))
    }

    @Test
    fun `duplicate ids are refused`() {
        val json = RuleTransfer.encode(listOf(rule(id = "same"), rule(id = "same", name = "Other")))

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a rule with no action is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a rule with no condition is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[],"actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a blank name is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"   ","enabled":true,"match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a non-finite threshold is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"OUTSIDE_TEMP","op":"LT","number":NaN}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `an out-of-range weekday is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"DAY_OF_WEEK","op":"EQ","days":[2,9]}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `an unknown match mode is refused`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"MOST",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `absent optional fields fall back to the model defaults`() {
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}]}]}
        """.trimIndent()

        val rules = (decodeOf(json) as RuleTransfer.Result.Ok).rules

        assertEquals(
            listOf(
                Rule(
                    id = "r1",
                    name = "X",
                    enabled = true,
                    match = MatchMode.ALL,
                    conditions = listOf(Condition(ConditionType.IN_PARK, CompareOp.EQ)),
                    actions = listOf(Action(ActionType.SET_ONE_PEDAL))
                )
            ),
            rules
        )
    }

    // ------------------------------------------------------------- branches

    private fun branched(id: String = "b1") = rule(id = id).copy(
        elseIf = listOf(
            Branch(
                match = MatchMode.ALL,
                conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, CompareOp.LT, 15f)),
                actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
            )
        ),
        elseActions = listOf(Action(ActionType.SET_ONE_PEDAL))
    )

    @Test
    fun `round trip preserves every case`() {
        val rules = listOf(branched())

        assertEquals(RuleTransfer.Result.Ok(rules), decodeOf(RuleTransfer.encode(rules)))
    }

    @Test
    fun `a file with no branch still claims the older version`() {
        // Otherwise a build without branches refuses backups whose rules it understands.
        assertTrue(RuleTransfer.encode(listOf(rule())).contains("\"version\":1"))
        assertTrue(RuleTransfer.encode(listOf(branched())).contains("\"version\":2"))
    }

    @Test
    fun `a rule with no branch exports without the branch keys`() {
        val json = RuleTransfer.encode(listOf(rule()))

        assertFalse(json.contains("elseIf"))
        assertFalse(json.contains("elseActions"))
    }

    @Test
    fun `more else if cases than a rule may carry is refused`() {
        val case = """{"match":"ALL","conditions":[{"type":"IN_PARK","op":"EQ"}],
                       "actions":[{"type":"SET_ONE_PEDAL"}]}"""
        val json = """
            {"format":"evtasker-rules","version":2,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}],
               "elseIf":[${List(MAX_ELSE_IF + 1) { case }.joinToString(",")}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.TOO_MANY, invalidReason(json))
    }

    @Test
    fun `an action unknown to this build is refused wherever it hides`() {
        // In the "else" the entry is as unapplicable as in the "if": accepting the file and
        // dropping the case would apply a rule the file did not describe.
        val json = """
            {"format":"evtasker-rules","version":2,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}],
               "elseActions":[{"type":"LAUNCH_ROCKET"}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.UNKNOWN_ENTRY, invalidReason(json))
    }

    @Test
    fun `an else if with no condition is refused`() {
        val json = """
            {"format":"evtasker-rules","version":2,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}],
               "elseIf":[{"match":"ALL","conditions":[],"actions":[{"type":"SET_ONE_PEDAL"}]}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `cases claiming the version that predates them are refused`() {
        // Version 1 tells a build without cases it may apply these rules — which it would do
        // by keeping the "if" and dropping everything after it.
        val json = """
            {"format":"evtasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}],
               "elseActions":[{"type":"SET_ONE_PEDAL"}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a case that does not name the button of a button rule is refused`() {
        val json = """
            {"format":"evtasker-rules","version":2,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"PHYSICAL_BUTTON","op":"EQ"}],
               "actions":[{"type":"SET_ONE_PEDAL"}],
               "elseIf":[{"match":"ALL","conditions":[{"type":"IN_PARK","op":"EQ"}],
                          "actions":[{"type":"SET_ONE_PEDAL"}]}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a file written before the webhook merge imports with its verb kept`() {
        val json = """
            {"format":"evtasker-rules","version":2,"rules":[
              {"id":"r1","name":"X","match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ"}],
               "actions":[{"type":"WEBHOOK_GET","flag":true,"text":"https://a"}],
               "elseActions":[{"type":"WEBHOOK_POST","flag":false,"text":"https://b"}]}]}
        """.trimIndent()

        val rule = (decodeOf(json) as RuleTransfer.Result.Ok).rules.single()

        assertEquals(ActionType.WEBHOOK, rule.actions.single().type)
        assertFalse(rule.actions.single().flag)
        assertEquals(ActionType.WEBHOOK, rule.elseActions!!.single().type)
        assertTrue(rule.elseActions!!.single().flag)
    }
}
