package com.evsuite.tasker.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.io.File

/**
 * Directory listing for the in-app browser. Ordering is the whole point: the user scans a dialog
 * list with their eyes on a car screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageBrowserTest {

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val folder = TemporaryFolder()

    private fun names(extension: String? = null) =
        StorageBrowser.children(folder.root, extension).map { it.name }

    @Test
    fun `directories come before files, each alphabetically`() {
        folder.newFile("zebra.json")
        folder.newFile("apple.json")
        folder.newFolder("photos")
        folder.newFolder("Backups")

        assertEquals(listOf("Backups", "photos", "apple.json", "zebra.json"), names())
    }

    @Test
    fun `the extension filter keeps directories reachable`() {
        folder.newFile("rules.json")
        folder.newFile("notes.txt")
        folder.newFolder("backup")

        assertEquals(listOf("backup", "rules.json"), names("json"))
    }

    @Test
    fun `the extension match ignores case`() {
        folder.newFile("RULES.JSON")

        assertEquals(listOf("RULES.JSON"), names("json"))
    }

    @Test
    fun `hidden entries are not offered`() {
        folder.newFile(".hidden.json")
        folder.newFile("visible.json")

        assertEquals(listOf("visible.json"), names("json"))
    }

    @Test
    fun `a missing directory lists nothing rather than failing`() {
        assertEquals(emptyList<File>(), StorageBrowser.children(File(folder.root, "not-there")))
    }

    @Test
    fun `a file passed where a directory was expected lists nothing`() {
        val file = folder.newFile("rules.json")

        assertEquals(emptyList<File>(), StorageBrowser.children(file))
    }

    @Test
    fun `a writable directory is its own export target`() {
        assertEquals(folder.root, StorageBrowser.writableTarget(context(), folder.root))
    }

    @Test
    fun `a probe file is never left behind by the writability check`() {
        StorageBrowser.writableTarget(context(), folder.root)

        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test
    fun `a directory that does not exist has no writable target`() {
        val missing = File(folder.root, "not-there")

        assertEquals(null, StorageBrowser.writableTarget(context(), missing))
    }

    @Test
    fun `every offered root can actually be listed`() {
        for (root in StorageBrowser.roots(context())) {
            assertNotNull("offered a root that cannot be listed: ${root.dir}", root.dir.listFiles())
        }
    }

    @Test
    fun `roots are deduplicated and removable ones come first`() {
        val roots = StorageBrowser.roots(context())

        assertEquals(roots.map { it.dir.absolutePath }.distinct().size, roots.size)
        assertEquals(roots.sortedByDescending { it.removable }, roots)
    }
}
