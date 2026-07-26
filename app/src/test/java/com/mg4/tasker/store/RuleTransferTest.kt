package com.mg4.tasker.store

import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.CompareOp
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.MatchMode
import com.mg4.tasker.model.Rule
import org.junit.Assert.assertEquals
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
        val rules = listOf(rule(), rule(id = "r2", name = "Weekend"))

        val decoded = decodeOf(RuleTransfer.encode(rules))

        assertEquals(RuleTransfer.Result.Ok(rules), decoded)
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
        val json = """{"format":"mg4tasker-rules","version":2,"rules":[]}"""

        assertEquals(RuleTransfer.Reason.VERSION, invalidReason(json))
    }

    @Test
    fun `an unknown action names the entry this build does not know`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
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
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"MOON_PHASE","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.UNKNOWN_ENTRY, invalidReason(json))
    }

    @Test
    fun `an empty rule list is refused`() {
        val json = """{"format":"mg4tasker-rules","version":1,"rules":[]}"""

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
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a rule with no condition is refused`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[],"actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a blank name is refused`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"   ","enabled":true,"match":"ALL",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `a non-finite threshold is refused`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"OUTSIDE_TEMP","op":"LT","number":NaN}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `an out-of-range weekday is refused`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"ALL",
               "conditions":[{"type":"DAY_OF_WEEK","op":"EQ","days":[2,9]}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `an unknown match mode is refused`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
              {"id":"r1","name":"X","enabled":true,"match":"MOST",
               "conditions":[{"type":"IN_PARK","op":"EQ","flag":true}],
               "actions":[{"type":"SET_ONE_PEDAL","flag":true}]}]}
        """.trimIndent()

        assertEquals(RuleTransfer.Reason.MALFORMED, invalidReason(json))
    }

    @Test
    fun `absent optional fields fall back to the model defaults`() {
        val json = """
            {"format":"mg4tasker-rules","version":1,"rules":[
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
}
