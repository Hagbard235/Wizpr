package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import io.github.hagbard235.ringnotes.notes.Note
import java.text.DateFormat
import java.util.Date

class NotesActions(
    val onShare: (List<Note>) -> Unit,
    val onCopy: (Note) -> Unit,
    val onDelete: (Note) -> Unit,
    val onSetTriggers: (String) -> Unit,
)

@Composable
fun NotesTab(notes: List<Note>, triggers: List<String>, actions: NotesActions) {
    var toDelete by remember { mutableStateOf<Note?>(null) }
    var editTriggers by remember { mutableStateOf<String?>(null) }
    val open = notes.filterNot { it.shared }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Beginnt eine Aufnahme mit „${triggers.first()}“ (oder: ${triggers.drop(1).joinToString(", ")}), " +
                        "wird der Rest hier als Notiz gespeichert und nicht weitergeleitet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = open.isNotEmpty(), onClick = { actions.onShare(open) }) {
                        Text(if (open.isEmpty()) "Alles an Keep geteilt" else "${open.size} offene an Keep")
                    }
                    TextButton(onClick = { editTriggers = triggers.joinToString(", ") }) { Text("Stichworte") }
                }
            }
        }
        if (notes.isEmpty()) {
            item { Text("Noch keine Notizen. Sag zum Beispiel: „Notiz an mich selbst: Milch kaufen“.") }
        }
        items(notes, key = { it.id }) { note ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyLarge) }
                    Text(
                        format.format(Date(note.createdAt)) + if (note.shared) " · an Keep geteilt" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { actions.onShare(listOf(note)) }) { Text("An Keep") }
                        TextButton(onClick = { actions.onCopy(note) }) { Text("Kopieren") }
                        TextButton(onClick = { toDelete = note }) { Text("Löschen") }
                    }
                }
            }
        }
    }

    toDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Notiz löschen?") },
            text = { Text(note.text) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(note)
                    toDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }

    editTriggers?.let { value ->
        AlertDialog(
            onDismissRequest = { editTriggers = null },
            title = { Text("Stichworte für Notizen") },
            text = {
                Column {
                    OutlinedTextField(value = value, onValueChange = { editTriggers = it }, minLines = 3)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Durch Komma getrennt. Gilt nur am Anfang einer Aufnahme; leer = Standard.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onSetTriggers(value)
                    editTriggers = null
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editTriggers = null }) { Text("Abbrechen") } },
        )
    }
}
