package io.github.hagbard235.ringnotes

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.github.hagbard235.ringnotes.ble.RingGattClient
import io.github.hagbard235.ringnotes.core.NotificationDispatcher
import io.github.hagbard235.ringnotes.core.NotificationDispatcher.ClickTimer
import io.github.hagbard235.ringnotes.core.OperationCommand
import io.github.hagbard235.ringnotes.core.RingEvent
import io.github.hagbard235.ringnotes.core.StreamingWavWriter
import io.github.hagbard235.ringnotes.recording.Recording
import io.github.hagbard235.ringnotes.recording.RecordingStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkState { DISCONNECTED, CONNECTING, WAITING_FOR_RING, CONNECTED }

data class LogEntry(val time: Long, val text: String)

data class ActiveRecording(val file: File, val startedAt: Long, val durationMs: Long, val level: Float)

data class RingUiState(
    val link: LinkState = LinkState.DISCONNECTED,
    /** True while the user wants a connection (the foreground service stays up). */
    val wantConnected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val battery: RingEvent.BatteryUpdate? = null,
    val micOn: Boolean = false,
    val locked: Boolean = false,
    val recording: ActiveRecording? = null,
    val recordings: List<Recording> = emptyList(),
    val log: List<LogEntry> = emptyList(),
)

/**
 * App-wide ring session: owns the GATT client, turns notifications into events
 * and WAV recordings, and reconnects when the ring drops out of range.
 *
 * Everything runs on one background [HandlerThread]; the UI observes [state].
 */
