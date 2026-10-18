package com.evsuite.tasker.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PowerCycleStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("ev_tasker_power_cycle", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `nothing recorded prints none`() {
        val text = PowerCycleStore.format(null, null, 0L, null)
        assertTrue(text, text.startsWith("previous: off none, last heartbeat none, boot "))
        assertTrue(text, text.endsWith(", resumed none"))
    }

    @Test
    fun `a heartbeat gap closes the previous cycle with its off time`() {
        val t0 = 1_792_000_000_000L
        PowerCycleStore.heartbeat(context, t0)
        PowerCycleStore.ignitionOff(context, t0 + 10_000)
        for (t in 30_000L..270_000L step 30_000L) PowerCycleStore.heartbeat(context, t0 + t)
        PowerCycleStore.heartbeat(context, t0 + 280_000)   // still running after the off
        assertTrue(PowerCycleStore.describe(context).startsWith("previous: off none"))

        PowerCycleStore.heartbeat(context, t0 + 3_600_000)   // woke an hour later
        val text = PowerCycleStore.describe(context)
        assertTrue(text, "(+270 s)" in text)

        // The next cycle had no off of its own.
        PowerCycleStore.heartbeat(context, t0 + 7_200_000)
        assertTrue(PowerCycleStore.describe(context).startsWith("previous: off none"))
    }

    @Test
    fun `a wake on the factory clock is not a cycle`() {
        val t0 = 1_792_000_000_000L
        PowerCycleStore.heartbeat(context, t0)
        PowerCycleStore.ignitionOff(context, t0 + 10_000)
        PowerCycleStore.heartbeat(context, t0 + 30_000)
        PowerCycleStore.heartbeat(context, 1_546_300_836_000L)   // 36 s into 2019-01-01
        PowerCycleStore.heartbeat(context, t0 + 3_600_000)

        val text = PowerCycleStore.describe(context)
        assertTrue(text, "(+20 s)" in text && "2019" !in text)
    }
}
