package com.evsuite.tasker.store

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.evsuite.hardware.DriverContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CP-098: what EVTasker publishes as the driver, and the provider that serves it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DriverStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun provider() = Robolectric.buildContentProvider(DriverProvider::class.java).create().get()

    @Test fun `a set driver is read back normalised with its time`() {
        assertTrue(DriverStore.set(context, "  Alice ", nowMs = 1234L))
        assertEquals(DriverStore.Driver("Alice", 1234L), DriverStore.current(context))
    }

    @Test fun `an invalid name is refused and leaves the previous driver`() {
        DriverStore.set(context, "Alice", nowMs = 1L)
        assertFalse(DriverStore.set(context, "   "))
        assertEquals("Alice", DriverStore.current(context)?.name)
    }

    @Test fun `clear forgets the driver`() {
        DriverStore.set(context, "Alice")
        DriverStore.clear(context)
        assertNull(DriverStore.current(context))
    }

    @Test fun `the provider serves one row while a driver is set, none otherwise`() {
        val uri = Uri.parse(DriverContract.URI_CURRENT)
        provider().query(uri, null, null, null, null).use { assertEquals(0, it.count) }

        DriverStore.set(context, "Alice", nowMs = 42L)
        provider().query(uri, null, null, null, null).use {
            assertTrue(it.moveToFirst())
            assertEquals("Alice", it.getString(it.getColumnIndexOrThrow(DriverContract.COLUMN_NAME)))
            assertEquals(42L, it.getLong(it.getColumnIndexOrThrow(DriverContract.COLUMN_SET_AT_MS)))
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `the provider refuses writes`() {
        provider().delete(Uri.parse(DriverContract.URI_CURRENT), null, null)
    }
}
