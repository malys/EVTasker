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
}
