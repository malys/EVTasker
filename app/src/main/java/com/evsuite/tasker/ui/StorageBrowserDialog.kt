package com.evsuite.tasker.ui

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.evsuite.tasker.R
import com.evsuite.tasker.store.StorageBrowser
import java.io.File

/**
 * File and folder picker built from plain alert dialogs — one dialog per directory, re-shown on
 * every step.
 *
 * The MG4 head unit has no document picker to delegate to, so this is the browser. A chain of
 * dialogs needs no layout, no fragment and no back-stack handling, and the rows are the size of
 * a dialog list item, which is what a driver taps with one finger.
 */
class StorageBrowserDialog private constructor(
    private val context: Context,
    /** File extension to offer, or null for folder mode. */
    private val extension: String?,
    @StringRes private val titleRes: Int,
    private val onPicked: (File) -> Unit
) {

    companion object {
        /** Browse to an existing file with the given extension. */
        fun pickFile(
            context: Context,
            extension: String,
            @StringRes titleRes: Int,
            onPicked: (File) -> Unit
        ) = StorageBrowserDialog(context, extension, titleRes, onPicked).start()

        /** Browse to a directory to write into. */
        fun pickFolder(
            context: Context,
            @StringRes titleRes: Int,
            onPicked: (File) -> Unit
        ) = StorageBrowserDialog(context, null, titleRes, onPicked).start()
    }

    private val roots = StorageBrowser.roots(context)

    private fun start() {
        when (roots.size) {
            0 -> MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setMessage(R.string.browser_no_storage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            // A single volume makes the chooser a list of one: open it directly.
            1 -> showDir(roots[0].dir)
            else -> showRoots()
        }
    }

    private fun showRoots() {
        val labels = roots.map { root ->
            val name = context.getString(
                if (root.removable) R.string.browser_usb else R.string.browser_internal
            )
            "$name\n${root.dir.absolutePath}"
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which -> showDir(roots[which].dir) }
            .setNegativeButton(R.string.editor_cancel, null)
            .show()
    }

    private fun showDir(dir: File) {
        val entries = StorageBrowser.children(dir, extension)
        val labels = mutableListOf(context.getString(R.string.browser_up))
        // A trailing separator is the only affordance a plain list item has for "this opens".
        labels += entries.map { if (it.isDirectory) "${it.name}/" else it.name }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(dir.absolutePath)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) goUp(dir) else entries[which - 1].let { entry ->
                    if (entry.isDirectory) showDir(entry) else onPicked(entry)
                }
            }
            .setNegativeButton(R.string.editor_cancel, null)

        // Folder mode has no file to tap: the directory the user stopped on is the answer.
        if (extension == null) {
            builder.setPositiveButton(R.string.browser_use_folder) { _, _ -> onPicked(dir) }
        }
        builder.show()
    }

    /** Up from a volume root goes back to the volume list, never to `/storage` itself. */
    private fun goUp(dir: File) {
        if (roots.none { it.dir == dir }) {
            showDir(dir.parentFile ?: return)
        } else if (roots.size > 1) {
            showRoots()
        }
        // Single root, already at the top: the dialog has closed, which is the way out.
    }
}
