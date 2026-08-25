package com.evsuite.tasker.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "near a place" condition is only ever as good as the cached fix behind it, and that
 * cache was empty on the car: nothing in the app had ever subscribed, so there was no "last
 * known" position for anyone to read. These pin the subscription that fills it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CarLocationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun grantLocation() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun fixAt(lat: Double, lon: Double, ageMs: Long = 0L) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat
        longitude = lon
        time = System.currentTimeMillis() - ageMs
    }

    @After
    fun tearDown() {
        CarLocation.stopTracking(context)
    }

    @Test
    fun trackingSubscribesToGpsAndSeedsTheCache() {
        grantLocation()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(fixAt(48.858370, 2.294481))

        CarLocation.startTracking(context)

        assertTrue("a subscription is what fills the cache", CarLocation.isTracking())
        val fix = CarLocation.lastKnown(context)!!
        assertEquals(48.858370, fix.latitude, 1e-6)
        assertEquals(2.294481, fix.longitude, 1e-6)
    }

    @Test
    fun aLaterUpdateReplacesTheSeededFix() {
        grantLocation()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(fixAt(48.0, 2.0, ageMs = 60_000))
        CarLocation.startTracking(context)

        shadowOf(manager).simulateLocation(fixAt(48.873800, 2.295000))

        val fix = CarLocation.lastKnown(context)!!
        assertEquals(48.873800, fix.latitude, 1e-6)
    }

    @Test
    fun withoutPermissionNothingIsSubscribedAndNoFixIsReported() {
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).simulateLocation(fixAt(48.0, 2.0))

        CarLocation.startTracking(context)

        assertFalse(CarLocation.isTracking())
        assertNull(CarLocation.lastKnown(context))
    }

    @Test
    fun aDisabledProviderLeavesTrackingInactiveSoTheWatchdogRetries() {
        grantLocation()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)

        CarLocation.startTracking(context)

        assertFalse(CarLocation.isTracking())
    }

    @Test
    fun stopTrackingClearsTheSubscription() {
        grantLocation()
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        CarLocation.startTracking(context)

        CarLocation.stopTracking(context)

        assertFalse(CarLocation.isTracking())
    }
}
