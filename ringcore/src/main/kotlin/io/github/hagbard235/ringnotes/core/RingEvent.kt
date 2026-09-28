// Ported from wizpr-ring-core (events.rs) of https://github.com/vtouchio/wizpr-ring-sdk
// Copyright VTouch Inc., licensed under Apache-2.0. Modified: translated to Kotlin.
package io.github.hagbard235.ringnotes.core

import kotlin.math.roundToInt

/** Events emitted by the ring. */
sealed interface RingEvent {
    data object MicOn : RingEvent
    data object MicOff : RingEvent
    data object RecordingStarted : RingEvent
    data object RecordingStopped : RingEvent
    data object Click : RingEvent
    data object DoubleClick : RingEvent
    data object PowerOff : RingEvent
    data class BatteryUpdate(val voltage: Float, val level: Int) : RingEvent

    /** An operation string that is not recognized; for logging only. */
    data class Operation(val raw: String) : RingEvent
}

/**
 * Classify a single message from the operation characteristic.
 *
 * Click vs. double-click is not decided here; that needs a timer and lives in
 * [NotificationDispatcher].
 */
fun parseOperation(text: String): RingEvent {
    if ("POWER_OFF" in text) return RingEvent.PowerOff
    if ("MIC_ON" in text) return RingEvent.MicOn
    if ("MIC_OFF" in text) return RingEvent.MicOff

    parseBatteryVoltage(text)?.let { voltage ->
        return RingEvent.BatteryUpdate(voltage, batteryVoltageToPercent(voltage))
    }
    return RingEvent.Operation(text)
}

private fun parseBatteryVoltage(text: String): Float? {
    val batt = text.indexOf("BATT=")
    if (batt >= 0) return parseFloatPrefix(text.substring(batt + "BATT=".length))

    if ("BATTERY" in text) {
        val open = text.indexOf('(')
        if (open >= 0) {
            val rest = text.substring(open + 1)
            val close = rest.indexOf(')')
            if (close >= 0) return rest.substring(0, close).trim().toFloatOrNull()
        }
    }
    return null
}

private fun parseFloatPrefix(text: String): Float? {
    val trimmed = text.trimStart()
    val end = trimmed.indexOfFirst { !(it.isAsciiDigit() || it == '.' || it == '-' || it == '+') }
        .let { if (it < 0) trimmed.length else it }
    return trimmed.substring(0, end).toFloatOrNull()
}

private fun Char.isAsciiDigit() = this in '0'..'9'

internal fun batteryVoltageToPercent(voltage: Float): Int {
    if (voltage <= 0f) return 0
    val (maxVoltage, maxPercent) = BATTERY_VOLTAGE_TABLE.first()
    if (voltage >= maxVoltage) return maxPercent
    val (minVoltage, minPercent) = BATTERY_VOLTAGE_TABLE.last()
    if (voltage <= minVoltage) return minPercent

    for ((current, next) in BATTERY_VOLTAGE_TABLE.zipWithNext()) {
        val (currentVoltage, currentPercent) = current
        val (nextVoltage, nextPercent) = next
        if (voltage <= currentVoltage && voltage >= nextVoltage) {
            val voltageRange = currentVoltage - nextVoltage
            val percentRange = (currentPercent - nextPercent).toFloat()
            val offset = currentVoltage - voltage
            val interpolated = currentPercent - (offset / voltageRange) * percentRange
            return interpolated.roundToInt().coerceIn(0, 100)
        }
    }
    return 50
}

// Battery discharge curve (voltage → percent), identical to the SDK.
private val BATTERY_VOLTAGE_TABLE: List<Pair<Float, Int>> = listOf(
    3.740f to 100, 3.730f to 97, 3.720f to 95, 3.710f to 94, 3.700f to 91,
    3.690f to 90, 3.680f to 87, 3.670f to 85, 3.660f to 84, 3.650f to 81,
    3.640f to 78, 3.630f to 77, 3.620f to 75, 3.610f to 72, 3.600f to 71,
    3.590f to 68, 3.580f to 67, 3.570f to 65, 3.560f to 62, 3.550f to 61,
    3.540f to 58, 3.530f to 56, 3.520f to 54, 3.510f to 52, 3.500f to 49,
    3.490f to 48, 3.480f to 46, 3.470f to 44, 3.460f to 42, 3.450f to 39,
    3.440f to 38, 3.430f to 37, 3.420f to 35, 3.410f to 34, 3.400f to 32,
    3.390f to 30, 3.380f to 29, 3.370f to 28, 3.360f to 27, 3.350f to 25,
    3.340f to 24, 3.330f to 22, 3.320f to 19, 3.310f to 16, 3.300f to 15,
    3.290f to 13, 3.280f to 13, 3.270f to 11, 3.260f to 11, 3.250f to 11,
    3.240f to 10, 3.230f to 10, 3.220f to 10, 3.210f to 10, 3.200f to 9,
    3.190f to 9, 3.180f to 9, 3.170f to 9, 3.160f to 8, 3.150f to 8,
    3.140f to 8, 3.130f to 8, 3.120f to 6, 3.110f to 6, 3.100f to 6,
    3.090f to 6, 3.080f to 5, 3.070f to 5, 3.060f to 5, 3.050f to 5,
    3.040f to 4, 3.030f to 4, 3.020f to 4, 3.010f to 4, 3.000f to 4,
    2.990f to 4, 2.980f to 3, 2.970f to 3, 2.960f to 3, 2.950f to 3,
    2.940f to 1, 2.930f to 1, 2.920f to 1, 2.910f to 1, 2.900f to 0,
)
