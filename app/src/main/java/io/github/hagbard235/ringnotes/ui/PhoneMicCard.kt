package io.github.hagbard235.ringnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import io.github.hagbard235.ringnotes.phone.PhoneRecorder
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Talk through the phone's microphone: hold the button, or hold volume-down. */
@Composable
fun PhoneMicCard(mic: PhoneMicState, actions: RingScreenActions) {
    val current by rememberUpdatedState(actions)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sprechen übers Handy", style = MaterialTheme.typography.titleMedium)
            val color = if (mic.talking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(color)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                current.onTalkStart()
                                tryAwaitRelease()
                                current.onTalkEnd()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (mic.talking) "● Aufnahme – loslassen zum Senden" else "Gedrückt halten zum Sprechen",
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text("Mikrofon-Verstärkung: ${"%.1f".format(mic.gain)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = mic.gain,
                onValueChange = current.onMicGain,
                valueRange = PhoneRecorder.MIN_GAIN..PhoneRecorder.MAX_GAIN,
                steps = 13,
            )
            if (mic.autoLevelAvailable) {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = mic.autoLevel,
                        onValueChange = current.onMicAutoLevel,
                        role = Role.Switch,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Automatische Pegelanpassung und Rauschunterdrückung", modifier = Modifier.weight(1f))
                    Switch(checked = mic.autoLevel, onCheckedChange = null)
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = mic.volumeKeyEnabled,
                    onValueChange = actions.onVolumeKeyEnabled,
                    role = Role.Switch,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Leiser-Taste gedrückt halten = Sprechen", modifier = Modifier.weight(1f))
                Switch(checked = mic.volumeKeyEnabled, onCheckedChange = null)
            }
            if (mic.volumeKeyEnabled) {
                Text(
                    if (mic.serviceEnabled) {
                        "Funktioniert überall bei eingeschaltetem Bildschirm (Bedienungshilfe aktiv). " +
                            "Kurz drücken regelt weiterhin leiser."
                    } else {
                        "Funktioniert, solange Ring Notes geöffnet ist. Für andere Apps die Bedienungshilfe " +
                            "„Ring Notes: Sprechen mit Leiser-Taste“ einschalten."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = actions.onOpenAccessibilitySettings) {
                    Text(if (mic.serviceEnabled) "Bedienungshilfe verwalten" else "Bedienungshilfe einschalten")
                }
            }
        }
    }
}
