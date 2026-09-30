package io.github.hagbard235.ringnotes.symcon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.hagbard235.ringnotes.R
import io.github.hagbard235.ringnotes.ai.AiConfig
import io.github.hagbard235.ringnotes.ai.AiSettings
import io.github.hagbard235.ringnotes.core.symcon.Clarification
import io.github.hagbard235.ringnotes.core.symcon.ReplyTo
import io.github.hagbard235.ringnotes.core.symcon.SymconAction
import io.github.hagbard235.ringnotes.core.symcon.SymconProtocol
import io.github.hagbard235.ringnotes.core.symcon.SymconRequest
import io.github.hagbard235.ringnotes.core.symcon.SymconResponse
import io.github.hagbard235.ringnotes.core.symcon.SymconResponse.Companion.Parsed
import io.github.hagbard235.ringnotes.core.symcon.matchOption
import io.github.hagbard235.ringnotes.feedback.Feedback
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.ui.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the UI shows for a recording's latest Symcon request. */
data class SymconView(
    val status: String?,
    val message: String?,
    val actions: List<SymconAction>,
    /** Only set while the question is open (not answered, not expired). */
    val clarification: Clarification?,
    /** Local note, e.g. a transport error or "Ergebnis noch offen". */
    val note: String?,
    val busy: Boolean,
    val canRefresh: Boolean,
    /** The smart home waits for an answer; the next recording is sent as that answer. */
    val awaitingAnswer: Boolean = false,
    /** Sent as a recognition test (dryRun): nothing was switched. */
    val dryRun: Boolean = false,
    /** Earlier turns of this conversation, oldest first. */
    val thread: List<ThreadEntry> = emptyList(),
    /** What was sent in this turn. */
    val transcript: String = "",
)

data class ThreadEntry(val request: String, val reply: String)

/**
 * Runs requests against the IP-Symcon AI hook (contract version 1): send with a
 * stable requestId, retry the identical request after transport errors, poll
 * pending results, handle clarifications and give audible feedback once per result.
 *
 * Each recording keeps its request history in `<name>.symcon.json` next to the WAV.
 * All job state lives on one background thread.
 */
