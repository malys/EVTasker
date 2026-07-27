package com.mg4.tasker.store

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Mounted volumes and their contents, for the in-app file browser.
 *
 * The MG4 head unit ships no document picker — SAF answers "FileManagement is not supported on
 * this device" — so the app browses storage itself.
 *
 * Release builds run as `android.uid.system` (see `AndroidManifest.xml`), which is what makes
 * the whole USB stick readable rather than only this app's own folder. Builds without the
 * platform signature get no such privilege, so an unreadable directory is a normal outcome
 * here and never an error: the browser falls back to the app-specific folder, which is
 * readable on every build at every API level with no storage permission.
 */
object StorageBrowser {

    /** A volume the user can start browsing from. */
    data class Root(val dir: File, val removable: Boolean)

    /**
     * One entry per mounted volume, removable first — the USB stick is what the user came for.
     *
     * Roots are derived by walking up from `getExternalFilesDirs()` rather than read from
     * StorageManager: `StorageVolume.getDirectory()` is API 30 and minSdk here is 28, and the
     * walk-up needs no branch per API level.
     */
    fun roots(context: Context): List<Root> {
        val roots = mutableListOf<Root>()
        for (appDir in context.getExternalFilesDirs(null).filterNotNull()) {
            val dir = volumeRoot(appDir) ?: appDir
            if (roots.none { it.dir == dir }) roots += Root(dir, isRemovable(appDir))
        }
        return roots.sortedByDescending { it.removable }
    }

    /**
     * Directories first, then files, each alphabetically — the order the user scans with their
     * eyes. [extension] keeps only files with that extension; directories always stay, or the
     * user could not reach anything nested. Unreadable or missing directory: no entries, no
     * error.
     */
    fun children(dir: File, extension: String? = null): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { !it.isHidden && it.canRead() }
            .filter { it.isDirectory || extension == null || it.name.endsWith(".$extension", ignoreCase = true) }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /**
     * `/storage/XXXX-XXXX/Android/data/com.mg4.tasker/files` → `/storage/XXXX-XXXX`, or null
     * when that root cannot be listed — which is the whole point of the check, not a failure.
     */
    private fun volumeRoot(appDir: File): File? {
        var dir: File? = appDir
        repeat(4) { dir = dir?.parentFile }
        return dir?.takeIf { it.isDirectory && it.listFiles() != null }
    }

    /** A dir on an unmounted or odd volume makes this throw; treat that as "not removable". */
    private fun isRemovable(dir: File): Boolean = try {
        Environment.isExternalStorageRemovable(dir)
    } catch (_: IllegalArgumentException) {
        false
    }
}
