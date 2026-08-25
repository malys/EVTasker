package com.evsuite.tasker.vehicle

import android.content.Context
import com.evsuite.hardware.saic.SaicHub
import com.evsuite.hardware.saic.SaicNav
import com.evsuite.hardware.saic.SaicPhone
import com.evsuite.hardware.saic.SaicRadio
import com.evsuite.hardware.saic.SaicTts
import com.evsuite.hardware.saic.SaicWeather

/**
 * Binds the SAIC vendor services the catalogue depends on.
 *
 * One call site for every bind, all idempotent, so every entry point — the persistent
 * service, a manual test, the diagnostic — can ask without knowing which of them already
 * did. The binds are asynchronous: asking early is the point, since a rule cycle will not
 * wait for one.
 */
object VendorServices {

    fun connect(context: Context) {
        val appContext = context.applicationContext
        // Climate and charging share the hub; radio and telephony are their own services.
        SaicHub.connect(appContext)
        SaicRadio.connect(appContext)
        SaicPhone.connect(appContext)
        // The vehicle's own voice — what the "speak" action uses in preference to an Android
        // TTS engine, which this head unit does not have.
        SaicTts.connect(appContext)
        // The head unit's own applications rather than the vehicle SDK: the navigation
        // adapter answers the odometer, and the map stack's weather service answers the sky.
        // Separate binds, and a car without either simply leaves those two readings absent.
        SaicNav.connect(appContext)
        SaicWeather.connect(appContext)
    }
}
