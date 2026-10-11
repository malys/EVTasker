package com.evsuite.tasker.debug

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.tasker.model.Action
import com.evsuite.tasker.model.Branch
import com.evsuite.tasker.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugReportRedactionTest {

    private val webhook = Action(
        ActionType.WEBHOOK,
        text = "https://gate.example.com/v2/set?auth_key=SECRET",
        payload = "{\"id\":\"SECRET\"}"
    )
    private val call = Action(ActionType.CALL_NUMBER, text = "+33600000000", displayName = "Someone — +33600000000")

    @Test fun `secrets leave every branch, the rest stays`() {
        val rule = Rule(
            name = "r",
            actions = listOf(webhook, Action(ActionType.SET_REGEN_LEVEL, number = 2)),
            elseIf = listOf(Branch(actions = listOf(call))),
            elseActions = listOf(Action(ActionType.SEND_SMS, text = "+33600000000", payload = "msg"))
        )
        val out = DebugReport.redacted(listOf(rule)).single()

        assertFalse(out.toString().contains("SECRET"))
        assertFalse(out.toString().contains("+33600000000"))
        assertEquals("https://gate.example.com/(redacted)", out.actions[0].text)
        assertEquals(rule.actions[1], out.actions[1])
        assertEquals("msg", out.otherwise.single().payload)
    }

    @Test fun `an unparseable webhook is masked whole`() {
        val out = DebugReport.redacted(listOf(Rule(name = "r", actions = listOf(webhook.copy(text = "not a url ?k=SECRET")))))
        assertEquals("(redacted)", out.single().actions.single().text)
    }
}
