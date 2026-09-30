package io.github.hagbard235.ringnotes.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hagbard235.ringnotes.RingService
import io.github.hagbard235.ringnotes.ble.FoundRing
import io.github.hagbard235.ringnotes.ble.RingScanner
import io.github.hagbard235.ringnotes.recording.AudioPlayer
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.ringController
import io.github.hagbard235.ringnotes.transcriptions
import io.github.hagbard235.ringnotes.aiSettings
import io.github.hagbard235.ringnotes.symconJobs
import io.github.hagbard235.ringnotes.ai.AiConfig
import io.github.hagbard235.ringnotes.phoneRecorder
import io.github.hagbard235.ringnotes.crashReporter
import io.github.hagbard235.ringnotes.diagnostics.CrashReporter
import io.github.hagbard235.ringnotes.noteStore
import io.github.hagbard235.ringnotes.notes.Note
import io.github.hagbard235.ringnotes.notes.NoteStore
import android.content.ClipData
import android.content.ClipboardManager
import io.github.hagbard235.ringnotes.pushToTalk
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var scanner: RingScanner
    private val player = AudioPlayer()
    private var pendingAction: (() -> Unit)? = null
    private var volumeKeyServiceEnabled by mutableStateOf(false)
    private var pendingCrash by mutableStateOf<String?>(null)

    private fun shareDiagnosis() {
        val log = ringController.state.value.log.map { "${java.util.Date(it.time)}  ${it.text}" }
        CrashReporter.share(this, crashReporter.diagnosis(log))
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val required = requiredPermissions()
            if (required.all { granted[it] == true || hasPermission(it) }) {
                ensureBluetoothOn()
            } else {
                pendingAction = null
            }
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingTranscription
            pendingTranscription = null
            if (granted) {
                // Re-promote the running service so it gains the microphone type for background transcription.
                if (ringController.state.value.wantConnected) RingService.start(this)
                action?.invoke()
            }
        }
    private var pendingTranscription: (() -> Unit)? = null

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter()?.isEnabled == true) runPending()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scanner = RingScanner(bluetoothAdapter())
        val controller = ringController
        controller.refreshRecordings()
        pendingCrash = runCatching { crashReporter.unseenCrash() }.getOrNull()

        setContent {
            RingNotesTheme {
                pendingCrash?.let { report ->
                    CrashDialog(
                        report = report,
                        onShare = { shareDiagnosis() },
                        onCopy = {
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Absturzbericht", report))
                        },
                        onDismiss = {
                            crashReporter.markSeen()
                            pendingCrash = null
                        },
                    )
                }
                val state by controller.state.collectAsStateWithLifecycle()
                val found by scanner.results.collectAsStateWithLifecycle()
                val scanning by scanner.scanning.collectAsStateWithLifecycle()
                val scanError by scanner.error.collectAsStateWithLifecycle()
                val playing by player.playing.collectAsStateWithLifecycle()
                val transcriptionStatus by transcriptions.status.collectAsStateWithLifecycle()
                val aiStatus by transcriptions.aiStatus.collectAsStateWithLifecycle()
                val aiConfig by aiSettings.config.collectAsStateWithLifecycle()
                val symconViews by symconJobs.views.collectAsStateWithLifecycle()
                val lastTestEntry by transcriptions.lastTestEntry.collectAsStateWithLifecycle()
                val phoneTalking by phoneRecorder.active.collectAsStateWithLifecycle()
                val volumeKeyEnabled by pushToTalk.volumeKeyEnabled.collectAsStateWithLifecycle()
                val notes by noteStore.notes.collectAsStateWithLifecycle()
                val noteTriggers by noteStore.triggers.collectAsStateWithLifecycle()
                val micGain by phoneRecorder.gain.collectAsStateWithLifecycle()
                val micAutoLevel by phoneRecorder.autoLevel.collectAsStateWithLifecycle()

                RingScreen(
                    state = state,
                    found = found,
                    scanning = scanning,
                    scanError = scanError,
                    playing = playing,
                    transcriptionStatus = transcriptionStatus,
                    transcriptionSupported = transcriptions.isSupported,
                    aiStatus = aiStatus,
                    aiConfig = aiConfig,
                    symconViews = symconViews,
                    lastTestEntry = lastTestEntry,
                    phoneMic = PhoneMicState(
                        talking = phoneTalking,
                        volumeKeyEnabled = volumeKeyEnabled,
                        serviceEnabled = volumeKeyServiceEnabled,
                        gain = micGain,
                        autoLevel = micAutoLevel,
                        autoLevelAvailable = phoneRecorder.autoLevelAvailable,
                    ),
                    notes = notes,
                    noteTriggers = noteTriggers,
                    notesActions = NotesActions(
                        onShare = { list: List<Note> ->
                            if (NoteStore.shareToKeep(this, list)) noteStore.markShared(list.map { it.id })
                        },
                        onCopy = { note: Note ->
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Notiz", note.text))
                        },
                        onDelete = { note: Note -> noteStore.delete(note.id) },
                        onSetTriggers = { input: String -> noteStore.setTriggers(input) },
                    ),
                    actions = RingScreenActions(
                        onScan = { withBluetooth { scanner.start() } },
                        onStopScan = scanner::stop,
                        onConnect = { ring: FoundRing -> withBluetooth { connect(ring) } },
                        onConnectLast = { withBluetooth { if (controller.connectLast()) RingService.start(this) } },
                        onDisconnect = controller::disconnect,
                        onLock = controller::lock,
                        onUnlock = controller::unlock,
                        onRequestBattery = controller::requestBattery,
                        onPlay = { rec: Recording -> player.toggle(rec.file) },
                        onShare = ::share,
                        onTranscribe = { rec: Recording -> withMicPermission { transcriptions.enqueue(rec.file) } },
                        onSendToAi = { rec: Recording -> transcriptions.sendToAi(rec) },
                        onUpdateAi = { change: (AiConfig) -> AiConfig -> aiSettings.update(change) },
                        onSymconRefresh = { rec: Recording -> symconJobs.refresh(rec.file.path) },
                        onSymconAnswer = { rec: Recording, optionId: String?, text: String ->
                            symconJobs.answer(rec.file.path, optionId, text)
                        },
                        onSubmitText = { text: String, dryRun: Boolean -> transcriptions.submitText(text, dryRun) },
                        onTalkStart = {
                            if (phoneRecorder.hasPermission()) pushToTalk.pressStart() else withMicPermission {}
                        },
                        onTalkEnd = { pushToTalk.pressEnd() },
                        onVolumeKeyEnabled = { on: Boolean -> pushToTalk.setVolumeKeyEnabled(on) },
                        onMicGain = { g: Float -> phoneRecorder.setGain(g) },
                        onShareDiagnosis = { shareDiagnosis() },
                        onMicAutoLevel = { on: Boolean -> phoneRecorder.setAutoLevel(on) },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onDelete = { rec: Recording ->
                            if (playing == rec.file) player.stop()
                            controller.delete(rec)
                        },
                        onDeleteAll = {
                            player.stop()
                            controller.deleteAll(keep = setOfNotNull(phoneRecorder.currentFile))
                        },
                    ),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        transcriptions.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just switched the accessibility service on or off in the system settings.
        volumeKeyServiceEnabled = pushToTalk.isServiceEnabled()
    }

    /** Hold volume-down to talk while the app is in front; the volume itself stays unchanged. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 && pushToTalk.volumeKeyEnabled.value && !phoneRecorder.hasPermission()
        ) {
            withMicPermission {}
            return true
        }
        return pushToTalk.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        scanner.stop()
        transcriptions.setForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.stop()
    }

    private fun connect(ring: FoundRing) {
        scanner.stop()
        ringController.connect(ring.device, ring.name)
        RingService.start(this)
    }

    private fun share(recording: Recording) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", recording.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { recording.transcript?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) } }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, recording.name))
    }

    // ---- Permissions & Bluetooth state ---------------------------------------------------------

    private fun withBluetooth(action: () -> Unit) {
        pendingAction = action
        val missing = (requiredPermissions() + optionalPermissions()).filterNot(::hasPermission)
        if (requiredPermissions().any { !hasPermission(it) }) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            ensureBluetoothOn()
        }
    }

    /** The speech recognizer demands RECORD_AUDIO even though it only reads our WAV file. */
    private fun withMicPermission(action: () -> Unit) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            action()
        } else {
            pendingTranscription = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureBluetoothOn() {
        if (bluetoothAdapter()?.isEnabled == true) {
            runPending()
        } else {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun runPending() {
        pendingAction?.invoke()
        pendingAction = null
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun optionalPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        // Asked together with Bluetooth so new recordings can be transcribed automatically.
        if (transcriptions.isSupported) add(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter
}

@Composable
private fun RingNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
