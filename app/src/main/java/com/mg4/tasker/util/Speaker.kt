package com.mg4.tasker.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mg4.hardware.AppLogger
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks a rule message through the platform TTS engine.
 *
 * The engine is created per utterance and released once it has spoken: rules fire a few
 * times per drive at most, and a resident engine would hold an audio focus client alive
 * for the whole ignition cycle for nothing.
 *
 * ⚠️ Blocks until the engine has spoken (or the timeout expires); call off the main
 * thread — which is where the rule engine already runs.
 */
object Speaker {

    private const val TAG = "Speak"
    private const val INIT_TIMEOUT_SECONDS = 5L

    /** Long enough for a spoken sentence, short enough not to hold the cycle open. */
    private const val SPEAK_TIMEOUT_SECONDS = 30L

    private const val UTTERANCE_ID = "mg4-tasker-rule"

    /** @return false when no engine is available, or it never finished speaking. */
    fun speak(context: Context, message: String): Boolean {
        if (message.isBlank()) return false

        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val engine = TextToSpeech(context.applicationContext) { result ->
            status = result
            ready.countDown()
        }

        try {
            if (!ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
                AppLogger.w(TAG, "no TTS engine available (status=$status)")
                return false
            }

            // Follow the app's display language: a French message read by an English voice
            // is unintelligible. LANG_MISSING_DATA leaves the engine default in place.
            val locale = Locale.getDefault()
            if (engine.setLanguage(locale) == TextToSpeech.LANG_NOT_SUPPORTED) {
                AppLogger.w(TAG, "locale $locale not supported, using the engine default")
            }

            val spoken = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = spoken.countDown()
                @Deprecated("Kept for API < 33; the platform calls this overload.")
                override fun onError(utteranceId: String?) = spoken.countDown()
            })

            if (engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) != TextToSpeech.SUCCESS) {
                AppLogger.w(TAG, "engine refused the utterance")
                return false
            }
            return spoken.await(SPEAK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            // stop() first: shutdown() alone can leave a half-spoken utterance queued.
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
