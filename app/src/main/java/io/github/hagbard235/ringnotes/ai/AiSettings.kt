package io.github.hagbard235.ringnotes.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiTarget { OFF, CLAUDE, WEBHOOK }

data class AiConfig(
    val target: AiTarget = AiTarget.OFF,
    val claudeApiKey: String = "",
    val claudeModel: String = DEFAULT_MODEL,
    val instruction: String = DEFAULT_INSTRUCTION,
    val webhookUrl: String = "",
    /** Only recordings created after this moment are forwarded automatically. */
    val enabledSince: Long = 0,
) {
    val isReady: Boolean
        get() = when (target) {
            AiTarget.OFF -> false
            AiTarget.CLAUDE -> claudeApiKey.isNotBlank() && claudeModel.isNotBlank()
            AiTarget.WEBHOOK -> webhookUrl.startsWith("https://") || webhookUrl.startsWith("http://")
        }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        const val DEFAULT_INSTRUCTION =
            "Das folgende ist eine gesprochene Notiz, aufgenommen mit einem Smart Ring und automatisch " +
                "transkribiert (Erkennungsfehler sind möglich). Fasse sie knapp zusammen und liste " +
                "Aufgaben, Termine und offene Fragen als Stichpunkte auf."
    }
}

/**
 * Forwarding settings, kept in app-private SharedPreferences. The API key never
 * leaves the device except in requests to the Claude API.
 */
class AiSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun update(transform: (AiConfig) -> AiConfig) {
        val old = _config.value
        var new = transform(old)
        if (old.target == AiTarget.OFF && new.target != AiTarget.OFF) {
            new = new.copy(enabledSince = System.currentTimeMillis())
        }
        prefs.edit()
            .putString("target", new.target.name)
            .putString("claudeApiKey", new.claudeApiKey)
            .putString("claudeModel", new.claudeModel)
            .putString("instruction", new.instruction)
            .putString("webhookUrl", new.webhookUrl)
            .putLong("enabledSince", new.enabledSince)
            .apply()
        _config.value = new
    }

    private fun load() = AiConfig(
        target = prefs.getString("target", null)?.let { runCatching { AiTarget.valueOf(it) }.getOrNull() } ?: AiTarget.OFF,
        claudeApiKey = prefs.getString("claudeApiKey", "") ?: "",
        claudeModel = prefs.getString("claudeModel", null) ?: AiConfig.DEFAULT_MODEL,
        instruction = prefs.getString("instruction", null) ?: AiConfig.DEFAULT_INSTRUCTION,
        webhookUrl = prefs.getString("webhookUrl", "") ?: "",
        enabledSince = prefs.getLong("enabledSince", 0),
    )
}
