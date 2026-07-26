package com.mg4.tasker.store

import android.content.Context
import android.os.Environment
import com.mg4.tasker.model.Rule
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rules files on removable storage.
 *
 * The MG4 head unit ships no document picker — SAF answers "FileManagement is not supported
 * on this device" — so files are found by scanning instead of being picked. `getExternalFilesDirs()`
 * returns this app's own folder on internal storage *and* on every mounted volume including a
 * USB stick, and those need no storage permission at any API level. Same approach as
 * ABRP_Uploader's config import, for the same reason.
 */
object RuleFiles {

    private const val NAME_PREFIX = "mg4tasker-rules-"

    /**
     * Where an export goes: a removable volume when one is mounted, internal storage otherwise.
     * Also the folder the "no file found" message points the user at.
     */
    fun exportTarget(context: Context): File? {
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val target = dirs.firstOrNull { isRemovable(it) } ?: dirs.firstOrNull() ?: return null
        return target.takeIf { it.isDirectory || it.mkdirs() }
    }

    /** Absolute path of [exportTarget], or "" when no volume is usable. */
    fun hint(context: Context): String = exportTarget(context)?.absolutePath ?: ""

    /** @return the file written, or null when no volume was writable. */
    fun export(context: Context, rules: List<Rule>): File? {
        val dir = exportTarget(context) ?: return null
        val target = File(dir, fileName())
        val temp = File(dir, target.name + ".tmp")
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
     * Every `.json` in an app-specific folder that claims to be a rules file, decoded — newest
     * first, so the most recent backup leads the chooser. [RuleTransfer.Result.NotARulesFile]
     * entries are dropped: unrelated JSON on the stick is not something to report.
     */
    fun scan(context: Context): List<Pair<File, RuleTransfer.Result>> {
        val found = mutableListOf<Pair<File, RuleTransfer.Result>>()
        for (dir in context.getExternalFilesDirs(null).filterNotNull()) {
            val files = dir.listFiles() ?: continue
            for (file in files.sortedByDescending { it.lastModified() }) {
                val text = readCapped(file) ?: continue
                val result = RuleTransfer.decode(text)
                if (result != RuleTransfer.Result.NotARulesFile) found += file to result
            }
        }
        return found
    }

    /** Timestamped: an export is a backup, and overwriting the previous one loses it. */
    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$NAME_PREFIX$stamp.${RuleTransfer.FILE_EXTENSION}"
    }

    /**
     * File text, or null when it cannot be a rules file. The length is checked before reading so
     * a wrong file — a huge binary sitting on the stick — is never slurped into memory.
     */
    private fun readCapped(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        if (!file.name.endsWith(".${RuleTransfer.FILE_EXTENSION}", ignoreCase = true)) return null
        if (file.length() == 0L || file.length() > RuleTransfer.MAX_BYTES) return null
        return try {
            file.readText()
        } catch (_: IOException) {
            null
        }
    }

    /** A dir on an unmounted or odd volume makes this throw; treat that as "not removable". */
    private fun isRemovable(dir: File): Boolean = try {
        Environment.isExternalStorageRemovable(dir)
    } catch (_: IllegalArgumentException) {
        false
    }
}
