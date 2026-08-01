package com.mg4.tasker.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

    /**
     * Speech, not media.
     *
     * On a head unit the audio policy routes by usage, and an utterance published as plain
     * media is mixed under whatever is playing instead of over it — audible in a silent car,
     * inaudible on the motorway, which reads as "the speak action does not work". ASSISTANT
     * plus a transient duck is the combination that ducks the source and comes through.
     */
    private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Why an utterance did not reach the driver. Reported verbatim in the run history. */
    enum class Failure {
        EMPTY_MESSAGE,
        /** The engine never called back — a wedged service, not a missing one. */
        INIT_TIMEOUT,
        NO_ENGINE,
        /** The engine took the call and rejected the utterance: worth one more try. */
        REFUSED,
        SPEAK_TIMEOUT,
    }

    /** @return null when the message was spoken, otherwise why it was not. */
    fun speak(context: Context, message: String): Failure? {
        if (message.isBlank()) return Failure.EMPTY_MESSAGE

        val appContext = context.applicationContext
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val engine = TextToSpeech(appContext) { result ->
            status = result
            ready.countDown()
        }

        val focus = requestFocus(appContext)
        try {
            if (!ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "TTS engine did not initialise within ${INIT_TIMEOUT_SECONDS}s")
                return Failure.INIT_TIMEOUT
            }
            if (status != TextToSpeech.SUCCESS) {
                AppLogger.w(TAG, "no TTS engine available (status=$status)")
                return Failure.NO_ENGINE
            }

            engine.setAudioAttributes(ATTRIBUTES)

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
                return Failure.REFUSED
            }
            return if (spoken.await(SPEAK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) null
            else Failure.SPEAK_TIMEOUT
        } finally {
            // stop() first: shutdown() alone can leave a half-spoken utterance queued.
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
            releaseFocus(appContext, focus)
        }
    }

    /**
     * Ducking focus, not exclusive: a rule message is a few seconds long, and pausing the
     * driver's music for it would be a bigger interruption than the message is worth.
     * Failing to obtain focus is not fatal — some head units grant none and still play.
     */
    private fun requestFocus(context: Context): AudioFocusRequest? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .build()
        val granted = runCatching { manager.requestAudioFocus(request) }.getOrNull()
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLogger.d(TAG, "audio focus not granted ($granted) — speaking anyway")
        }
        return request
    }

    private fun releaseFocus(context: Context, request: AudioFocusRequest?) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        request?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
    }
}
