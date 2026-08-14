package com.evsuite.tasker.store

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
    fun `automation is disabled by default`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(AppState.isAutomationEnabled(context))
    }

    @Test
    fun `automation cannot be enabled without current consent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        AppState.setAutomationEnabled(context, true)

        assertFalse(AppState.isAutomationEnabled(context))
    }

    @Test
    fun `accepted consent permits automation and persists`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        AppState.acceptCurrentWriteConsent(context)
        AppState.setAutomationEnabled(context, true)

        assertTrue(AppState.hasCurrentWriteConsent(context))
        assertTrue(AppState.isAutomationEnabled(context))
    }

    @Test
    fun `expert rule branches are hidden by default and persist when enabled`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(AppState.areExpertRulesEnabled(context))
        AppState.setExpertRulesEnabled(context, true)
        assertTrue(AppState.areExpertRulesEnabled(context))
    }
}
