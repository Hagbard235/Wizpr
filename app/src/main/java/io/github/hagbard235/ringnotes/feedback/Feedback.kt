package io.github.hagbard235.ringnotes.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Audible feedback on the phone: short status tones and the reply read aloud. */
class Feedback(private val context: Context) {
    enum class Tone(val tone: Int, val durationMs: Int) {
        SUCCESS(ToneGenerator.TONE_PROP_ACK, 300),
        QUESTION(ToneGenerator.TONE_PROP_PROMPT, 400),
        WARNING(ToneGenerator.TONE_PROP_BEEP2, 400),
        ERROR(ToneGenerator.TONE_PROP_NACK, 400),
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = mutableListOf<Triple<String, String?, Float>>()

    /** Uptime until which a status tone is still sounding; speech waits for it. */
    private var quietUntil = 0L

    fun tone(tone: Tone) {
        main.post {
            quietUntil = maxOf(quietUntil, SystemClock.uptimeMillis() + tone.durationMs + TONE_GAP_MS)
            try {
                val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
                generator.startTone(tone.tone, tone.durationMs)
                main.postDelayed({ generator.release() }, tone.durationMs + 200L)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Tone failed", e)
            }
        }
    }

    /**
     * Speak [text] once, after any status tone has finished (tone first, then a short
     * pause, then speech); the engine starts lazily on first use.
     */
    fun speak(text: String, languageTag: String?, rate: Float = 1f) {
        if (text.isBlank()) return
        main.post {
            val wait = quietUntil - SystemClock.uptimeMillis()
            if (wait > 0) {
                main.postDelayed({ speakNow(text, languageTag, rate) }, wait)
            } else {
                speakNow(text, languageTag, rate)
            }
        }
    }

    private fun speakNow(text: String, languageTag: String?, rate: Float) {
        run {
            if (ttsReady) {
                say(text, languageTag, rate)
                return@run
            }
            pending += Triple(text, languageTag, rate)
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    main.post {
                        ttsReady = status == TextToSpeech.SUCCESS
                        if (ttsReady) {
                            tts?.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                            pending.forEach { (t, l, r) -> say(t, l, r) }
                        } else {
                            Log.w(TAG, "TextToSpeech unavailable ($status)")
                        }
                        pending.clear()
                    }
                }
            }
        }
    }

    private fun say(text: String, languageTag: String?, rate: Float) {
        val engine = tts ?: return
        engine.setLanguage(languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault())
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "reply-${text.hashCode()}")
    }

    private companion object {
        const val TAG = "Feedback"
        const val VOLUME = 80
        /** Pause between the end of a tone and the start of speech. */
        const val TONE_GAP_MS = 250L
    }
}
