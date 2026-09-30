package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Shown once after a crash so the report can be shared. */
@Composable
fun CrashDialog(report: String, onShare: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ring Notes ist abgestürzt") },
        text = {
            Column {
                Text("Teile den Bericht, damit der Fehler gefunden werden kann.")
                Text(
                    report.lineSequence().take(12).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Bericht teilen") } },
        dismissButton = {
            Column {
                TextButton(onClick = onCopy) { Text("Kopieren") }
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        },
    )
}
