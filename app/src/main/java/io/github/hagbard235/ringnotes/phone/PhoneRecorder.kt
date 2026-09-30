package io.github.hagbard235.ringnotes.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        val file = store.newFile(prefix = "phone")
        val writer = try {
            StreamingWavWriter(file)
        } catch (e: Exception) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
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

    private companion object {
        const val TAG = "PhoneRecorder"
        const val MIN_DURATION_MS = 400L
        const val SILENCE_PEAK = 64
    }
}
