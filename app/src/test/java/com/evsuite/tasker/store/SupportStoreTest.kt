package com.evsuite.tasker.store

import androidx.test.core.app.ApplicationProvider
import com.evsuite.hardware.catalog.ConditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The record survives app updates, and an update can delete catalogue entries — the four
 * `STAR_*_PRESS` conditions became one `PHYSICAL_BUTTON`. What is read back must name only
 * entries this build still defines, or the Diagnostic screen counts conditions the editor
 * can no longer offer.
 */
// Plain Application: EVTaskerApp runs a support check of its own on a background thread,
// which would race this test for the same record.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SupportStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `names removed from the catalogue are dropped on read`() {
        val alive = ConditionType.PHYSICAL_BUTTON.name
        SupportStore.save(context, "SWI68", setOf(alive, "STAR_SHORT_PRESS"), emptySet())

        val read = SupportStore.supportedConditions(context)!!
        assertTrue(alive in read)
        assertFalse("STAR_SHORT_PRESS" in read)
        assertEquals(1, read.size)
    }

    @Test fun `never checked stays null so the caller does not filter`() {
        assertNull(SupportStore.supportedConditions(context))
    }

    /**
     * The mirror of the test above. A set written by an older build cannot name an entry
     * that build did not have, so a catalogue entry added by an update would be read as
     * unsupported and vanish from the pickers on every car that had ever run a check.
     */
    @Test fun `a record from an earlier version reads as no record`() {
        SupportStore.save(context, "SWI68", setOf(ConditionType.PHYSICAL_BUTTON.name), setOf("SPEAK_TEXT"))
        // What an app update does: same stored sets, a version they know nothing about.
        context.getSharedPreferences("ev_tasker_support", android.content.Context.MODE_PRIVATE)
            .edit().putInt("checked_version", -1).commit()

        assertNull(SupportStore.supportedConditions(context))
        assertNull(SupportStore.supportedActions(context))
        assertTrue(SupportStore.diagnosticBlockedActions(context).isEmpty())
    }
}
