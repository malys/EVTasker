package com.mg4.tasker.ui

import android.app.Activity
import com.mg4.tasker.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProfileAutomationWarningActivityTest {

    @Test
    fun `cancel closes without confirming`() {
        val activity = Robolectric.buildActivity(ProfileAutomationWarningActivity::class.java)
            .setup().get()

        activity.findViewById<android.view.View>(R.id.warningCancel).performClick()

        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals(true, activity.isFinishing)
    }

    @Test
    fun `confirm returns success`() {
        val activity = Robolectric.buildActivity(ProfileAutomationWarningActivity::class.java)
            .setup().get()

        activity.findViewById<android.view.View>(R.id.warningConfirm).performClick()

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(true, activity.isFinishing)
    }
}
