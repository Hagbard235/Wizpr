package io.github.hagbard235.ringnotes.ai

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import io.github.hagbard235.ringnotes.recording.Recording
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Sends a transcript to the configured AI target. Blocking; call from a background thread. */
class AiForwarder {
    sealed interface Result {
        /** [reply] is shown in the app; null when the target returns nothing worth showing. */
        data class Success(val reply: String?) : Result
        data class Failure(val message: String) : Result
    }

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    fun forward(config: AiConfig, recording: Recording, transcript: String): Result = when (config.target) {
        AiTarget.OFF -> Result.Failure("KI-Weiterleitung ist ausgeschaltet")
        AiTarget.CLAUDE -> askClaude(config, transcript)
        AiTarget.WEBHOOK -> postWebhook(config, recording, transcript)
        // Handled by SymconJobs (polling, clarifications); never routed here.
        AiTarget.SYMCON -> Result.Failure("Smarthome-Aufträge laufen über SymconJobs")
    }

    private fun claudeClient(apiKey: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == apiKey) return existing
        existing?.close()
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build().also {
            client = it
            clientKey = apiKey
        }
    }

    private fun askClaude(config: AiConfig, transcript: String): Result {
        val builder = MessageCreateParams.builder()
            .model(config.claudeModel)
            .maxTokens(16000L)
            .system(config.instruction)
            .addUserMessage(transcript)
        if (config.claudeModel in MODELS_WITH_DEFAULT_FALLBACK) {
            // If Claude's safety classifiers decline, rerun server-side on Anthropic's recommended fallback model.
            builder.addBeta(FALLBACK_BETA)
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return try {
            val response = claudeClient(config.claudeApiKey).beta().messages().create(builder.build())
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                return Result.Failure("Claude hat die Anfrage abgelehnt")
            }
            val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n")
                .trim()
            Result.Success(text.ifEmpty { null })
        } catch (e: UnauthorizedException) {
            Result.Failure("API-Schlüssel ungültig")
        } catch (e: PermissionDeniedException) {
            Result.Failure("Keine Berechtigung für dieses Modell")
        } catch (e: NotFoundException) {
            Result.Failure("Modell „${config.claudeModel}“ nicht gefunden")
        } catch (e: BadRequestException) {
            Result.Failure("Ungültige Anfrage: ${e.message}")
        } catch (e: RateLimitException) {
            Result.Failure("Rate-Limit erreicht, später erneut versuchen")
        } catch (e: AnthropicServiceException) {
            Result.Failure("Claude-API-Fehler ${e.statusCode()}")
        } catch (e: AnthropicIoException) {
            Result.Failure("Keine Verbindung zur Claude-API")
        } catch (e: Exception) {
            Log.e(TAG, "Claude request failed", e)
            Result.Failure("Claude-Anfrage fehlgeschlagen: ${e.message}")
        }
    }

    /** POSTs `{"recording", "createdAt", "durationMs", "transcript"}` as JSON; a text reply is shown in the app. */
    private fun postWebhook(config: AiConfig, recording: Recording, transcript: String): Result {
        val body = JSONObject()
            .put("recording", recording.name)
            .put("createdAt", isoTime(recording.createdAt))
            .put("durationMs", recording.durationMs)
            .put("transcript", transcript)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return try {
            val conn = URL(config.webhookUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code !in 200..299) return Result.Failure("Webhook antwortete mit HTTP $code")
                val reply = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                Result.Success(reply.takeIf { it.isNotEmpty() && it.length <= MAX_WEBHOOK_REPLY })
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Result.Failure("Webhook nicht erreichbar: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Result.Failure("Ungültige Webhook-URL")
        }
    }

    private fun isoTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private companion object {
        const val TAG = "AiForwarder"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        val MODELS_WITH_DEFAULT_FALLBACK = setOf("claude-opus-5", "claude-fable-5-1")
        const val MAX_WEBHOOK_REPLY = 20_000
    }
}
