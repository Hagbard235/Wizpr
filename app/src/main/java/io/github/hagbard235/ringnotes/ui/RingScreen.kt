package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import io.github.hagbard235.ringnotes.notes.Note
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.hagbard235.ringnotes.LinkState
import io.github.hagbard235.ringnotes.RingUiState
import io.github.hagbard235.ringnotes.ai.AiConfig
import io.github.hagbard235.ringnotes.ai.AiTarget
import io.github.hagbard235.ringnotes.ble.FoundRing
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.statusText
import io.github.hagbard235.ringnotes.symcon.SymconView
import io.github.hagbard235.ringnotes.transcription.TranscriptionStatus
import java.io.File
import java.text.DateFormat
import java.util.Date

class RingScreenActions(
    val onScan: () -> Unit,
    val onStopScan: () -> Unit,
    val onConnect: (FoundRing) -> Unit,
    val onConnectLast: () -> Unit,
    val onDisconnect: () -> Unit,
    val onLock: () -> Unit,
    val onUnlock: () -> Unit,
    val onRequestBattery: () -> Unit,
    val onPlay: (Recording) -> Unit,
    val onShare: (Recording) -> Unit,
    val onDelete: (Recording) -> Unit,
    val onDeleteAll: () -> Unit,
    val onTranscribe: (Recording) -> Unit,
    val onSendToAi: (Recording) -> Unit,
    val onUpdateAi: ((AiConfig) -> AiConfig) -> Unit,
    val onSymconRefresh: (Recording) -> Unit,
    /** optionId is null for a free-text answer. */
    val onSymconAnswer: (Recording, String?, String) -> Unit,
    /** Debug: send typed text instead of a recognized recording. */
    val onSubmitText: (String) -> Unit,
    val onTalkStart: () -> Unit,
    val onTalkEnd: () -> Unit,
    val onVolumeKeyEnabled: (Boolean) -> Unit,
    val onMicGain: (Float) -> Unit,
    val onMicAutoLevel: (Boolean) -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
)

