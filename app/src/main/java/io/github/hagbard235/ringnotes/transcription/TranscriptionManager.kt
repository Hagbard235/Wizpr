package io.github.hagbard235.ringnotes.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.hagbard235.ringnotes.recording.RecordingStore
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface TranscriptionStatus {
    data object Queued : TranscriptionStatus
    data object Running : TranscriptionStatus
    data class Failed(val message: String) : TranscriptionStatus
}

/**
 * Serial queue that transcribes recordings one at a time and stores the text
 * next to the WAV file. New recordings are transcribed automatically while the
 * app is in the foreground; the recognizer is not usable from the background,
 * so recordings made with the screen off are picked up on the next app start.
 */
class TranscriptionManager(
    private val context: Context,
    private val store: RecordingStore,
    private val onTranscriptSaved: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val transcriber = Transcriber(context)
    private val queue = ArrayDeque<File>()
    private var running: File? = null
    private var foreground = false

    private val _status = MutableStateFlow<Map<String, TranscriptionStatus>>(emptyMap())
    val status: StateFlow<Map<String, TranscriptionStatus>> = _status.asStateFlow()

    val isSupported: Boolean get() = transcriber.isSupported()

    /** Language of the phone, e.g. "de-DE". */
    var languageTag: String = Locale.getDefault().toLanguageTag()

    fun enqueue(wav: File) = main.post { add(wav) }

    fun setForeground(isForeground: Boolean) = main.post {
        foreground = isForeground
        if (isForeground) {
            store.list().filter { it.transcript == null && _status.value[it.file.path] == null }
                .forEach { add(it.file) }
        }
        next()
    }

    private fun add(wav: File) {
        if (!wav.exists() || wav == running || wav in queue) return
        queue.addLast(wav)
        setStatus(wav, TranscriptionStatus.Queued)
        next()
    }

    private fun next() {
        if (running != null || !foreground) return
        val wav = queue.pollFirst() ?: return
        if (!wav.exists()) {
            setStatus(wav, null)
            next()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus(wav, TranscriptionStatus.Failed("Mikrofon-Berechtigung fehlt (wird von der Spracherkennung verlangt)"))
            next()
            return
        }
        running = wav
        setStatus(wav, TranscriptionStatus.Running)
        transcriber.transcribe(wav, languageTag) { result ->
            running = null
            when (result) {
                is Transcriber.Result.Success -> {
                    store.saveTranscript(wav, result.text)
                    setStatus(wav, null)
                    onTranscriptSaved()
                }
                is Transcriber.Result.Failure -> setStatus(wav, TranscriptionStatus.Failed(result.message))
            }
            next()
        }
    }

    private fun setStatus(wav: File, status: TranscriptionStatus?) = _status.update {
        if (status == null) it - wav.path else it + (wav.path to status)
    }
}
