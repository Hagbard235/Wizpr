package io.github.hagbard235.ringnotes.transcription

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hagbard235.ringnotes.R
import io.github.hagbard235.ringnotes.ai.AiForwarder
import io.github.hagbard235.ringnotes.ai.AiSettings
import io.github.hagbard235.ringnotes.ai.AiTarget
import io.github.hagbard235.ringnotes.feedback.Feedback
import io.github.hagbard235.ringnotes.notes.Note
import io.github.hagbard235.ringnotes.notes.NoteStore
import io.github.hagbard235.ringnotes.notes.ShareNoteActivity
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.recording.RecordingStore
import io.github.hagbard235.ringnotes.symcon.SymconJobs
import io.github.hagbard235.ringnotes.ui.MainActivity
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
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
 * Pipeline for finished recordings: transcribe on the device, then forward the
 * text to the configured AI target (Claude or a webhook) and keep the reply.
 *
 * Runs in the background while [io.github.hagbard235.ringnotes.RingService] is
 * up (it declares the microphone foreground-service type, which Android needs
 * before it lets the speech recognizer run without a visible activity). Work
 * that fails in the background is retried the next time the app is opened.
 * Transcription state is touched only on the main thread.
 */
class TranscriptionManager(
    private val context: Context,
    private val store: RecordingStore,
    private val aiSettings: AiSettings,
    private val symconJobs: SymconJobs,
    private val noteStore: NoteStore,
    private val feedback: Feedback,
    private val onRecordingsChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val transcriber = Transcriber(context)
    private val forwarder = AiForwarder()
    private val aiExecutor = Executors.newSingleThreadExecutor { Thread(it, "ai-forward") }
    private val queue = ArrayDeque<File>()
    private var running: File? = null
    private var foreground = false

    private val _status = MutableStateFlow<Map<String, TranscriptionStatus>>(emptyMap())
    val status: StateFlow<Map<String, TranscriptionStatus>> = _status.asStateFlow()

    private val _aiStatus = MutableStateFlow<Map<String, TranscriptionStatus>>(emptyMap())
    val aiStatus: StateFlow<Map<String, TranscriptionStatus>> = _aiStatus.asStateFlow()

    val isSupported: Boolean get() = transcriber.isSupported()

    /** Language of the phone, e.g. "de-DE". */
    var languageTag: String = Locale.getDefault().toLanguageTag()

    init {
        val channel = NotificationChannel(AI_CHANNEL, "KI-Antworten", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Transcribe (and then forward) one recording; also used for manual retries. */
    fun enqueue(wav: File) {
        main.post { add(wav) }
    }

    private val _lastTestEntry = MutableStateFlow<String?>(null)

    /** Path of the most recent typed test input, to show its result next to the input field. */
    val lastTestEntry: StateFlow<String?> = _lastTestEntry.asStateFlow()

    /**
     * Debug aid: send typed text as if it had been spoken, bypassing speech
     * recognition. Goes through the same forwarding (including answers to open
     * smart-home questions) and shows up as a "test-…" entry in the recordings.
     */
    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        main.post {
            val rec = store.createTextEntry(trimmed)
            _lastTestEntry.value = rec.file.path
            onRecordingsChanged()
            if (!keepAsNote(rec, trimmed)) forward(rec, trimmed)
        }
    }

    /**
     * "Notiz an mich selbst …" and similar: store the rest locally and forward
     * nothing. Returns true when [text] was such a note (also if saved earlier).
     */
    private fun keepAsNote(rec: Recording, text: String): Boolean {
        val body = noteStore.match(text) ?: return false
        if (noteStore.notes.value.any { it.recording == rec.name }) return true
        val note = noteStore.add(body, rec.name)
        val config = aiSettings.config.value
        if (config.statusTone) feedback.tone(Feedback.Tone.SUCCESS)
        if (config.speakReplies) feedback.speak("Notiz gespeichert", languageTag)
        notifyNote(note)
        return true
    }

    private fun notifyNote(note: Note) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val id = note.id.hashCode()
        val toKeep = PendingIntent.getActivity(
            context, id,
            Intent(context, ShareNoteActivity::class.java)
                .putExtra(ShareNoteActivity.EXTRA_NOTE_ID, note.id)
                .putExtra(ShareNoteActivity.EXTRA_NOTIFICATION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, AI_CHANNEL)
            .setSmallIcon(R.drawable.ic_ring)
            .setContentTitle("Notiz gespeichert")
            .setContentText(note.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(0, "An Keep", toKeep)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications not permitted; the note is still in the app.
        }
    }

    /** Forward an already transcribed recording to the AI target; for manual (re)sends. */
    fun sendToAi(recording: Recording) {
        val text = recording.transcript
        if (text.isNullOrBlank()) return
        forward(recording, text)
    }

    /** Called by the activity; when the app is opened, anything left over is picked up. */
    fun setForeground(isForeground: Boolean) {
        main.post {
            foreground = isForeground
            if (isForeground) catchUp()
            next()
        }
    }

    private fun catchUp() {
        val config = aiSettings.config.value
        for (rec in store.list()) {
            val path = rec.file.path
            if (rec.transcript == null) {
                if (_status.value[path] == null) add(rec.file)
            } else if (noteStore.match(rec.transcript) != null) {
                continue
            } else if (shouldAutoForward(rec) && rec.aiReply == null && !symconJobs.hasJob(rec.file) &&
                _aiStatus.value[path] == null && config.isReady
            ) {
                forward(rec, rec.transcript)
            }
        }
    }

    private fun add(wav: File) {
        if (!wav.exists() || wav == running || wav in queue) return
        queue.addLast(wav)
        setStatus(wav, TranscriptionStatus.Queued)
        next()
    }

    private fun next() {
        if (running != null) return
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
                    onRecordingsChanged()
                    store.find(wav)?.let { rec ->
                        if (result.text.isNotBlank() && !keepAsNote(rec, result.text) && shouldAutoForward(rec)) {
                            forward(rec, result.text)
                        }
                    }
                }
                is Transcriber.Result.Failure ->
                    // In the background the recognizer may refuse; leave it for the next app start.
                    setStatus(wav, if (foreground) TranscriptionStatus.Failed(result.message) else null)
            }
            next()
        }
    }

    private fun shouldAutoForward(rec: Recording): Boolean {
        val config = aiSettings.config.value
        if (!config.isReady || rec.createdAt < config.enabledSince) return false
        // A switching command must not fire long after it was spoken (e.g. when a recording
        // made in the background is only transcribed at the next app start): send those by hand.
        if (config.target == AiTarget.SYMCON) {
            return System.currentTimeMillis() - rec.createdAt <= SMART_HOME_MAX_AGE_MS
        }
        return true
    }

    private fun forward(rec: Recording, transcript: String) {
        val config = aiSettings.config.value
        if (!config.isReady) {
            setAiStatus(rec.file, TranscriptionStatus.Failed("KI-Weiterleitung ist nicht eingerichtet"))
            return
        }
        if (config.target == AiTarget.SYMCON) {
            symconJobs.submit(rec, transcript)
            return
        }
        if (_aiStatus.value[rec.file.path] == TranscriptionStatus.Running) return
        setAiStatus(rec.file, TranscriptionStatus.Running)
        aiExecutor.execute {
            val result = forwarder.forward(config, rec, transcript)
            main.post {
                when (result) {
                    is AiForwarder.Result.Success -> {
                        val reply = result.reply ?: "An Webhook gesendet."
                        store.saveAiReply(rec.file, reply)
                        setAiStatus(rec.file, null)
                        onRecordingsChanged()
                        if (config.target == AiTarget.CLAUDE && result.reply != null) notify(rec, result.reply)
                    }
                    is AiForwarder.Result.Failure -> setAiStatus(rec.file, TranscriptionStatus.Failed(result.message))
                }
            }
        }
    }

    private fun notify(rec: Recording, reply: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, AI_CHANNEL)
            .setSmallIcon(R.drawable.ic_ring)
            .setContentTitle("Claude · ${rec.name}")
            .setContentText(reply)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(rec.file.path.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the reply is still shown in the app.
        }
    }

    private fun setStatus(wav: File, status: TranscriptionStatus?) = _status.update {
        if (status == null) it - wav.path else it + (wav.path to status)
    }

    private fun setAiStatus(wav: File, status: TranscriptionStatus?) = _aiStatus.update {
        if (status == null) it - wav.path else it + (wav.path to status)
    }

    private companion object {
        const val AI_CHANNEL = "ai"
        const val SMART_HOME_MAX_AGE_MS = 2 * 60_000L
    }
}