/** Push-to-talk with the phone's microphone. */
data class PhoneMicState(
    val talking: Boolean,
    val volumeKeyEnabled: Boolean,
    /** Accessibility service on: the volume key works outside the app too. */
    val serviceEnabled: Boolean,
    val gain: Float = 3f,
    val autoLevel: Boolean = true,
    val autoLevelAvailable: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingScreen(
    state: RingUiState,
    found: List<FoundRing>,
    scanning: Boolean,
    scanError: String?,
    playing: File?,
    transcriptionStatus: Map<String, TranscriptionStatus>,
    transcriptionSupported: Boolean,
    aiStatus: Map<String, TranscriptionStatus>,
    aiConfig: AiConfig,
    symconViews: Map<String, SymconView>,
    lastTestEntry: String?,
    phoneMic: PhoneMicState,
    notes: List<Note>,
    noteTriggers: List<String>,
    notesActions: NotesActions,
    actions: RingScreenActions,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val openNotes = notes.count { !it.shared }
    Scaffold(topBar = { TopAppBar(title = { Text("Ring Notes") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ring") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Aufnahmen (${state.recordings.size})") })
                Tab(
                    selected = tab == 2, onClick = { tab = 2 },
                    text = { Text(if (openNotes > 0) "Notizen ($openNotes)" else "Notizen") },
                )
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("KI") })
                Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Log") })
            }
            when (tab) {
                0 -> RingTab(state, found, scanning, scanError, phoneMic, actions)
                1 -> RecordingsTab(
                    state.recordings, playing, transcriptionStatus, transcriptionSupported, aiStatus, aiConfig,
                    symconViews, notes.mapNotNull { it.recording }.toSet(), actions,
                )
                2 -> NotesTab(notes, noteTriggers, notesActions)
                3 -> AiSettingsTab(aiConfig, actions.onUpdateAi) {
                    TestInputPanel(
                        config = aiConfig,
                        lastEntry = state.recordings.firstOrNull { it.file.path == lastTestEntry },
                        aiStatus = aiStatus,
                        symconViews = symconViews,
                        actions = actions,
                    )
                }
                else -> LogTab(state)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingTab(
    state: RingUiState,
    found: List<FoundRing>,
    scanning: Boolean,
    scanError: String?,
    phoneMic: PhoneMicState,
    actions: RingScreenActions,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.deviceName ?: "Kein Ring gewählt", style = MaterialTheme.typography.titleLarge)
                    state.deviceAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(statusText(state), style = MaterialTheme.typography.bodyLarge)
                    if (state.micOn) Text("Mikrofon aktiv", color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.wantConnected) {
                            OutlinedButton(onClick = actions.onDisconnect) { Text("Trennen") }
                        } else if (state.deviceAddress != null) {
                            Button(onClick = actions.onConnectLast) { Text("Verbinden") }
                        }
                        if (state.link == LinkState.CONNECTED) {
                            if (state.locked) {
                                OutlinedButton(onClick = actions.onUnlock) { Text("Entsperren") }
                            } else {
                                OutlinedButton(onClick = actions.onLock) { Text("Sperren") }
                            }
                            OutlinedButton(onClick = actions.onRequestBattery) { Text("Akku abfragen") }
                        }
                    }
                }
            }
        }

        item { PhoneMicCard(phoneMic, actions) }

        state.recording?.let { rec ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("● Aufnahme ${formatDuration(rec.durationMs)}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { rec.level }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (scanning) {
                        OutlinedButton(onClick = actions.onStopScan) { Text("Suche stoppen") }
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else {
                        Button(onClick = actions.onScan) { Text("Ringe suchen") }
                    }
                }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!scanning && found.isEmpty()) {
                    Text(
                        "Ring einschalten und in die Nähe halten, dann suchen. Tippe auf einen Treffer, um zu verbinden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        items(found, key = { it.address }) { ring ->
            Card(Modifier.fillMaxWidth().clickable { actions.onConnect(ring) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(ring.name ?: "Unbenannter Ring", style = MaterialTheme.typography.titleMedium)
                    Text("${ring.address} · ${ring.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RecordingsTab(
    recordings: List<Recording>,
    playing: File?,
    transcriptionStatus: Map<String, TranscriptionStatus>,
    transcriptionSupported: Boolean,
    aiStatus: Map<String, TranscriptionStatus>,
    aiConfig: AiConfig,
    symconViews: Map<String, SymconView>,
    /** Names of recordings that were kept as notes to self. */
    noteRecordings: Set<String>,
    actions: RingScreenActions,
) {
    var toDelete by remember { mutableStateOf<Recording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    if (recordings.isEmpty()) {
        Text(
            "Noch keine Aufnahmen. Starte eine Aufnahme am Ring – sie wird hier automatisch als WAV gespeichert.",
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Alle ${recordings.size} Aufnahmen löschen?") },
            text = {
                Text(
                    "Audio, Transkripte und KI-/Smarthome-Antworten werden gelöscht. " +
                        "Gespeicherte Notizen bleiben erhalten. Das lässt sich nicht rückgängig machen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteAll()
                    confirmDeleteAll = false
                }) { Text("Alle löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { confirmDeleteAll = true }) {
                    Text("Alle Aufnahmen löschen", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        items(recordings, key = { it.file.path }) { rec ->
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${formatDuration(rec.durationMs)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rec.createdAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { actions.onPlay(rec) }) {
                        if (playing == rec.file) {
                            Icon(Icons.Default.Close, contentDescription = "Stopp")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Abspielen")
                        }
                    }
                    IconButton(onClick = { actions.onShare(rec) }) { Icon(Icons.Default.Share, contentDescription = "Teilen") }
                    IconButton(onClick = { toDelete = rec }) { Icon(Icons.Default.Delete, contentDescription = "Löschen") }
                }
                Transcript(rec, transcriptionStatus[rec.file.path], transcriptionSupported) { actions.onTranscribe(rec) }
                val symcon = symconViews[rec.file.path]
                when {
                    symcon != null -> SymconCard(
                        view = symcon,
                        onRefresh = { actions.onSymconRefresh(rec) },
                        onAnswer = { optionId, text -> actions.onSymconAnswer(rec, optionId, text) },
                    )
                    rec.name in noteRecordings -> Text(
                        "📝 Als Notiz gespeichert (Tab „Notizen“)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    else -> AiReply(rec, aiStatus[rec.file.path], aiConfig) { actions.onSendToAi(rec) }
                }
                HorizontalDivider()
            }
        }
    }
    toDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Aufnahme löschen?") },
            text = { Text(rec.name) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(rec)
                    toDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Transcript(
    rec: Recording,
    status: TranscriptionStatus?,
    supported: Boolean,
    onTranscribe: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
    when {
        status == TranscriptionStatus.Running ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Wird transkribiert …", style = MaterialTheme.typography.bodySmall)
            }
        status == TranscriptionStatus.Queued ->
            Text("Wartet auf Transkription …", style = MaterialTheme.typography.bodySmall, modifier = modifier)
        status is TranscriptionStatus.Failed ->
            Column(modifier) {
                Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onTranscribe) { Text("Erneut versuchen") }
            }
        rec.transcript != null ->
            SelectionContainer(modifier) {
                Text(
                    rec.transcript.ifBlank { "(keine Sprache erkannt)" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        supported -> TextButton(onClick = onTranscribe, modifier = Modifier.padding(start = 4.dp)) { Text("Transkribieren") }
    }
}

/**
 * Debug input standing in for speech recognition: the text runs through the same
 * forwarding as a transcript, and the latest result is shown right below.
 */
@Composable
private fun TestInputPanel(
    config: AiConfig,
    lastEntry: Recording?,
    aiStatus: Map<String, TranscriptionStatus>,
    symconViews: Map<String, SymconView>,
    actions: RingScreenActions,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Testeingabe (statt Spracherkennung)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("z. B. Schalte den Sternenhimmel für zwei Minuten ein") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = config.isReady && text.isNotBlank(),
                    onClick = {
                        actions.onSubmitText(text)
                        text = ""
                    },
                ) { Text("Senden") }
                if (!config.isReady) {
                    Text("Ziel erst unten einrichten", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Wird wie ein Transkript behandelt und beantwortet auch offene Rückfragen. " +
                    "Erscheint als „test-…“ unter Aufnahmen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (lastEntry != null) {
        Text("Letzte Testeingabe: „${lastEntry.transcript.orEmpty()}“", style = MaterialTheme.typography.bodySmall)
        val symcon = symconViews[lastEntry.file.path]
        if (symcon != null) {
            SymconCard(
                view = symcon,
                onRefresh = { actions.onSymconRefresh(lastEntry) },
                onAnswer = { optionId, answer -> actions.onSymconAnswer(lastEntry, optionId, answer) },
            )
        } else {
            AiReply(lastEntry, aiStatus[lastEntry.file.path], config) { actions.onSendToAi(lastEntry) }
        }
    }
}

@Composable
private fun AiReply(rec: Recording, status: TranscriptionStatus?, config: AiConfig, onSend: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
    val canSend = config.isReady && !rec.transcript.isNullOrBlank()
    when {
        status == TranscriptionStatus.Running ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Wird an die KI gesendet …", style = MaterialTheme.typography.bodySmall)
            }
        status is TranscriptionStatus.Failed ->
            Column(modifier) {
                Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (canSend) TextButton(onClick = onSend) { Text("Erneut senden") }
            }
        rec.aiReply != null ->
            Card(
                modifier,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                SelectionContainer(Modifier.padding(12.dp)) {
                    Text(rec.aiReply, style = MaterialTheme.typography.bodyMedium)
                }
            }
        canSend -> TextButton(onClick = onSend, modifier = Modifier.padding(start = 4.dp)) {
            Text(if (config.target == AiTarget.SYMCON) "An Smarthome senden" else "An KI senden")
        }
    }
}

@Composable
private fun LogTab(state: RingUiState) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(state.log) { entry ->
            Text(
                "${format.format(Date(entry.time))}  ${entry.text}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
