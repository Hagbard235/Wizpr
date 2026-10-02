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

    /** Called on the ring thread with each newly saved WAV file. */
    var onRecordingSaved: ((File) -> Unit)? = null

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
    // End-of-recording detection (see checkRecordingEnd).
    private var lastAudioAt = 0L
    /** Last time a packet carried sound above the silence threshold. */
    private var lastLoudAt = 0L
    /** Loudest packet so far in this recording (raw PCM), to set the silence threshold. */
    private var speechPeak = 0
    private var stopSignalAt = 0L
    private var stopReason = ""
    private var packetsAfterStop = 0
    private var droppedAudioPackets = 0
    private val endWatch = Runnable { checkRecordingEnd() }
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

    /** Add a line to the event log from elsewhere in the app. */
    fun logNote(text: String) {
        handler.post { log(text) }
    }

    fun requestBattery() {
        handler.post { gatt?.send(OperationCommand.BATTERY_STATUS) }
    }

    fun refreshRecordings() {
        handler.post { _state.update { it.copy(recordings = store.list()) } }
    }

    /**
     * Delete every recording with its transcript and replies, except files still
     * being written (the ring's current recording and anything in [keep]).
     * Notes to self are stored separately and stay.
     */
    fun deleteAll(keep: Set<File> = emptySet()) {
        handler.post {
            val skip = keep + listOfNotNull(writerFile)
            val victims = store.list().filterNot { it.file in skip }
            victims.forEach(store::delete)
            _state.update { it.copy(recordings = store.list()) }
            log("${victims.size} Aufnahmen gelöscht")
        }
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
        if (droppedAudioPackets > 0) {
            log("$droppedAudioPackets Audiopakete außerhalb einer Aufnahme verworfen")
            droppedAudioPackets = 0
        }
        when (event) {
            RingEvent.RecordingStarted -> startRecording()
            RingEvent.RecordingStopped -> onStopSignal("Stoppsignal")
            RingEvent.MicOn -> {
                _state.update { it.copy(micOn = true) }
                // Mic came back before the ring stopped the transfer: the user is still talking.
                if (stopSignalAt != 0L && stopReason == MIC_OFF_REASON) stopSignalAt = 0L
            }
            RingEvent.MicOff -> {
                _state.update { it.copy(micOn = false) }
                onStopSignal(MIC_OFF_REASON)
            }
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
        lastAudioAt = SystemClock.elapsedRealtime()
        lastLoudAt = lastAudioAt
        speechPeak = 0
        stopSignalAt = 0L
        packetsAfterStop = 0
        handler.removeCallbacks(endWatch)
        handler.postDelayed(endWatch, END_CHECK_MS)
        _state.update { it.copy(recording = ActiveRecording(file, recordingStartedAt, 0, 0f)) }
    }

    private fun handleAudio(pcm: ShortArray) {
        if (state.value.locked) return
        val w = writer
        if (w == null) {
            droppedAudioPackets++
            return
        }
        lastAudioAt = SystemClock.elapsedRealtime()
        if (stopSignalAt != 0L) packetsAfterStop++
        try {
            w.append(pcm)
        } catch (e: IOException) {
            log("Schreibfehler: ${e.message}")
            finishRecording()
            return
        }
        var packetPeak = 0
        for (s in pcm) packetPeak = maxOf(packetPeak, abs(s.toInt()))
        peak = maxOf(peak, packetPeak)
        speechPeak = maxOf(speechPeak, packetPeak)
        val now = SystemClock.elapsedRealtime()
        // Relative to the loudest speech so far, so a noisy room does not count as talking.
        if (packetPeak >= maxOf(SILENCE_FLOOR, speechPeak / SILENCE_RATIO)) lastLoudAt = now
        if (now - lastLevelUpdate >= LEVEL_UPDATE_MS) {
            lastLevelUpdate = now
            val level = (peak * WAV_GAIN / Short.MAX_VALUE).coerceAtMost(1f)
            peak = 0
            _state.update { s ->
                s.copy(recording = s.recording?.copy(durationMs = w.durationMs, level = level))
            }
        }
    }

    /** The ring signalled the end (transfer stop or mic off); the file closes once the audio has drained. */
    private fun onStopSignal(reason: String) {
        if (writer == null || stopSignalAt != 0L) return
        stopSignalAt = SystemClock.elapsedRealtime()
        stopReason = reason
    }

    /**
     * Decides when a recording is over. The ring's stop signal alone is not
     * enough: buffered audio can keep arriving after it, and sometimes the
     * signal never comes. So a recording ends when
     *  - a stop signal (transfer stop or mic off) arrived and no audio came for
     *    [QUIET_AFTER_STOP_MS] (or [MAX_DRAIN_MS] passed since the signal), or
     *  - no audio arrived for [IDLE_TIMEOUT_MS] even without a stop signal.
     * Packets alone are not reliable either: the ring may keep streaming
     * silence after the mic went off. So the level counts too:
     *  - after a stop signal, [SILENT_AFTER_STOP_MS] without sound ends it,
     *  - without one, [SILENCE_TIMEOUT_MS] of silence ends it,
     *  - and nothing runs longer than [MAX_RECORDING_MS].
     */
    private fun checkRecordingEnd() {
        val w = writer ?: return
        val now = SystemClock.elapsedRealtime()
        val quietFor = now - lastAudioAt
        val silentFor = now - lastLoudAt
        val stopped = stopSignalAt != 0L
        val reason = when {
            stopped && quietFor >= QUIET_AFTER_STOP_MS -> "$stopReason, $packetsAfterStop Pakete danach"
            stopped && now - maxOf(stopSignalAt, lastLoudAt) >= SILENT_AFTER_STOP_MS ->
                "$stopReason, danach nur Stille ($packetsAfterStop Pakete)"
            stopped && now - stopSignalAt >= MAX_DRAIN_MS -> "$stopReason, Ton lief weiter ($packetsAfterStop Pakete)"
            !stopped && quietFor >= IDLE_TIMEOUT_MS -> "kein Stoppsignal, Audio verstummt"
            !stopped && silentFor >= SILENCE_TIMEOUT_MS -> "kein Stoppsignal, ${silentFor / 1000} s Stille"
            w.durationMs >= MAX_RECORDING_MS -> "Höchstdauer ${MAX_RECORDING_MS / 1000} s erreicht"
            else -> null
        }
        if (reason != null) finishRecording(reason) else handler.postDelayed(endWatch, END_CHECK_MS)
    }

    private fun finishRecording(reason: String? = null) {
        handler.removeCallbacks(endWatch)
        stopSignalAt = 0L
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
            val why = reason?.let { "; Ende: $it" } ?: ""
            log("Gespeichert: ${file?.name} (${"%.1f".format(w.durationMs / 1000f)} s$why)")
            file?.let { onRecordingSaved?.invoke(it) }
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
        const val MIC_OFF_REASON = "Mikrofon aus"
        const val END_CHECK_MS = 100L
        const val QUIET_AFTER_STOP_MS = 700L
        const val MAX_DRAIN_MS = 5_000L
        const val IDLE_TIMEOUT_MS = 3_000L
        const val SILENT_AFTER_STOP_MS = 800L
        const val SILENCE_TIMEOUT_MS = 5_000L
        const val MAX_RECORDING_MS = 120_000L
        /** Raw PCM peak below which a packet always counts as silence. */
        const val SILENCE_FLOOR = 300
        /** A packet this many times quieter than the loudest speech counts as silence. */
        const val SILENCE_RATIO = 8
        const val RECONNECT_DELAY_MS = 1_000L
        const val BATTERY_POLL_MS = 5 * 60_000L
        const val LEVEL_UPDATE_MS = 100L
        const val MAX_LOG = 100
    }
}
