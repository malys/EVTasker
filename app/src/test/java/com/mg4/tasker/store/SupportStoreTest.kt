package com.mg4.tasker.store

import androidx.test.core.app.ApplicationProvider
import com.mg4.hardware.catalog.ConditionType
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
// Plain Application: MG4TaskerApp runs a support check of its own on a background thread,
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
}
