package com.mg4.tasker.store

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStateTest {

    @Test
    fun `automation is enabled by default`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertTrue(AppState.isAutomationEnabled(context))
    }

    @Test
    fun `disabling automation persists across reads`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        AppState.setAutomationEnabled(context, false)

        assertFalse(AppState.isAutomationEnabled(context))
    }
}
