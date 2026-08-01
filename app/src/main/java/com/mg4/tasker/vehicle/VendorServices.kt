package com.mg4.tasker.vehicle

import android.content.Context
import com.mg4.hardware.saic.SaicHub
import com.mg4.hardware.saic.SaicPhone
import com.mg4.hardware.saic.SaicRadio
import com.mg4.hardware.saic.SaicTts

/**
 * Binds the SAIC vendor services the catalogue depends on.
 *
 * One call site for four binds, all idempotent, so every entry point — the persistent
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
    }
}
