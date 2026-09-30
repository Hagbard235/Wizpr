package io.github.hagbard235.ringnotes.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.hagbard235.ringnotes.core.StreamingWavWriter
import io.github.hagbard235.ringnotes.core.WizprBle
import io.github.hagbard235.ringnotes.recording.RecordingStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Push-to-talk with the phone's own microphone. Writes 16 kHz mono PCM, the same
 * format the ring delivers, into a "phone-…" WAV that then takes the same path as
 * a ring recording (transcription, forwarding). Start and stop on the main thread.
 */
class PhoneRecorder(
    private val context: Context,
    private val store: RecordingStore,
    private val onSaved: (File) -> Unit,
    /** Called when the recording was pure silence, which is what Android delivers to a blocked background app. */
    private val onSilent: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var discard = false

    /** The WAV currently being written, if any. */
    @Volatile var currentFile: File? = null
        private set

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    /** Gain applied while recording (the recognition source has no automatic gain). */
    private val _gain = MutableStateFlow(prefs.getFloat(KEY_GAIN, DEFAULT_GAIN))
    val gain: StateFlow<Float> = _gain.asStateFlow()

    /** Use the phone's automatic gain control and noise suppression, if it has them. */
    private val _autoLevel = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LEVEL, true))
    val autoLevel: StateFlow<Boolean> = _autoLevel.asStateFlow()

    fun setGain(value: Float) {
        val g = value.coerceIn(MIN_GAIN, MAX_GAIN)
        prefs.edit().putFloat(KEY_GAIN, g).apply()
        _gain.value = g
    }

    fun setAutoLevel(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LEVEL, on).apply()
        _autoLevel.value = on
    }

    /** Whether this phone offers automatic gain control for recordings. */
    val autoLevelAvailable: Boolean get() = AutomaticGainControl.isAvailable()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Returns false when the microphone could not be started (permission, busy, background). */
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        val rate = WizprBle.SAMPLE_RATE_HZ
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 2),
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord unavailable", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val effects = if (_autoLevel.value) attachLevelEffects(record.audioSessionId) else emptyList()
        val file = store.newFile(prefix = "phone")
        val writer = try {
            StreamingWavWriter(file, gain = _gain.value)
        } catch (e: Exception) {
            effects.forEach { it.release() }
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            effects.forEach { it.release() }
            record.release()
            writer.close()
            file.delete()
            return false
        }
        running = true
        discard = false
        currentFile = file
        _active.value = true
        Thread({
            val buffer = ShortArray(rate / 10)
            var peak = 0
            try {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        writer.append(buffer.copyOf(n))
                        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buffer[i].toInt()))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recording failed", e)
            } finally {
                runCatching { record.stop() }
                effects.forEach { runCatching { it.release() } }
                record.release()
                runCatching { writer.close() }
                main.post { finish(file, writer.durationMs, peak) }
            }
        }, "phone-mic").start()
        return true
    }

    fun stop() {
        running = false
    }

    /** Stop and throw the recording away (e.g. it was only a short volume-down press). */
    fun cancel() {
        discard = true
        running = false
    }

    /** Automatic gain control and noise suppression, where the phone provides them. */
    private fun attachLevelEffects(sessionId: Int): List<AudioEffect> = buildList {
        if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
        }
    }

    private fun finish(file: File, durationMs: Long, peak: Int) {
        _active.value = false
        currentFile = null
        when {
            discard || durationMs < MIN_DURATION_MS -> file.delete()
            peak < SILENCE_PEAK -> {
                file.delete()
                onSilent()
            }
            else -> onSaved(file)
        }
    }

    companion object {
        private const val TAG = "PhoneRecorder"
        private const val KEY_GAIN = "gain"
        private const val KEY_AUTO_LEVEL = "autoLevel"
        const val MIN_GAIN = 1f
        const val MAX_GAIN = 8f
        const val DEFAULT_GAIN = 3f
        const val MIN_DURATION_MS = 400L
        const val SILENCE_PEAK = 64
    }
}
