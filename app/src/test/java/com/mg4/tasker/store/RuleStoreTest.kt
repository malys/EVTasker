package com.mg4.tasker.store

import androidx.test.core.app.ApplicationProvider
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleStoreTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sampleRule(name: String = "test") = Rule(
        name = name,
        conditions = listOf(Condition(type = ConditionType.entries.first())),
        actions = listOf(Action(type = ActionType.entries.first()))
    )

    @Test
    fun `saved rule is read back`() {
        val store = RuleStore(context())
        val rule = sampleRule()

        assertEquals(RuleStore.SaveResult.OK, store.save(rule))

        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals(rule.id, all[0].id)
        assertEquals(rule.name, all[0].name)
        assertEquals(rule.conditions, all[0].conditions)
        assertEquals(rule.actions, all[0].actions)
    }

    @Test
    fun `a second store instance sees the saved rule`() {
        val rule = sampleRule()
        RuleStore(context()).save(rule)

        assertEquals(1, RuleStore(context()).getAll().size)
    }

    @Test
    fun `the quota is reported as its own outcome, not as a write failure`() {
        val store = RuleStore(context())
        repeat(RuleStore.MAX_RULES) { assertEquals(RuleStore.SaveResult.OK, store.save(sampleRule("r$it"))) }

        assertEquals(RuleStore.SaveResult.QUOTA_REACHED, store.save(sampleRule("one too many")))
    }

    @Test
    fun `editing an existing rule is not refused once the quota is full`() {
        val store = RuleStore(context())
        val rules = (0 until RuleStore.MAX_RULES).map { sampleRule("r$it") }
        rules.forEach { store.save(it) }

        assertEquals(RuleStore.SaveResult.OK, store.save(rules.first().copy(name = "renamed")))
        assertEquals("renamed", store.getById(rules.first().id)?.name)
    }

    /**
     * A catalog entry that an update removed leaves rules on disk naming it. Gson builds the
     * enum field as null there, so reading such a rule back used to hand the engine and the
     * list a null [ConditionType] and crash on the first upgrade run. The rule cannot be
     * honoured any more, but the other rules must survive.
     */
    @Test
    fun `a rule naming a catalog entry that no longer exists is dropped, not crashed on`() {
        val store = RuleStore(context())
        store.save(sampleRule("still valid"))
        val json = context().getSharedPreferences("mg4_tasker_rules", android.content.Context.MODE_PRIVATE)
            .getString("rules_json", null)!!
        val withRemovedType = json.dropLast(1) + """,{"id":"gone","name":"old button rule",
            "enabled":true,"match":"ALL","trigger":"PHYSICAL_BUTTON",
            "conditions":[{"type":"STAR_LEFT_SHORT_PRESS","op":"EQ","number":0.0,"flag":true,
            "text":"","minutesFrom":0,"minutesTo":0,"days":[]}],
            "actions":[{"type":"SET_MEDIA_VOLUME","number":5,"flag":false,"text":"",
            "minutesFrom":0,"minutesTo":0}]}]"""
        context().getSharedPreferences("mg4_tasker_rules", android.content.Context.MODE_PRIVATE)
            .edit().putString("rules_json", withRemovedType).commit()

        val all = RuleStore(context()).getAll()

        assertEquals(1, all.size)
        assertEquals("still valid", all[0].name)
        all.forEach { rule -> rule.conditions.forEach { it.type.name }; rule.actions.forEach { it.type.name } }
    }

    /**
     * Same failure hidden one level deeper. A removed entry inside an "else if" or the "else"
     * only surfaces when that case is the one that matches — on the car, on a later drive.
     */
    @Test
    fun `a removed catalog entry inside another case drops the rule too`() {
        val store = RuleStore(context())
        store.save(sampleRule("still valid"))
        val prefs = context()
            .getSharedPreferences("mg4_tasker_rules", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("rules_json", null)!!
        val withRemovedType = json.dropLast(1) + """,{"id":"gone","name":"branched rule",
            "enabled":true,"match":"ALL",
            "conditions":[{"type":"IN_PARK","op":"EQ","number":0.0,"flag":true,
            "text":"","minutesFrom":0,"minutesTo":0,"days":[]}],
            "actions":[{"type":"SET_MEDIA_VOLUME","number":5,"flag":false,"text":"",
            "minutesFrom":0,"minutesTo":0}],
            "elseActions":[{"type":"STAR_LEFT_LONG_PRESS_ACTION","number":1,"flag":true,
            "text":"","minutesFrom":0,"minutesTo":0}]}]"""
        prefs.edit().putString("rules_json", withRemovedType).commit()

        val all = RuleStore(context()).getAll()

        assertEquals(1, all.size)
        assertEquals("still valid", all[0].name)
    }

    /**
     * The webhook rule saved by an older build names WEBHOOK_GET / WEBHOOK_POST, entries this
     * catalog merged into one. Read as-is, Gson nulls the type and the rule is dropped as
     * unhonourable — the user's rule disappearing on update is exactly the bug being guarded.
     */
    @Test
    fun `a webhook rule saved before the merge survives, verb and all`() {
        val prefs = context()
            .getSharedPreferences("mg4_tasker_rules", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("rules_json", """[{"id":"r1","name":"old webhook",
            "enabled":true,"match":"ALL",
            "conditions":[{"type":"IN_PARK","op":"EQ","number":0.0,"flag":true,
            "text":"","minutesFrom":0,"minutesTo":0,"days":[]}],
            "actions":[{"type":"WEBHOOK_GET","number":0,"flag":true,"text":"https://a",
            "minutesFrom":0,"minutesTo":0},
            {"type":"WEBHOOK_POST","number":0,"flag":false,"text":"https://b",
            "minutesFrom":0,"minutesTo":0}]}]""").commit()

        val rule = RuleStore(context()).getAll().single()

        assertEquals(listOf(ActionType.WEBHOOK, ActionType.WEBHOOK), rule.actions.map { it.type })
        assertEquals(listOf(false, true), rule.actions.map { it.flag })
    }
}