class SymconJobs(
    private val context: Context,
    private val recordingsDir: File,
    private val settings: AiSettings,
    private val feedback: Feedback,
) {
    private class Job(
        val wavPath: String,
        val requestId: String,
        val body: String,
        val submittedAt: Long,
        var pollUrl: String? = null,
        var rawResponse: String? = null,
        var note: String? = null,
        var spokenKey: String? = null,
        var answered: Boolean = false,
        /** requestId of the previous request in the same conversation (answer to a question). */
        val linkedTo: String? = null,
        /** When the final (non-pending) answer arrived; starts the follow-up window. */
        var respondedAt: Long = 0,
    ) {
        // Runtime only, not persisted.
        var busy = false
        var attempts = 0
        val response: SymconResponse?
            get() = (SymconResponse.parse(rawResponse) as? Parsed.Ok)?.response
        val transcript: String
            get() = runCatching { JSONObject(body).optString("transcript") }.getOrDefault("")
        val dryRun: Boolean
            get() = runCatching { JSONObject(body).optBoolean("dryRun", false) }.getOrDefault(false)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "symcon") }
    private val client = SymconClient()
    private val jobs = mutableMapOf<String, MutableList<Job>>()

    private val _views = MutableStateFlow<Map<String, SymconView>>(emptyMap())
    val views: StateFlow<Map<String, SymconView>> = _views.asStateFlow()

    init {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL, "KI-Antworten", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        executor.execute { loadAll() }
    }

    fun hasJob(wav: File): Boolean = File(recordingsDir, wav.nameWithoutExtension + SUFFIX).exists()

    /**
     * Send a new transcript. If the smart home is waiting for an answer to a
     * question, the transcript is sent as that answer instead.
     */
    /**
     * @param dryRun recognition test only: the service resolves the request but switches nothing.
     */
    fun submit(recording: Recording, transcript: String, dryRun: Boolean = false) {
        executor.execute {
            val open = openClarification()
            if (open != null) {
                val (job, clarification) = open
                val option = matchOption(transcript, clarification.options)
                if (option != null || clarification.allowFreeText) {
                    job.answered = true
                    save(job.wavPath)
                    startJob(
                        recording.file.path,
                        request(recording, transcript, ReplyTo(job.requestId, clarification.id, option?.id), job.dryRun || dryRun),
                        linkedTo = job.requestId,
                    )
                    return@execute
                }
                Log.i(TAG, "Answer matches no option; sending as a new request")
            }
            startJob(recording.file.path, request(recording, transcript, null, dryRun))
        }
    }

    /**
     * Answer an open question by tapping an option or typing (in the app or the
     * notification). Option ids go back exactly as the service sent them.
     */
    fun answer(wavPath: String, optionId: String?, text: String) {
        executor.execute {
            val job = jobs[wavPath]?.lastOrNull() ?: return@execute
            val clarification = openClarificationOf(job)
            if (clarification == null || text.isBlank()) {
                job.note = "Rückfrage ist nicht mehr offen – bitte den Auftrag neu erteilen"
                publish()
                return@execute
            }
            val reply = SymconRequest(
                requestId = UUID.randomUUID().toString(),
                transcript = text,
                locale = Locale.getDefault().toLanguageTag(),
                replyTo = ReplyTo(job.requestId, clarification.id, optionId),
                // Answers to a dry-run question stay dry runs.
                dryRun = job.dryRun,
            )
            job.answered = true
            save(wavPath)
            startJob(wavPath, reply, linkedTo = job.requestId)
        }
    }

    /** Ask again for the latest request of a recording; never creates a new switching request. */
    fun refresh(wavPath: String) {
        executor.execute {
            val job = jobs[wavPath]?.lastOrNull() ?: return@execute
            if (job.busy) return@execute
            job.attempts = 0
            when {
                job.pollUrl != null -> poll(job, manual = true)
                System.currentTimeMillis() - job.submittedAt < DUPLICATE_WINDOW_MS -> send(job)
                else -> {
                    job.note = "Auftrag ist älter als 24 Stunden – bitte neu erteilen"
                    publish()
                }
            }
        }
    }

    // ---- internals (symcon thread) --------------------------------------------------------------

    private fun request(recording: Recording, transcript: String, replyTo: ReplyTo?, dryRun: Boolean) = SymconRequest(
        requestId = UUID.randomUUID().toString(),
        transcript = transcript,
        recording = recording.name,
        createdAt = iso(recording.createdAt),
        durationMs = recording.durationMs,
        locale = Locale.getDefault().toLanguageTag(),
        replyTo = replyTo,
        dryRun = dryRun,
    )

    private fun startJob(wavPath: String, request: SymconRequest, linkedTo: String? = null) {
        val job = Job(wavPath, request.requestId, request.toJson(), System.currentTimeMillis(), linkedTo = linkedTo)
        jobs.getOrPut(wavPath) { mutableListOf() } += job
        save(wavPath)
        send(job)
    }

    private fun send(job: Job) {
        val config = settings.config.value
        if (!config.isSymconReady()) return fail(job, "Smarthome-Verbindung ist nicht eingerichtet")
        job.busy = true
        job.note = null
        publish()
        handle(job, client.post(config.symconUrl, config.symconKey, job.body), config) { send(job) }
    }

    private fun poll(job: Job, manual: Boolean = false) {
        val config = settings.config.value
        val url = job.pollUrl ?: return
        if (!manual && System.currentTimeMillis() - job.submittedAt > POLL_WINDOW_MS) {
            job.busy = false
            job.note = "Ergebnis noch offen"
            save(job.wavPath)
            publish()
            return
        }
        job.busy = true
        publish()
        handle(job, client.get(url, config.symconKey), config) { poll(job) }
    }

    /** Evaluate one HTTP exchange; [retry] repeats the same exchange (same requestId, same body). */
    private fun handle(job: Job, result: SymconClient.Result, config: AiConfig, retry: () -> Unit) {
        when (result) {
            is SymconClient.Result.Transport -> retryOrGiveUp(job, "Keine Verbindung zum Smarthome", null, retry)
            is SymconClient.Result.Http -> when (val parsed = SymconResponse.parse(result.body)) {
                is Parsed.Ok -> {
                    val r = parsed.response
                    val serverBusy = result.code == 429 || result.code == 503
                    if (serverBusy && r.error?.retryable == true) {
                        retryOrGiveUp(job, r.message.ifBlank { "Smarthome ist gerade ausgelastet" }, result.retryAfterMs, retry)
                    } else {
                        apply(job, r, result.body!!, config)
                    }
                }
                is Parsed.Incompatible -> fail(job, "Smarthome antwortet mit inkompatibler Version ${parsed.version}")
                is Parsed.Invalid -> when {
                    result.code == 401 -> fail(job, "Zugangsschlüssel ungültig")
                    result.code in 300..399 -> fail(job, "Weiterleitung abgelehnt (HTTP ${result.code})")
                    result.code == 429 || result.code >= 500 ->
                        retryOrGiveUp(job, "Smarthome-Dienst gestört (HTTP ${result.code})", result.retryAfterMs, retry)
                    else -> fail(job, "Unerwartete Antwort vom Smarthome (HTTP ${result.code}, ${parsed.reason})")
                }
            }
        }
    }

    /**
     * A timeout or 5xx does not prove nothing was switched, so only the identical
     * request is repeated (the server's duplicate protection keeps it idempotent).
     */
    private fun retryOrGiveUp(job: Job, message: String, retryAfterMs: Long?, retry: () -> Unit) {
        val delay = retryAfterMs ?: BACKOFF_MS.getOrNull(job.attempts)
        if (delay != null && job.attempts < BACKOFF_MS.size && delay <= MAX_RETRY_DELAY_MS) {
            job.attempts++
            job.note = "$message – neuer Versuch …"
            publish()
            executor.schedule(Runnable { retry() }, delay, TimeUnit.MILLISECONDS)
        } else {
            fail(job, message)
        }
    }

    private fun fail(job: Job, message: String) {
        job.busy = false
        job.note = message
        save(job.wavPath)
        publish()
        if (settings.config.value.statusTone) feedback.tone(Feedback.Tone.ERROR)
    }

    private fun apply(job: Job, r: SymconResponse, raw: String, config: AiConfig) {
        job.rawResponse = raw
        job.note = null
        job.attempts = 0
        if (r.status == SymconProtocol.PENDING) {
            val poll = r.poll
            val url = poll?.url
            when {
                poll == null || url == null -> {
                    job.busy = false
                    job.note = "Ergebnis noch offen"
                }
                !SymconProtocol.isSameHttpsOrigin(config.symconUrl, url) -> {
                    job.busy = false
                    job.pollUrl = null
                    job.note = "Status-URL gehört nicht zum Smarthome-Server – nicht abgefragt"
                }
                else -> {
                    job.pollUrl = url
                    val wait = poll.afterMs.coerceAtLeast(MIN_POLL_MS)
                    executor.schedule(Runnable { poll(job) }, wait, TimeUnit.MILLISECONDS)
                }
            }
            save(job.wavPath)
            publish()
            return
        }
        job.busy = false
        job.pollUrl = null
        if (job.respondedAt == 0L) job.respondedAt = System.currentTimeMillis()
        save(job.wavPath)
        publish()
        announce(job, r, config)
    }

    /** Tone, speech and notification — once per distinct result, even across repeated polls. */
    private fun announce(job: Job, r: SymconResponse, config: AiConfig) {
        val key = "${job.requestId}|${r.status}|${r.message}"
        if (job.spokenKey == key) return
        job.spokenKey = key
        save(job.wavPath)
        if (config.statusTone) {
            feedback.tone(
                when (r.status) {
                    SymconProtocol.COMPLETED -> Feedback.Tone.SUCCESS
                    SymconProtocol.CLARIFICATION_REQUIRED -> Feedback.Tone.QUESTION
                    SymconProtocol.PARTIAL -> Feedback.Tone.WARNING
                    SymconProtocol.REJECTED, SymconProtocol.FAILED -> Feedback.Tone.ERROR
                    else -> Feedback.Tone.WARNING
                },
            )
        }
        if (config.speakReplies) feedback.speak(r.message, Locale.getDefault().toLanguageTag())
        notify(job, r)
    }

    private fun notify(job: Job, r: SymconResponse) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || r.message.isBlank()) return
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_ring)
            .setContentTitle("Smarthome · ${statusLabel(r.status)}")
            .setContentText(r.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.message))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        openClarificationOf(job)?.options?.take(3)?.forEachIndexed { i, option ->
            val intent = Intent(context, SymconReplyReceiver::class.java)
                .putExtra(SymconReplyReceiver.EXTRA_PATH, job.wavPath)
                .putExtra(SymconReplyReceiver.EXTRA_OPTION_ID, option.id)
                .putExtra(SymconReplyReceiver.EXTRA_LABEL, option.label)
                .putExtra(SymconReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId(job))
            val pending = PendingIntent.getBroadcast(
                context, (job.requestId + i).hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, option.label, pending)
        }
        try {
            manager.notify(notificationId(job), builder.build())
        } catch (_: SecurityException) {
            // Notifications not permitted; the result is still shown in the app.
        }
    }

    private fun notificationId(job: Job) = job.wavPath.hashCode()

    private fun openClarification(): Pair<Job, Clarification>? =
        jobs.values.mapNotNull { it.lastOrNull() }
            .sortedByDescending { it.submittedAt }
            .firstNotNullOfOrNull { job -> openClarificationOf(job)?.let { job to it } }

    private fun openClarificationOf(job: Job): Clarification? {
        val r = job.response ?: return null
        if (r.status != SymconProtocol.CLARIFICATION_REQUIRED || job.answered) return null
        val c = r.clarification ?: return null
        val expires = c.expiresAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        return if (expires != null && expires < System.currentTimeMillis()) null else c
    }

    /** The conversation up to [job]: earlier requests it answers, oldest first. */
    private fun threadOf(job: Job): List<ThreadEntry> {
        val byId = jobs.values.flatten().associateBy { it.requestId }
        val chain = mutableListOf<Job>()
        var current: Job? = byId[job.linkedTo]
        while (current != null && chain.size < MAX_THREAD) {
            chain += current
            current = byId[current.linkedTo]
        }
        return chain.reversed().map { ThreadEntry(it.transcript, it.response?.message.orEmpty()) }
    }


    private fun publish() {
        _views.value = jobs.mapValues { (_, list) ->
            val job = list.last()
            val r = job.response
            SymconView(
                status = r?.status,
                message = r?.message,
                actions = r?.actions.orEmpty(),
                clarification = openClarificationOf(job),
                note = job.note,
                busy = job.busy,
                canRefresh = !job.busy &&
                    (r == null || r.status == SymconProtocol.PENDING || job.note != null || r.error?.retryable == true),
                awaitingAnswer = openClarificationOf(job) != null,
                dryRun = job.dryRun,
                thread = threadOf(job),
                transcript = job.transcript,
            )
        }
    }

    // ---- persistence ------------------------------------------------------------------------------

    private fun loadAll() {
        recordingsDir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().forEach { file ->
            val wav = File(recordingsDir, file.name.removeSuffix(SUFFIX) + ".wav")
            if (!wav.exists()) return@forEach
            try {
                val array = JSONArray(file.readText())
                jobs[wav.path] = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    Job(
                        wavPath = wav.path,
                        requestId = o.getString("requestId"),
                        body = o.getString("body"),
                        submittedAt = o.getLong("submittedAt"),
                        pollUrl = o.optString("pollUrl").ifEmpty { null },
                        rawResponse = o.optString("rawResponse").ifEmpty { null },
                        note = o.optString("note").ifEmpty { null },
                        spokenKey = o.optString("spokenKey").ifEmpty { null },
                        answered = o.optBoolean("answered", false),
                        linkedTo = o.optString("linkedTo").ifEmpty { null } ?: replyToOf(o.getString("body")),
                        respondedAt = o.optLong("respondedAt", 0),
                    )
                }.toMutableList()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ${file.name}", e)
            }
        }
        publish()
    }

    private fun save(wavPath: String) {
        val list = jobs[wavPath] ?: return
        val wav = File(wavPath)
        if (!wav.exists()) {
            jobs.remove(wavPath)
            return
        }
        val array = JSONArray()
        list.forEach { job ->
            array.put(
                JSONObject()
                    .put("requestId", job.requestId)
                    .put("body", job.body)
                    .put("submittedAt", job.submittedAt)
                    .put("pollUrl", job.pollUrl ?: "")
                    .put("rawResponse", job.rawResponse ?: "")
                    .put("note", job.note ?: "")
                    .put("spokenKey", job.spokenKey ?: "")
                    .put("answered", job.answered)
                    .put("linkedTo", job.linkedTo ?: "")
                    .put("respondedAt", job.respondedAt),
            )
        }
        File(recordingsDir, wav.nameWithoutExtension + SUFFIX).writeText(array.toString())
    }

    /** Older saved jobs have no linkedTo; recover it from the request's replyTo. */
    private fun replyToOf(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("replyTo")?.optString("requestId") }.getOrNull()?.ifEmpty { null }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    companion object {
        const val SUFFIX = ".symcon.json"
        private const val TAG = "SymconJobs"
        private const val NOTIFICATION_CHANNEL = "ai"
        private const val POLL_WINDOW_MS = 60_000L
        private const val DUPLICATE_WINDOW_MS = 24 * 60 * 60_000L
        private const val MIN_POLL_MS = 500L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private val BACKOFF_MS = listOf(2_000L, 5_000L, 10_000L)
        private const val MAX_THREAD = 10

        fun statusLabel(status: String?): String = when (status) {
            SymconProtocol.COMPLETED -> "Erledigt"
            SymconProtocol.PENDING -> "In Arbeit"
            SymconProtocol.CLARIFICATION_REQUIRED -> "Rückfrage"
            SymconProtocol.PARTIAL -> "Teilweise erledigt"
            SymconProtocol.REJECTED -> "Abgelehnt"
            SymconProtocol.FAILED -> "Fehlgeschlagen"
            null -> "Gesendet"
            else -> status
        }
    }
}