class RingController(private val context: Context) : RingGattClient.Listener {
    private val thread = HandlerThread("ring").apply { start() }
    private val handler = Handler(thread.looper)
    private val prefs = context.getSharedPreferences("ring", Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val store = RecordingStore(context)

    private val _state = MutableStateFlow(
        RingUiState(
            deviceName = prefs.getString(KEY_NAME, null),
            deviceAddress = prefs.getString(KEY_ADDRESS, null),
            recordings = store.list(),
        ),
    )
    val state: StateFlow<RingUiState> = _state.asStateFlow()

    private var gatt: RingGattClient? = null
    private var device: BluetoothDevice? = null
    private var dispatcher = NotificationDispatcher()

    private var writer: StreamingWavWriter? = null
    private var writerFile: File? = null
    private var recordingStartedAt = 0L
    private var lastLevelUpdate = 0L
    private var peak = 0

    private val clickTimeout = Runnable { dispatcher.onClickTimeout()?.let(::handleEvent) }
    private val finalizeRecording = Runnable { finishRecording() }
    private val reconnect = Runnable { device?.let { startGatt(it, autoConnect = true) } }
    private val batteryPoll = object : Runnable {
        override fun run() {
            gatt?.takeIf { it.isReady }?.send(OperationCommand.BATTERY_STATUS)
            handler.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    // ---- Public API (any thread) -------------------------------------------------------------

    fun connect(target: BluetoothDevice, name: String?) {
        // Updated synchronously so a RingService started right after this call sees it.
        _state.update { it.copy(wantConnected = true, deviceAddress = target.address, deviceName = name) }
        handler.post { connectOnRingThread(target, name) }
    }

    private fun connectOnRingThread(target: BluetoothDevice, name: String?) {
        prefs.edit().putString(KEY_ADDRESS, target.address).putString(KEY_NAME, name).apply()
        gatt?.disconnect()
        handler.removeCallbacks(reconnect)
        device = target
        startGatt(target, autoConnect = false)
    }

    /** Reconnect to the last ring without scanning; false when none is remembered. */
    fun connectLast(): Boolean {
        val address = state.value.deviceAddress ?: return false
        val target = adapter?.getRemoteDevice(address) ?: return false
        connect(target, state.value.deviceName)
        return true
    }

    fun disconnect() {
        _state.update { it.copy(wantConnected = false) }
        handler.post { disconnectOnRingThread() }
    }

    private fun disconnectOnRingThread() {
        handler.removeCallbacks(reconnect)
        gatt?.disconnect()
        gatt = null
        setLink(LinkState.DISCONNECTED)
    }

    /**
     * Send the ring's LOCK operation and gate input on the host side, as the SDK
     * requires: while locked, recording starts and audio are ignored.
     */
    fun lock() {
        handler.post {
            gatt?.send(OperationCommand.LOCK)
            finishRecording()
            _state.update { it.copy(locked = true) }
            log("Ring gesperrt")
        }
    }

    /** Unlocking is a purely host-side state change (there is no UNLOCK command). */
    fun unlock() {
        handler.post {
            _state.update { it.copy(locked = false) }
            log("Ring entsperrt")
        }
    }

    fun requestBattery() {
        handler.post { gatt?.send(OperationCommand.BATTERY_STATUS) }
    }

    fun refreshRecordings() {
        handler.post { _state.update { it.copy(recordings = store.list()) } }
    }

    fun delete(recording: Recording) {
        handler.post {
            store.delete(recording)
            _state.update { it.copy(recordings = store.list()) }
        }
    }

    // ---- GATT listener (ring thread) ----------------------------------------------------------

    override fun onReady() {
        setLink(LinkState.CONNECTED)
        // The host lock does not survive a reconnect (see SDK README).
        _state.update { it.copy(locked = false, micOn = false) }
        log("Verbunden")
        handler.removeCallbacks(batteryPoll)
        handler.postDelayed(batteryPoll, BATTERY_POLL_MS)
    }

    override fun onDisconnected(userInitiated: Boolean, status: Int) {
        handler.removeCallbacks(batteryPoll)
        handler.removeCallbacks(clickTimeout)
        finishRecording()
        gatt = null
        if (!userInitiated && state.value.wantConnected) {
            log("Verbindung verloren (Status $status), warte auf Ring …")
            setLink(LinkState.WAITING_FOR_RING)
            handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
        } else if (!state.value.wantConnected) {
            setLink(LinkState.DISCONNECTED)
            log("Getrennt")
        }
    }

    override fun onNotification(uuid: UUID, value: ByteArray) {
        val out = dispatcher.handle(uuid, value)
        out.audio?.let(::handleAudio)
        out.events.forEach(::handleEvent)
        when (out.clickTimer) {
            ClickTimer.ARM -> {
                handler.removeCallbacks(clickTimeout)
                handler.postDelayed(clickTimeout, NotificationDispatcher.DOUBLE_CLICK_WINDOW_MS)
            }
            ClickTimer.DISARM -> handler.removeCallbacks(clickTimeout)
            ClickTimer.NONE -> Unit
        }
    }

    // ---- Internals (ring thread) ---------------------------------------------------------------

    private fun startGatt(target: BluetoothDevice, autoConnect: Boolean) {
        dispatcher = NotificationDispatcher()
        setLink(if (autoConnect) LinkState.WAITING_FOR_RING else LinkState.CONNECTING)
        try {
            gatt = RingGattClient(context, handler, this).also { it.connect(target, autoConnect) }
        } catch (e: SecurityException) {
            log("Bluetooth-Berechtigung fehlt")
            _state.update { it.copy(wantConnected = false) }
            setLink(LinkState.DISCONNECTED)
        }
    }

    private fun handleEvent(event: RingEvent) {
        when (event) {
            RingEvent.RecordingStarted -> startRecording()
            RingEvent.RecordingStopped -> onRecordingStopped()
            RingEvent.MicOn -> _state.update { it.copy(micOn = true) }
            RingEvent.MicOff -> _state.update { it.copy(micOn = false) }
            is RingEvent.BatteryUpdate -> _state.update { it.copy(battery = event) }
            else -> Unit
        }
        log(describe(event))
    }

    private fun startRecording() {
        if (state.value.locked) {
            log("Aufnahme ignoriert (gesperrt)")
            return
        }
        finishRecording()
        val file = store.newFile()
        try {
            writer = StreamingWavWriter(file, gain = WAV_GAIN)
        } catch (e: IOException) {
            log("Kann ${file.name} nicht anlegen: ${e.message}")
            return
        }
        writerFile = file
        recordingStartedAt = System.currentTimeMillis()
        peak = 0
        _state.update { it.copy(recording = ActiveRecording(file, recordingStartedAt, 0, 0f)) }
    }

    private fun handleAudio(pcm: ShortArray) {
        if (state.value.locked) return
        val w = writer ?: return
        try {
            w.append(pcm)
        } catch (e: IOException) {
            log("Schreibfehler: ${e.message}")
            finishRecording()
            return
        }
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelUpdate >= LEVEL_UPDATE_MS) {
            lastLevelUpdate = now
            val level = (peak * WAV_GAIN / Short.MAX_VALUE).coerceAtMost(1f)
            peak = 0
            _state.update { s ->
                s.copy(recording = s.recording?.copy(durationMs = w.durationMs, level = level))
            }
        }
    }

    /** Keep collecting for a short drain window, like the SDK's desktop example. */
    private fun onRecordingStopped() {
        if (writer == null) return
        handler.removeCallbacks(finalizeRecording)
        handler.postDelayed(finalizeRecording, RECORDING_DRAIN_MS)
    }

    private fun finishRecording() {
        handler.removeCallbacks(finalizeRecording)
        val w = writer ?: return
        val file = writerFile
        writer = null
        writerFile = null
        try {
            w.close()
        } catch (e: Exception) {
            Log.e(TAG, "Closing recording failed", e)
        }
        if (w.sampleCount == 0L) {
            file?.delete()
        } else {
            log("Gespeichert: ${file?.name} (${"%.1f".format(w.durationMs / 1000f)} s)")
        }
        _state.update { it.copy(recording = null, recordings = store.list()) }
    }

    private fun setLink(link: LinkState) = _state.update { it.copy(link = link) }

    private fun log(text: String) {
        Log.i(TAG, text)
        _state.update { s -> s.copy(log = (listOf(LogEntry(System.currentTimeMillis(), text)) + s.log).take(MAX_LOG)) }
    }

    private fun describe(event: RingEvent): String = when (event) {
        RingEvent.MicOn -> "Mikrofon an"
        RingEvent.MicOff -> "Mikrofon aus"
        RingEvent.RecordingStarted -> "Aufnahme gestartet"
        RingEvent.RecordingStopped -> "Aufnahme beendet"
        RingEvent.Click -> "Klick"
        RingEvent.DoubleClick -> "Doppelklick"
        RingEvent.PowerOff -> "Ring schaltet ab"
        is RingEvent.BatteryUpdate -> "Akku ${event.level} % (${"%.2f".format(event.voltage)} V)"
        is RingEvent.Operation -> "Operation: ${event.raw.trim()}"
    }

    private companion object {
        const val TAG = "RingController"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val WAV_GAIN = 3.0f
        const val RECORDING_DRAIN_MS = 500L
        const val RECONNECT_DELAY_MS = 1_000L
        const val BATTERY_POLL_MS = 5 * 60_000L
        const val LEVEL_UPDATE_MS = 100L
        const val MAX_LOG = 100
    }
}
