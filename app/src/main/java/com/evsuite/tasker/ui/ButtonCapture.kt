package com.evsuite.tasker.ui

import android.content.Context
import android.content.DialogInterface
import com.evsuite.hardware.PhysicalButtonEventDecoder
import com.evsuite.tasker.R
import com.evsuite.tasker.service.TaskerVehicleService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Asks the driver to press a button, and names what the car reported.
 *
 * A name in a list is a guess at what a symbol on the wheel is called; the press is what the
 * car actually sends. While the dialog is open the presses go here and not to the rules, so
 * naming a button never runs what it is bound to.
 */
object ButtonCapture {

    fun show(
        context: Context,
        confirmRes: Int,
        onPicked: (PhysicalButtonEventDecoder.Event) -> Unit
    ) {
        var last: PhysicalButtonEventDecoder.Event? = null
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.button_capture_title)
            // The presses reach the app through the vehicle service; without it, nothing will.
            .setMessage(
                if (TaskerVehicleService.isRunning) R.string.button_capture_waiting
                else R.string.button_capture_no_service
            )
            .setNegativeButton(R.string.button_capture_cancel, null)
            .setPositiveButton(confirmRes) { _, _ -> last?.let(onPicked) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        }
        dialog.setOnDismissListener { TaskerVehicleService.buttonCapture = null }
        // A double tap arrives as a short press then a double: the latest one is the answer.
        TaskerVehicleService.buttonCapture = { event ->
            last = event
            dialog.setMessage(
                context.getString(
                    R.string.button_capture_detected,
                    ValueEditorDialog.buttonLabel(context, event.keyId),
                    ValueEditorDialog.pressLabel(context, event.press)
                )
            )
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
        }
        dialog.show()
    }
}
