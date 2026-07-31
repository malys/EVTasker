package com.mg4.tasker.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the fix for the bug that made every release build read back zero rules.
 *
 * `object : TypeToken<List<Rule>>() {}` needs its generic signature to survive R8, and on the
 * car it did not: R8 dropped the anonymous subclass, Gson threw "TypeToken must be created
 * with a type argument", and a saved rule vanished from the list. Unit tests run unminified,
 * so no behavioural test can catch a regression here — the source pattern itself is what has
 * to stay gone.
 */
class ShrinkerSafeGsonTest {

    private val storeDir = File("src/main/java/com/mg4/tasker/store")

    @Test
    fun `no persistence code deserialises through an anonymous TypeToken`() {
        val offenders = storeDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.readText().contains("object : TypeToken") }
            .map { it.name }
            .toList()

        assertEquals(
            "Use Array<T>::class.java instead — an anonymous TypeToken does not survive R8",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `the rules wire format keeps its field names under the shrinker`() {
        val rules = File("proguard-rules.pro").readText()

        assertEquals(
            "RuleTransfer's DTO field names are the JSON keys written to the USB stick; " +
                "without a keep rule R8 renames them and the export becomes unreadable",
            true,
            rules.contains("com.mg4.tasker.store.RuleTransfer\$*")
        )
    }
}
