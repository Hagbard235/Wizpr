package io.github.hagbard235.ringnotes.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import io.github.hagbard235.ringnotes.core.isRingCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FoundRing(
    val device: BluetoothDevice,
    val address: String,
    val name: String?,
    val rssi: Int,
    val advertisesService: Boolean,
)

/**
 * Time-boxed BLE scan that keeps only ring candidates, using the same rule as
 * the SDK: the ring service UUID is advertised or the name contains "WIZPR RING".
 */
@SuppressLint("MissingPermission") // The UI requests BLUETOOTH_SCAN before calling start().
class RingScanner(private val adapter: BluetoothAdapter?) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _results = MutableStateFlow<List<FoundRing>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val results: StateFlow<List<FoundRing>> = _results.asStateFlow()
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    private val stopRunnable = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            _error.value = "Scan fehlgeschlagen (Code $errorCode)"
            _scanning.value = false
        }
    }

    fun start(durationMs: Long = 15_000) {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _error.value = "Bluetooth ist ausgeschaltet"
            return
        }
        if (_scanning.value) stop()
        _error.value = null
        _results.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        _scanning.value = true
        mainHandler.postDelayed(stopRunnable, durationMs)
    }

    fun stop() {
        mainHandler.removeCallbacks(stopRunnable)
        if (!_scanning.value) return
        _scanning.value = false
        if (adapter?.isEnabled == true) adapter.bluetoothLeScanner?.stopScan(callback)
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: result.device.name
        val services = record?.serviceUuids?.map { it.uuid }.orEmpty()
        if (!isRingCandidate(name, services)) return
        val found = FoundRing(
            device = result.device,
            address = result.device.address,
            name = name,
            rssi = result.rssi,
            advertisesService = services.isNotEmpty() && isRingCandidate(null, services),
        )
        _results.update { list ->
            (list.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi }
        }
    }
}
