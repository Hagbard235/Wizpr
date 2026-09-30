package io.github.hagbard235.ringnotes.core.symcon

import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wire format of the "Ring Notes und IP Symcon – KI-Steuerung über Webhook"
 * contract, version 1. Pure data mapping; transport lives in the app.
 */
object SymconProtocol {
    const val VERSION = "1"

    /** Request-level statuses. Anything else is shown via `message` but never retried automatically. */
    const val COMPLETED = "completed"
    const val PENDING = "pending"
    const val CLARIFICATION_REQUIRED = "clarification_required"
    const val PARTIAL = "partial"
    const val REJECTED = "rejected"
    const val FAILED = "failed"

    /** Polling stops for every status except [PENDING]. */
    fun isFinal(status: String): Boolean = status != PENDING

    /**
     * A poll URL may only receive the access key if it has the same HTTPS origin
     * (scheme, host, effective port) as the configured webhook.
     */
    fun isSameHttpsOrigin(webhookUrl: String, otherUrl: String): Boolean {
        val a = runCatching { URI(webhookUrl) }.getOrNull() ?: return false
        val b = runCatching { URI(otherUrl) }.getOrNull() ?: return false
        if (!a.scheme.equals("https", ignoreCase = true) || !b.scheme.equals("https", ignoreCase = true)) return false
        val hostA = a.host ?: return false
        val hostB = b.host ?: return false
        return hostA.equals(hostB, ignoreCase = true) && effectivePort(a) == effectivePort(b)
    }

    private fun effectivePort(uri: URI) = if (uri.port == -1) 443 else uri.port
}

data class ReplyTo(
    val requestId: String,
    val clarificationId: String,
    /** Null for a free-text answer. */
    val optionId: String?,
)

data class SymconRequest(
    val requestId: String,
    val transcript: String,
    val recording: String? = null,
    /** RFC 3339 with time zone, e.g. 2026-09-28T12:45:25Z. */
    val createdAt: String? = null,
    val durationMs: Long? = null,
    val locale: String? = null,
    val replyTo: ReplyTo? = null,
    /** Recognition test only: the service resolves the request but switches nothing. */
    val dryRun: Boolean = false,
) {
    init {
        require(transcript.isNotBlank()) { "transcript must not be empty" }
    }

    /**
     * Serialize once and resend these exact bytes on every retry: the server
     * answers 409 REQUEST_ID_CONFLICT when a repeated requestId carries different content.
     */
    fun toJson(): String {
        val json = JSONObject()
            .put("version", SymconProtocol.VERSION)
            .put("requestId", requestId)
            .put("transcript", transcript)
        recording?.let { json.put("recording", it) }
        createdAt?.let { json.put("createdAt", it) }
        durationMs?.let { json.put("durationMs", it.coerceAtLeast(0)) }
        locale?.let { json.put("locale", it) }
        replyTo?.let { r ->
            val reply = JSONObject()
                .put("requestId", r.requestId)
                .put("clarificationId", r.clarificationId)
            r.optionId?.let { reply.put("optionId", it) }
            json.put("replyTo", reply)
        }
        if (dryRun) json.put("dryRun", true)
        return json.toString()
    }
}

data class SymconAction(
    val deviceId: String?,
    val deviceName: String?,
    val service: String?,
    val operation: String?,
    /** pending, confirmed, failed or unknown; `unknown` must never trigger a new switching request. */
    val status: String,
    /** Timed switch-on: planned duration, planned switch-off time (RFC 3339) and its state. */
    val durationSeconds: Long? = null,
    val offAt: String? = null,
    /** "scheduled" = planned, not confirmed; the later result is not pushed to the app. */
    val offStatus: String? = null,
)

data class SymconError(val code: String, val retryable: Boolean)

data class ClarificationOption(val id: String, val label: String)

data class Clarification(
    val id: String,
    val expiresAt: String?,
    val options: List<ClarificationOption>,
    val allowFreeText: Boolean,
)

