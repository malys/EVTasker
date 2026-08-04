package com.mg4.tasker.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.mg4.tasker.R
import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Branch
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.Rule
import com.mg4.tasker.store.RuleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * End-to-end: pick one condition and one action in a case window, confirm it, and check the
 * rule screen carries the cases it was given and saves them.
 * This is the path the user reports as "the rule is never saved".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleEditorFlowTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** AlertDialog dispatches its button clicks through a Handler; drain it. */
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Lays a dialog out so its RecyclerView actually binds view holders under Robolectric. */
    private fun layout(dialog: Dialog) {
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1920, 720)
    }

    private fun firstEntryOf(dialog: Dialog): View? {
        val grid = dialog.findViewById<RecyclerView>(R.id.pickerGrid)
        layout(dialog)
        return grid.findViewHolderForAdapterPosition(0)?.itemView
    }

    @Test
    fun `condition and action picked through the pickers come back as a case`() {
        val intent = BranchEditorActivity.intent(
            context(), BranchEditorActivity.Kind.IF, 0, Branch()
        )
        val activity = Robolectric.buildActivity(BranchEditorActivity::class.java, intent).setup().get()

        // --- condition ---
        activity.findViewById<View>(R.id.addConditionButton).performClick()
        val conditionPicker = ShadowDialog.getLatestDialog()
        assertNotNull("condition picker never opened", conditionPicker)
        val conditionEntry = firstEntryOf(conditionPicker)
        assertNotNull("condition picker showed no entry", conditionEntry)
        conditionEntry!!.performClick()
        idle()

        // The value dialog must be the dialog now on screen, and its OK button must commit.
        val valueDialog = ShadowDialog.getLatestDialog()
        assertNotNull("value editor never opened for the condition", valueDialog)
        valueDialog!!.findViewById<View>(R.id.editorSave).performClick()
        idle()

        assertTrue(
            "no condition row rendered",
            activity.findViewById<ViewGroup>(R.id.conditionContainer).childCount > 0
        )

        // --- action ---
        activity.findViewById<View>(R.id.addActionButton).performClick()
        val actionPicker = ShadowDialog.getLatestDialog()
        val actionEntry = firstEntryOf(actionPicker)
        assertNotNull("action picker showed no entry", actionEntry)
        actionEntry!!.performClick()
        idle()
        ShadowDialog.getLatestDialog().findViewById<View>(R.id.editorSave).performClick()
        idle()

        assertTrue(
            "no action row rendered",
            activity.findViewById<ViewGroup>(R.id.actionContainer).childCount > 0
        )

        // --- confirm ---
        activity.findViewById<View>(R.id.branchDoneButton).performClick()
        idle()

        val shadow = shadowOf(activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        val branch = BranchEditorActivity.resultBranch(shadow.resultIntent)
        assertNotNull("the case never came back", branch)
        assertEquals(1, branch!!.conditions.size)
        assertEquals(1, branch.actions.size)
    }

    @Test
    fun `an else case with no action is refused instead of coming back empty`() {
        val intent = BranchEditorActivity.intent(
            context(), BranchEditorActivity.Kind.ELSE, 0, Branch()
        )
        val activity = Robolectric.buildActivity(BranchEditorActivity::class.java, intent).setup().get()

        activity.findViewById<View>(R.id.branchDoneButton).performClick()
        idle()

        assertTrue("the window must stay open", !activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun `a branched rule shows one card per case and saves them all`() {
        val store = RuleStore(context())
        val rule = Rule(
            name = "cases",
            conditions = listOf(Condition(ConditionType.IN_PARK, flag = true)),
            actions = listOf(Action(ActionType.SET_ONE_PEDAL)),
            elseIf = listOf(
                Branch(
                    conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP, number = 5f)),
                    actions = listOf(Action(ActionType.SET_FAN_LEVEL, number = 1))
                )
            ),
            elseActions = listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 8))
        )
        assertEquals(RuleStore.SaveResult.OK, store.save(rule))

        val activity = Robolectric.buildActivity(
            RuleEditorActivity::class.java, RuleEditorActivity.intentForEdit(context(), rule.id)
        ).setup().get()

        assertEquals(
            "if, else if and else each get a card",
            3,
            activity.findViewById<ViewGroup>(R.id.branchContainer).childCount
        )

        activity.findViewById<View>(R.id.saveButton).performClick()
        idle()

        val saved = store.getById(rule.id)
        assertNotNull("rule was not persisted", saved)
        assertEquals(1, saved!!.elseIfBranches.size)
        assertEquals(listOf(Action(ActionType.SET_MEDIA_VOLUME, number = 8)), saved.otherwise)
    }
}
