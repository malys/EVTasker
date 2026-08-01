package com.mg4.tasker.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech

/**
 * Which text-to-speech engines this app can see on the head unit.
 *
 * One source for three answers that must agree: whether the Diagnostic tab reports a speech
 * engine, whether the rule editor offers the "speak" action, and what the support check
 * stores. They disagreed before — the editor offered an action the diagnostic had just
 * declared impossible — and that mismatch is the whole reason this lives in one place.
 *
 * Queried, never instantiated: creating a [TextToSpeech] to test it takes audio focus, and
 * on a car that means talking over whatever is playing.
 */
object SpeechEngines {

    /**
     * Engine packages, most reliable source first.
     *
     * The service query alone under-reports. On API 30+ it only returns packages made
     * visible to us, and a head unit whose engine ships outside that visibility then reads
     * as a car with no voice at all — which the driver disproves every time the vehicle
     * speaks. The default-engine setting is readable whatever the package visibility rules
     * say, so it settles the question the query got wrong.
     */
    fun list(context: Context): List<String> {
        val services = context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
        if (services.isNotEmpty()) return services
        // Settings.Secure.TTS_DEFAULT_SYNTH, spelled out: the constant is deprecated, the row
        // it names is still the one the platform reads when it picks an engine.
        val default = Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        return listOfNotNull(default?.takeIf { it.isNotBlank() })
    }

    fun any(context: Context): Boolean = list(context).isNotEmpty()
}
