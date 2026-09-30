package io.github.hagbard235.ringnotes.transcription

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.hagbard235.ringnotes.core.RecognitionAudio
import io.github.hagbard235.ringnotes.core.WizprBle
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcribes a recorded WAV file with the phone's on-device speech recognizer
 * (on Pixels: Google's offline recognition), without using the microphone.
 *
 * Uses the Android 13 audio-source API: the PCM data is streamed through a pipe
 * into [SpeechRecognizer] as a segmented session, so long recordings with pauses
 * are transcribed completely. Must be used on the main thread.
 */
class Transcriber(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed interface Result {
        data class Success(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * @param online use Google's (usually more accurate) online recognizer; audio then
     *   leaves the device. Falls back to the on-device recognizer if that fails.
     * @param biasing words the recognizer should expect (trigger phrases, device names).
     */
    fun transcribe(
        wav: File,
        languageTag: String,
        online: Boolean = false,
        biasing: List<String> = emptyList(),
        onDone: (Result) -> Unit,
    ) {
        if (!isSupported()) {
            onDone(Result.Failure("On-Device-Spracherkennung braucht Android 13+ mit Google-Spracherkennung"))
            return
        }
        // Recordings can be long: never load them whole. The peak is measured in one
        // streaming pass off the main thread; the pump applies the gain chunk by chunk.
        Thread({
            val gain = try {
                RecognitionAudio.gainForPeak(peakOf(wav))
            } catch (e: Exception) {
                mainHandler.post { onDone(Result.Failure("Aufnahme nicht lesbar: ${e.message}")) }
                return@Thread
            }
            mainHandler.post { recognize(wav, gain, languageTag, online, biasing, onDone) }
        }, "transcribe-peak").start()
    }

    private fun recognize(
        wav: File,
        gain: Float,
        languageTag: String,
        online: Boolean,
        biasing: List<String>,
        onDone: (Result) -> Unit,
    ) {
        if (online && SpeechRecognizer.isRecognitionAvailable(context)) {
            start(wav, gain, languageTag, online = true, biasing) { result ->
                if (result is Result.Failure) {
                    Log.i(TAG, "Online recognition failed (${result.message}), falling back to on-device")
                    start(wav, gain, languageTag, online = false, biasing, onDone)
                } else {
                    onDone(result)
                }
            }
        } else {
            start(wav, gain, languageTag, online = false, biasing, onDone)
        }
    }

    /** Loudest sample of the WAV payload, read in small chunks. */
    private fun peakOf(wav: File): Int {
        var peak = 0
        forEachChunk(wav) { chunk, count ->
            for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(chunk[i].toInt()))
        }
        return peak
    }

    /** Streams the 16-bit little-endian PCM after the 44-byte header in chunks of up to 8192 samples. */
    private fun forEachChunk(wav: File, block: (ShortArray, Int) -> Unit) {
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        val samples = ShortArray(CHUNK_SAMPLES)
        wav.inputStream().buffered().use { input ->
            input.skip(WAV_HEADER_BYTES)
            while (true) {
                val n = input.readNBytesCompat(bytes)
                if (n <= 1) break
                val count = n / 2
                ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, count)
                block(samples, count)
                if (n < bytes.size) break
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = read(buffer, total, buffer.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun start(wav: File, gain: Float, languageTag: String, online: Boolean, biasing: List<String>, onDone: (Result) -> Unit) {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        val recognizer = if (online) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, WizprBle.SAMPLE_RATE_HZ)
            // Keep recognizing across pauses until the audio source ends.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        if (biasing.isNotEmpty()) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasing.distinct().take(MAX_BIASING)))
        }

        val segments = mutableListOf<String>()
        var finished = false
        val handler = Handler(Looper.getMainLooper())
        var timeout: Runnable? = null

        fun finish(result: Result) {
            if (finished) return
            finished = true
            timeout?.let(handler::removeCallbacks)
            recognizer.destroy()
            readSide.closeQuietly()
            onDone(result)
        }

        fun text() = segments.joinToString(" ").trim()

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onSegmentResults(segmentResults: Bundle) {
                bestResult(segmentResults)?.let(segments::add)
            }

            override fun onEndOfSegmentedSession() = finish(Result.Success(text()))

            override fun onResults(results: Bundle) {
                bestResult(results)?.let(segments::add)
                finish(Result.Success(text()))
            }

            override fun onError(error: Int) {
                when {
                    // Silence at the end of a recording is not a failure.
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        finish(Result.Success(text()))
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                        recognizer.triggerModelDownload(intent)
                        finish(Result.Failure("Sprachpaket für $languageTag fehlt – Download angestoßen, danach erneut versuchen"))
                    }
                    else -> finish(Result.Failure(describeError(error)))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        // Safety net in case the recognizer never reports back: audio length plus a minute.
        val audioMs = (wav.length() - WAV_HEADER_BYTES).coerceAtLeast(0) / 2 * 1000 / WizprBle.SAMPLE_RATE_HZ +
            RecognitionAudio.LEAD_SILENCE_MS + RecognitionAudio.TAIL_SILENCE_MS
        timeout = Runnable {
            finish(if (segments.isEmpty()) Result.Failure("Spracherkennung hat nicht geantwortet") else Result.Success(text()))
        }.also { handler.postDelayed(it, audioMs + 60_000) }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            writeSide.closeQuietly()
            finish(Result.Failure("Spracherkennung konnte nicht starten: ${e.message}"))
            return
        }
        pumpPcm(wav, gain, writeSide)
    }

    /**
     * Streams lead silence, the recording with [gain] applied and tail silence into the
     * pipe on a background thread, chunk by chunk (constant memory for any length).
     */
    private fun pumpPcm(wav: File, gain: Float, writeSide: ParcelFileDescriptor) {
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    val bytes = ByteBuffer.allocate(CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                    fun write(samples: ShortArray, count: Int) {
                        bytes.clear()
                        bytes.asShortBuffer().put(samples, 0, count)
                        out.write(bytes.array(), 0, count * 2)
                    }
                    val silence = ShortArray(CHUNK_SAMPLES)
                    fun writeSilence(total: Int) {
                        var left = total
                        while (left > 0) {
                            val n = minOf(left, CHUNK_SAMPLES)
                            write(silence, n)
                            left -= n
                        }
                    }
                    writeSilence(RecognitionAudio.leadSamples())
                    forEachChunk(wav) { chunk, count ->
                        RecognitionAudio.applyGain(chunk, count, gain)
                        write(chunk, count)
                    }
                    writeSilence(RecognitionAudio.tailSamples())
                }
            } catch (e: IOException) {
                // The recognizer closed its end early (finished or failed); nothing left to do.
                Log.d(TAG, "PCM pipe closed: ${e.message}")
            }
        }, "transcribe-pump").start()
    }

    private fun bestResult(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt (wird von der Spracherkennung verlangt)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Spracherkennung ist gerade belegt"
        SpeechRecognizer.ERROR_AUDIO -> "Audiofehler bei der Spracherkennung"
        SpeechRecognizer.ERROR_CLIENT -> "Spracherkennung abgebrochen"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Spracherkennungsdienst nicht erreichbar"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Zu viele Anfragen an die Spracherkennung"
        else -> "Spracherkennung fehlgeschlagen (Fehler $error)"
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "Transcriber"
        const val WAV_HEADER_BYTES = 44L
        const val MAX_BIASING = 100
        const val CHUNK_SAMPLES = 8192
    }
}