data class Poll(val url: String, val afterMs: Long)

data class SymconResponse(
    val version: String,
    val requestId: String?,
    val status: String,
    val message: String,
    val actions: List<SymconAction>,
    val error: SymconError?,
    val clarification: Clarification?,
    val poll: Poll?,
) {
    companion object {
        /** Result of [parse]: either a response or the reason it could not be read. */
        sealed interface Parsed {
            data class Ok(val response: SymconResponse) : Parsed
            data class Invalid(val reason: String) : Parsed
            /** A different major version: treat as incompatible. */
            data class Incompatible(val version: String) : Parsed
        }

        fun parse(body: String?): Parsed {
            if (body.isNullOrBlank()) return Parsed.Invalid("leere Antwort")
            val json = try {
                JSONObject(body)
            } catch (e: JSONException) {
                return Parsed.Invalid("keine JSON-Antwort")
            }
            val version = json.optString("version", "")
            if (version.isEmpty()) return Parsed.Invalid("Feld version fehlt")
            if (version.substringBefore('.') != SymconProtocol.VERSION) return Parsed.Incompatible(version)
            val status = json.optString("status", "")
            if (status.isEmpty()) return Parsed.Invalid("Feld status fehlt")
            return Parsed.Ok(
                SymconResponse(
                    version = version,
                    requestId = json.stringOrNull("requestId"),
                    status = status,
                    message = json.optString("message", ""),
                    actions = json.optJSONArray("actions").objects().map(::parseAction),
                    error = json.optJSONObject("error")?.let {
                        SymconError(it.optString("code", "UNKNOWN"), it.optBoolean("retryable", false))
                    },
                    clarification = json.optJSONObject("clarification")?.let(::parseClarification),
                    poll = json.optJSONObject("poll")?.let { p ->
                        p.stringOrNull("url")?.let { Poll(it, p.optLong("afterMs", DEFAULT_POLL_MS).coerceAtLeast(0)) }
                    },
                ),
            )
        }

        private const val DEFAULT_POLL_MS = 1_500L

        private fun parseAction(a: JSONObject): SymconAction {
            val p = a.optJSONObject("parameters")
            return SymconAction(
                deviceId = a.stringOrNull("deviceId"),
                deviceName = a.stringOrNull("deviceName"),
                service = a.stringOrNull("service"),
                operation = a.stringOrNull("operation"),
                status = a.optString("status", "unknown"),
                durationSeconds = p?.takeIf { it.has("durationSeconds") && !it.isNull("durationSeconds") }
                    ?.optLong("durationSeconds"),
                offAt = p?.stringOrNull("offAt"),
                offStatus = p?.stringOrNull("offStatus"),
            )
        }

        private fun parseClarification(c: JSONObject) = Clarification(
            id = c.optString("id", ""),
            expiresAt = c.stringOrNull("expiresAt"),
            options = c.optJSONArray("options").objects().mapNotNull { o ->
                val id = o.stringOrNull("id") ?: return@mapNotNull null
                // An empty label would become an empty answer transcript, which the contract forbids.
                ClarificationOption(id, o.stringOrNull("label")?.takeIf { it.isNotBlank() } ?: id)
            },
            allowFreeText = c.optBoolean("allowFreeText", false),
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key)
    }
}

/**
 * Picks the clarification option a spoken answer refers to, e.g. "den Sternenhimmel"
 * → option "Sternenhimmel". Null when no single option matches.
 */
fun matchOption(answer: String, options: List<ClarificationOption>): ClarificationOption? {
    val normalized = answer.lowercase().trim()
    val hits = options.filter { o ->
        val label = o.label.lowercase().trim()
        label.isNotEmpty() && (normalized.contains(label) || label.contains(normalized.takeIf { it.length >= 3 } ?: "\u0000"))
    }
    return hits.singleOrNull()
}
