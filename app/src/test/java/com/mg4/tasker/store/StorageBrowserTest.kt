package com.mg4.tasker.store

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Directory listing for the in-app browser. Ordering is the whole point: the user scans a dialog
 * list with their eyes on a car screen.
 */
class StorageBrowserTest {

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
}
