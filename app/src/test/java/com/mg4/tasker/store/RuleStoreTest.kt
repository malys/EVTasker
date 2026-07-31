package com.mg4.tasker.store

import androidx.test.core.app.ApplicationProvider
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertTrue(store.save(rule))

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
}
