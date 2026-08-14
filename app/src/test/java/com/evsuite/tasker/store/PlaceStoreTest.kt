package com.evsuite.tasker.store

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved places are what makes the location controls answerable at the wheel, so what
 * goes in has to come back out — under the name given, in an order a driver can scan.
 */
// Plain Application: EVTaskerApp does background work of its own at startup, which has no
// business running during a preference test.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlaceStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `a saved place comes back under its name`() {
        assertTrue(PlaceStore.save(context, "Maison", "48.858370,2.294481"))

        assertEquals(listOf(PlaceStore.Place("Maison", "48.858370,2.294481")), PlaceStore.all(context))
    }

    @Test fun `the same name replaces rather than duplicates`() {
        PlaceStore.save(context, "Maison", "48.858370,2.294481")
        PlaceStore.save(context, "Maison", "45.764043,4.835659")

        assertEquals(listOf(PlaceStore.Place("Maison", "45.764043,4.835659")), PlaceStore.all(context))
    }

    @Test fun `places read back alphabetically whatever their casing`() {
        PlaceStore.save(context, "travail", "48.8,2.3")
        PlaceStore.save(context, "Aéroport", "49.0,2.5")
        PlaceStore.save(context, "Maison", "45.7,4.8")

        assertEquals(listOf("Aéroport", "Maison", "travail"), PlaceStore.all(context).map { it.name })
    }

    @Test fun `a nameless place or an empty point is refused`() {
        assertFalse(PlaceStore.save(context, "   ", "48.8,2.3"))
        assertFalse(PlaceStore.save(context, "Maison", "  "))

        assertTrue(PlaceStore.all(context).isEmpty())
    }

    @Test fun `surrounding blanks are not part of the record`() {
        PlaceStore.save(context, "  Maison  ", "  48.8,2.3  ")

        assertEquals(listOf(PlaceStore.Place("Maison", "48.8,2.3")), PlaceStore.all(context))
    }

    @Test fun `a removed place is gone`() {
        PlaceStore.save(context, "Maison", "48.8,2.3")
        PlaceStore.remove(context, "Maison")

        assertTrue(PlaceStore.all(context).isEmpty())
    }
}
