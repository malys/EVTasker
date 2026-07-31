package com.mg4.tasker.store

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
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
     * Mount points the head unit uses for a USB stick. The car does not register the stick
     * as an app-visible external volume, so `getExternalFilesDirs()` never reports it and no
     * `Android/data` tree is ever created on it — the stick is simply mounted here. Each
     * listable child of these directories is a volume.
     */
    private val VOLUME_PARENTS = listOf("/storage", "/mnt/media_rw", "/mnt/usb")

    /** Sticks mounted straight onto a fixed path rather than under a parent directory. */
    private val VOLUME_PATHS = listOf("/mnt/usbotg", "/mnt/udisk", "/udisk", "/mnt/external_sd")

    /** Never volumes: emulated primary storage is already reported, the rest are plumbing. */
    private val NOT_VOLUMES = setOf("emulated", "self", "container", "enc_emulated", "knox-emulated")

    /**
     * One entry per mounted volume, removable first — the USB stick is what the user came for.
     *
     * Three sources, in order of trust, deduplicated by path:
     *  1. `getExternalFilesDirs()` walked up to its volume — always right when the platform
     *     reports the volume at all.
     *  2. [StorageManager]'s volume list — catches a stick the platform mounted but never gave
     *     this app an `Android/data` directory on.
     *  3. The raw mount points above — catches the MG4 head unit, which does neither.
     *
     * Anything unreadable is dropped rather than offered: a root the browser cannot list is a
     * dead end for the user.
     */
    fun roots(context: Context): List<Root> {
        val roots = LinkedHashMap<String, Root>()

        fun add(dir: File, removable: Boolean) {
            if (!dir.isDirectory || dir.listFiles() == null) return
            // First source wins: it also carries the more trustworthy "removable" answer.
            roots.getOrPut(canonical(dir)) { Root(dir, removable) }
        }

        for (appDir in context.getExternalFilesDirs(null).filterNotNull()) {
            val removable = isRemovable(appDir)
            // The volume root is the useful place to start; when it cannot be listed, the
            // app-specific folder still can, and on a stick that folder is on the stick.
            val volume = volumeRoot(appDir)
            if (volume != null) add(volume, removable) else add(appDir, removable)
        }

        for (volume in storageManagerVolumes(context)) add(volume.dir, volume.removable)

        for (parent in VOLUME_PARENTS) {
            val children = File(parent).listFiles() ?: continue
            for (child in children) {
                if (child.name in NOT_VOLUMES || child.isHidden) continue
                add(child, removable = true)
            }
        }
        for (path in VOLUME_PATHS) add(File(path), removable = true)

        return roots.values.sortedByDescending { it.removable }
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
     * A directory an export can actually be written to, starting from the one the user picked.
     *
     * A stick is often mounted so that its root refuses writes while the app-specific folder on
     * the same volume accepts them. Falling back there keeps the file on the stick the user
     * chose — which is the whole point — instead of failing with nothing to show for it.
     * Null when nothing on that volume is writable.
     */
    fun writableTarget(context: Context, dir: File): File? {
        if (canWriteInto(dir)) return dir
        val picked = canonical(dir)
        for (appDir in context.getExternalFilesDirs(null).filterNotNull()) {
            val volume = volumeRoot(appDir) ?: continue
            if (!picked.startsWith(canonical(volume))) continue
            appDir.mkdirs()
            if (canWriteInto(appDir)) return appDir
        }
        return null
    }

    /**
     * `File.canWrite()` answers from the permission bits, which lie on a FAT stick and on a
     * platform-signed build. Creating a file is the only answer that counts.
     */
    private fun canWriteInto(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".mg4tasker-write-probe")
        return try {
            probe.createNewFile()
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.delete() }
        }
    }

    private fun canonical(dir: File): String =
        runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    /**
     * Volumes as the platform knows them. `StorageVolume.getDirectory()` is API 30; below that
     * the path is only reachable by reflection, which a platform-signed system app is exempt
     * from the hidden-API restrictions on. Any failure means "this source found nothing".
     */
    private fun storageManagerVolumes(context: Context): List<Root> = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        manager.storageVolumes.mapNotNull { volume ->
            volumeDirectory(volume)?.let { Root(it, volume.isRemovable) }
        }
    }.getOrDefault(emptyList())

    private fun volumeDirectory(volume: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            runCatching {
                StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            }.getOrNull()?.let(::File)
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
