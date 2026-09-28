package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hagbard235.ringnotes.ai.AiConfig
import io.github.hagbard235.ringnotes.ai.AiTarget

@Composable
fun AiSettingsTab(config: AiConfig, onUpdate: ((AiConfig) -> AiConfig) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Transkripte weiterleiten", style = MaterialTheme.typography.titleMedium)
        Text(
            "Neue Aufnahmen werden im Hintergrund transkribiert und der Text automatisch hierhin geschickt. " +
                "Ältere Aufnahmen kannst du einzeln über „An KI senden“ verschicken.",
            style = MaterialTheme.typography.bodySmall,
        )

        TargetOption("Aus", config.target == AiTarget.OFF) { onUpdate { it.copy(target = AiTarget.OFF) } }
        TargetOption("Claude (Anthropic-API)", config.target == AiTarget.CLAUDE) { onUpdate { it.copy(target = AiTarget.CLAUDE) } }
        TargetOption("Webhook (z. B. n8n, Home Assistant, Make)", config.target == AiTarget.WEBHOOK) {
            onUpdate { it.copy(target = AiTarget.WEBHOOK) }
        }

        when (config.target) {
            AiTarget.CLAUDE -> {
                OutlinedTextField(
                    value = config.claudeApiKey,
                    onValueChange = { v -> onUpdate { it.copy(claudeApiKey = v.trim()) } },
                    label = { Text("API-Schlüssel") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Von console.anthropic.com. Wird nur auf diesem Gerät gespeichert.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.claudeModel,
                    onValueChange = { v -> onUpdate { it.copy(claudeModel = v.trim()) } },
                    label = { Text("Modell") },
                    singleLine = true,
                    supportingText = { Text("Standard: ${AiConfig.DEFAULT_MODEL}; günstiger: claude-sonnet-5 oder claude-haiku-4-5") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.instruction,
                    onValueChange = { v -> onUpdate { it.copy(instruction = v) } },
                    label = { Text("Anweisung an Claude") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onUpdate { it.copy(instruction = AiConfig.DEFAULT_INSTRUCTION) } }) {
                    Text("Standard-Anweisung wiederherstellen")
                }
                Text(
                    "Die Antwort erscheint unter der Aufnahme und als Benachrichtigung. Jede Anfrage kostet API-Guthaben.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AiTarget.WEBHOOK -> {
                OutlinedTextField(
                    value = config.webhookUrl,
                    onValueChange = { v -> onUpdate { it.copy(webhookUrl = v.trim()) } },
                    label = { Text("Webhook-URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Gesendet wird ein POST mit JSON:\n" +
                        "{\"recording\", \"createdAt\", \"durationMs\", \"transcript\"}\n" +
                        "Antwortet der Webhook mit Text, wird er unter der Aufnahme angezeigt.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AiTarget.OFF -> Unit
        }

        if (config.target != AiTarget.OFF && !config.isReady) {
            Text("Noch nicht vollständig eingerichtet.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TargetOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
