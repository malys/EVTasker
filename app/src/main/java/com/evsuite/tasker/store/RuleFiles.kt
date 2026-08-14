package com.evsuite.tasker.store

import android.content.Context
import com.evsuite.tasker.model.Rule
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reading and writing a rules file, once the user has browsed to one.
 *
 * Finding the file is [StorageBrowser]'s job — nothing here searches storage. The user picks an
 * exact path, so an unusable file is reported against that path instead of being skipped.
 */
object RuleFiles {

    private const val NAME_PREFIX = "evtasker-rules-"

    /**
     * @return the file written, or null when nothing on that volume was writable.
     *
     * A stick whose root refuses writes still takes the app-specific folder on the same
     * volume ([StorageBrowser.writableTarget]), so the file lands on the stick either way.
     */
    fun export(context: Context, rules: List<Rule>, dir: File): File? {
        val into = StorageBrowser.writableTarget(context, dir) ?: return null
        val target = File(into, fileName())
        val temp = File(into, target.name + ".tmp")
        return try {
            // Temp then rename: a stick pulled mid-write leaves the .tmp, never a truncated
            // rules file that would later import as garbage.
            temp.writeText(RuleTransfer.encode(rules))
            if (temp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * A file the user pointed at. Unreadable or oversized reads as
     * [RuleTransfer.Reason.MALFORMED]: they chose this path, so "cannot use it" is the answer
     * they need, not silence.
     */
    fun read(file: File): RuleTransfer.Result {
        val text = readCapped(file)
            ?: return RuleTransfer.Result.Invalid(RuleTransfer.Reason.MALFORMED)
        return RuleTransfer.decode(text)
    }

    /** Timestamped: an export is a backup, and overwriting the previous one loses it. */
    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$NAME_PREFIX$stamp.${RuleTransfer.FILE_EXTENSION}"
    }

    /**
     * File text, or null when it cannot be a rules file. The length is checked before reading so
     * a wrong pick — a huge binary sitting on the stick — is never slurped into memory.
     */
    private fun readCapped(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        if (file.length() == 0L || file.length() > RuleTransfer.MAX_BYTES) return null
        return try {
            file.readText()
        } catch (_: IOException) {
            null
        }
    }
}
