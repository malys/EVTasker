package com.evsuite.tasker.util

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The foreground service types are a permission question, not a manifest one.
 *
 * Since API 34 the platform checks every type the service claims against what the app holds
 * at that instant, and refuses the whole start when one is unbacked — `connectedDevice` rests
 * on BLUETOOTH_CONNECT, `location` on either position grade. Claiming them unconditionally is
 * what killed the vehicle service, and with it every rule, on a car that had granted neither.
 */
@RunWith(RobolectricTestRunner::class)
class NotifierTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing is claimed while both permissions are denied`() {
        shadowOf(app).denyPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
            Notifier.foregroundServiceTypes(app)
        )
    }

    @Test
    fun `location type is left out while position is denied`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            Notifier.foregroundServiceTypes(app)
        )
    }

    @Test
    fun `connected-device type is left out while Bluetooth is denied`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            Notifier.foregroundServiceTypes(app)
        )
    }

    @Test
    fun `both types are claimed once both permissions are granted`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            Notifier.foregroundServiceTypes(app)
        )
    }

    @Test
    fun `an approximate position grant is enough to claim the location type`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            Notifier.foregroundServiceTypes(app)
        )
    }
}
