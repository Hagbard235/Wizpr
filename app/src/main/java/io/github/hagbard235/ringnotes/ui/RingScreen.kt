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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import io.github.hagbard235.ringnotes.ble.FoundRing
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.statusText
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingScreen(
    state: RingUiState,
    found: List<FoundRing>,
    scanning: Boolean,
    scanError: String?,
    playing: File?,
    actions: RingScreenActions,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(topBar = { TopAppBar(title = { Text("Ring Notes") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ring") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Aufnahmen (${state.recordings.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Log") })
            }
            when (tab) {
                0 -> RingTab(state, found, scanning, scanError, actions)
                1 -> RecordingsTab(state.recordings, playing, actions)
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
private fun RecordingsTab(recordings: List<Recording>, playing: File?, actions: RingScreenActions) {
    var toDelete by remember { mutableStateOf<Recording?>(null) }
    if (recordings.isEmpty()) {
        Text(
            "Noch keine Aufnahmen. Starte eine Aufnahme am Ring – sie wird hier automatisch als WAV gespeichert.",
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(recordings, key = { it.file.path }) { rec ->
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
            HorizontalDivider()
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
