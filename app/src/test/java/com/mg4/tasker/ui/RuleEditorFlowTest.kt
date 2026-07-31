package com.mg4.tasker.ui

import android.app.Dialog
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.mg4.tasker.R
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
 * End-to-end: open the editor, pick one condition, pick one action, save.
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
    fun `condition and action picked through the pickers end up in a saved rule`() {
        val activity = Robolectric.buildActivity(RuleEditorActivity::class.java).setup().get()

        // --- name ---
        activity.findViewById<android.widget.EditText>(R.id.nameInput).setText("flow")

        // --- condition ---
        activity.findViewById<View>(R.id.addConditionButton).performClick()
        val conditionPicker = ShadowDialog.getLatestDialog()
        assertNotNull("condition picker never opened", conditionPicker)
        val conditionEntry = firstEntryOf(conditionPicker)
        assertNotNull("condition picker showed no entry", conditionEntry)
        conditionEntry!!.performClick()
        idle()

        // The value dialog must be the dialog now on screen, and its OK button must commit.
        val valueDialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        assertNotNull("value editor never opened for the condition", valueDialog)
        valueDialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
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
        (ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)
            ?.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
        idle()

        assertTrue(
            "no action row rendered",
            activity.findViewById<ViewGroup>(R.id.actionContainer).childCount > 0
        )

        // --- save ---
        activity.findViewById<View>(R.id.saveButton).performClick()
        idle()

        assertEquals("rule was not persisted", 1, RuleStore(context()).getAll().size)
    }
}
