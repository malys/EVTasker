package com.mg4.tasker.store

import com.mg4.hardware.catalog.ActionType
import com.mg4.hardware.catalog.ConditionType
import com.mg4.tasker.model.Action
import com.mg4.tasker.model.Condition
import com.mg4.tasker.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The file layer around [RuleTransfer]: what lands on the stick, and what a user's pick reads
 * back as. Storage discovery is [StorageBrowser]'s and is covered separately.
 */
class RuleFilesTest {

    // Qualified: an `org.junit.Rule` import would collide with the model's Rule.
    @get:org.junit.Rule
    val folder = TemporaryFolder()

    private val rules = listOf(
        Rule(
            id = "r1",
            name = "Cold morning",
            conditions = listOf(Condition(ConditionType.OUTSIDE_TEMP)),
            actions = listOf(Action(ActionType.SET_STEERING_HEAT, number = 2))
        )
    )

    @Test
    fun `export writes a file the import path reads back`() {
        val file = RuleFiles.export(rules, folder.root)

        assertNotNull(file)
        assertEquals(RuleTransfer.Result.Ok(rules), RuleFiles.read(file!!))
    }

    @Test
    fun `exported name is timestamped so a second export keeps the first`() {
        val file = RuleFiles.export(rules, folder.root)!!

        assertTrue(
            file.name,
            file.name.matches(Regex("""mg4tasker-rules-\d{8}-\d{6}\.json"""))
        )
    }

    @Test
    fun `export leaves no temp file behind`() {
        RuleFiles.export(rules, folder.root)

        assertEquals(emptyList<String>(), folder.root.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `export into a missing directory fails instead of throwing`() {
        val missing = File(folder.root, "not-there")

        assertEquals(null, RuleFiles.export(rules, missing))
    }

    @Test
    fun `a file the user picked but cannot be read is malformed, not silence`() {
        val missing = File(folder.root, "gone.json")

        assertEquals(
            RuleTransfer.Result.Invalid(RuleTransfer.Reason.MALFORMED),
            RuleFiles.read(missing)
        )
    }

    @Test
    fun `an empty file is malformed`() {
        val empty = folder.newFile("empty.json")

        assertEquals(
            RuleTransfer.Result.Invalid(RuleTransfer.Reason.MALFORMED),
            RuleFiles.read(empty)
        )
    }

    @Test
    fun `an oversized file is refused without being read into memory`() {
        val huge = folder.newFile("huge.json")
        huge.writeText("x".repeat((RuleTransfer.MAX_BYTES + 1).toInt()))

        assertEquals(
            RuleTransfer.Result.Invalid(RuleTransfer.Reason.MALFORMED),
            RuleFiles.read(huge)
        )
    }

    @Test
    fun `somebody else's json reads as not a rules file`() {
        val other = folder.newFile("other.json")
        other.writeText("""{"some":"other file"}""")

        assertEquals(RuleTransfer.Result.NotARulesFile, RuleFiles.read(other))
    }
}
