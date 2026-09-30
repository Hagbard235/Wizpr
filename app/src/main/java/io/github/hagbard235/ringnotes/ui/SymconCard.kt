package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hagbard235.ringnotes.core.symcon.SymconAction
import io.github.hagbard235.ringnotes.core.symcon.SymconProtocol
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.hagbard235.ringnotes.symcon.SymconJobs
import io.github.hagbard235.ringnotes.symcon.SymconView

/** Result of a smart-home request: status, message, action details and open questions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymconCard(view: SymconView, onRefresh: () -> Unit, onAnswer: (optionId: String?, text: String) -> Unit) {
    var freeText by remember { mutableStateOf<String?>(null) }
    val container = when (view.status) {
        SymconProtocol.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        SymconProtocol.CLARIFICATION_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer
        SymconProtocol.FAILED, SymconProtocol.REJECTED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view.busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                val test = if (view.dryRun) " · Test (schaltet nichts)" else ""
                Text("Smarthome · ${SymconJobs.statusLabel(view.status)}$test", style = MaterialTheme.typography.labelLarge)
            }
            // Earlier turns of the conversation, so an answer is readable in context.
            view.thread.forEach { turn ->
                Text("Du: ${turn.request}", style = MaterialTheme.typography.bodySmall)
                if (turn.reply.isNotBlank()) {
                    Text("Smarthome: ${turn.reply}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (view.thread.isNotEmpty() && view.transcript.isNotBlank()) {
                Text("Du: ${view.transcript}", style = MaterialTheme.typography.bodySmall)
            }
            // Shown verbatim as plain text, as the contract requires.
            view.message?.takeIf { it.isNotBlank() }?.let {
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            view.actions.forEach { a ->
                Text(
                    "• ${a.deviceName ?: a.deviceId ?: "Gerät"}: ${a.operation ?: a.service ?: ""} – ${actionStatus(a.status)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                scheduledOff(a)?.let { Text("   $it", style = MaterialTheme.typography.bodySmall) }
            }
            view.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            view.clarification?.let { c ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.options.forEach { o -> OutlinedButton(onClick = { onAnswer(o.id, o.label) }) { Text(o.label) } }
                    if (c.allowFreeText) TextButton(onClick = { freeText = "" }) { Text("Antwort eingeben") }
                }
                if (c.allowFreeText) {
                    Text("Oder einfach am Ring antworten.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (view.canRefresh) TextButton(onClick = onRefresh) { Text("Aktualisieren") }
        }
    }

    freeText?.let { text ->
        AlertDialog(
            onDismissRequest = { freeText = null },
            title = { Text("Antwort an das Smarthome") },
            text = { OutlinedTextField(value = text, onValueChange = { freeText = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onAnswer(null, text.trim())
                        freeText = null
                    },
                ) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { freeText = null }) { Text("Abbrechen") } },
        )
    }
}

/**
 * A timed switch-on: the switch-off is only planned. The app never learns whether it
 * happened, so it must not claim the device is off.
 */
private fun scheduledOff(a: SymconAction): String? {
    val at = a.offAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
    if (at == null && a.durationSeconds == null) return null
    val time = at?.atZoneSameInstant(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val state = if (a.offStatus == "scheduled") "geplant, nicht bestätigt" else a.offStatus ?: "geplant"
    return when {
        time != null -> "Ausschalten um $time ($state)"
        else -> "Ausschalten nach ${a.durationSeconds} s ($state)"
    }
}

private fun actionStatus(status: String) = when (status) {
    "confirmed" -> "bestätigt"
    "pending" -> "ausstehend"
    "failed" -> "fehlgeschlagen"
    // "unknown" does not mean nothing happened; never retried automatically.
    "unknown" -> "unklar"
    else -> status
}
