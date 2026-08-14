package com.evsuite.tasker.store

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateLaunchStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("ev_tasker_ui_version", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `fresh install keeps normal landing screen`() {
        assertFalse(UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 10))
    }

    @Test
    fun `same version keeps normal landing screen`() {
        UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 10)

        assertFalse(UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 10))
    }

    @Test
    fun `first launch after version change opens diagnostic once`() {
        UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 10)

        assertTrue(UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 11))
        assertFalse(UpdateLaunchStore.shouldOpenDiagnostic(context, currentVersion = 11))
    }
}
