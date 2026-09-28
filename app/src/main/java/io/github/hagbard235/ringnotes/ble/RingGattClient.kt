package io.github.hagbard235.ringnotes.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import io.github.hagbard235.ringnotes.core.OperationCommand
import io.github.hagbard235.ringnotes.core.WizprBle
import java.util.ArrayDeque
import java.util.UUID

/**
 * One GATT connection to a ring.
 *
 * Mirrors `RingConnection::open` from the Rust SDK: connect, discover the ring
 * service, subscribe to audio / transfer-status / operation notifications, then
 * send `sample_rate 16` and a battery request.
 *
 * Android allows only one outstanding GATT operation at a time, so writes and
 * descriptor writes go through a small serial queue. Every callback is
 * re-posted to [handler] so all state lives on a single thread; public methods
 * must also be called on that thread.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before connecting.
class RingGattClient(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** Notifications are enabled and the ring is configured. */
        fun onReady()

        /** The link dropped or setup failed. [userInitiated] is true after [disconnect]. */
        fun onDisconnected(userInitiated: Boolean, status: Int)

        fun onNotification(uuid: UUID, value: ByteArray)
    }

    private sealed interface Op {
        data class EnableNotify(val characteristic: BluetoothGattCharacteristic) : Op
        data class Write(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : Op
    }

    private var gatt: BluetoothGatt? = null
    private var operationChar: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private var ready = false
    private var closing = false
    private val opTimeout = Runnable {
        Log.w(TAG, "GATT operation timed out")
        completeOp()
    }

    val isReady: Boolean get() = ready

    /** Start connecting. With [autoConnect] Android waits (without timeout) until the ring is in range. */
    fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        check(gatt == null) { "already connected" }
        closing = false
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt ?: return
        closing = true
        g.disconnect()
        // onConnectionStateChange may never arrive for a pending autoConnect, so close eagerly.
        teardown()
        listener.onDisconnected(userInitiated = true, status = BluetoothGatt.GATT_SUCCESS)
    }

    fun send(command: OperationCommand) {
        val c = operationChar ?: return
        enqueue(Op.Write(c, command.bytes()))
    }

    private fun teardown() {
        handler.removeCallbacks(opTimeout)
        queue.clear()
        busy = false
        ready = false
        operationChar = null
        gatt?.close()
        gatt = null
    }

    private fun onServicesDiscovered(g: BluetoothGatt) {
        val service = g.getService(WizprBle.SERVICE)
        val audio = service?.getCharacteristic(WizprBle.AUDIO_CHAR)
        val transfer = service?.getCharacteristic(WizprBle.TRANSFER_STATUS_CHAR)
        val operation = service?.getCharacteristic(WizprBle.OPERATION_CHAR)
        if (audio == null || transfer == null || operation == null) {
            Log.e(TAG, "Ring service or characteristic missing")
            fail(STATUS_MISSING_CHARACTERISTIC)
            return
        }
        operationChar = operation
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        enqueue(Op.EnableNotify(audio))
        enqueue(Op.EnableNotify(transfer))
        enqueue(Op.EnableNotify(operation))
        enqueue(Op.Write(operation, OperationCommand.SAMPLE_RATE_16.bytes()))
        enqueue(Op.Write(operation, OperationCommand.BATTERY_STATUS.bytes()))
    }

    private fun fail(status: Int) {
        if (gatt == null) return
        gatt?.disconnect()
        teardown()
        listener.onDisconnected(userInitiated = closing, status = status)
    }

    private fun enqueue(op: Op) {
        queue.addLast(op)
        drive()
    }

    private fun drive() {
        if (busy) return
        val g = gatt ?: return
        val op = queue.pollFirst()
        if (op == null) {
            if (!ready && operationChar != null) {
                ready = true
                listener.onReady()
            }
            return
        }
        val started = when (op) {
            is Op.EnableNotify -> {
                g.setCharacteristicNotification(op.characteristic, true)
                val cccd = op.characteristic.getDescriptor(WizprBle.CCCD)
                if (cccd == null) false else writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            is Op.Write -> writeCharacteristic(g, op.characteristic, op.value)
        }
        if (!started) {
            Log.w(TAG, "Could not start $op")
            if (op is Op.EnableNotify) {
                fail(STATUS_SUBSCRIBE_FAILED)
            } else {
                drive()
            }
            return
        }
        busy = true
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
    }

    private fun completeOp() {
        handler.removeCallbacks(opTimeout)
        busy = false
        drive()
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = value
            g.writeCharacteristic(c)
        }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected, requesting MTU")
                    if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Disconnected (status=$status)")
                    val userInitiated = closing
                    teardown()
                    listener.onDisconnected(userInitiated, status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                Log.i(TAG, "MTU $mtu (status=$status)")
                if (!g.discoverServices()) fail(STATUS_DISCOVERY_FAILED)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) onServicesDiscovered(g) else fail(status)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Subscribing to ${d.characteristic.uuid} failed: $status")
                    fail(status)
                    return@post
                }
                completeOp()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write to ${c.uuid} failed: $status")
                completeOp()
            }
        }

        // API 33+: value is delivered directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(g, c.uuid, value)
        }

        // API < 33: value lives on the characteristic and must be copied before the next packet arrives.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            deliver(g, c.uuid, c.value?.copyOf() ?: return)
        }

        private fun deliver(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
            handler.post {
                if (g == gatt) listener.onNotification(uuid, value)
            }
        }
    }

    companion object {
        private const val TAG = "RingGatt"
        private const val REQUESTED_MTU = 247
        private const val OP_TIMEOUT_MS = 5_000L
        const val STATUS_MISSING_CHARACTERISTIC = -1
        const val STATUS_SUBSCRIBE_FAILED = -2
        const val STATUS_DISCOVERY_FAILED = -3
    }
}
