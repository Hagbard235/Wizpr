// Ported from wizpr-ring-core (constants.rs) of https://github.com/vtouchio/wizpr-ring-sdk
// Copyright VTouch Inc., licensed under Apache-2.0. Modified: translated to Kotlin.
package io.github.hagbard235.ringnotes.core

import java.util.UUID

/** BLE identifiers and protocol constants of the WIZPR Ring. */
object WizprBle {
    val SERVICE: UUID = UUID.fromString("00000000-dc2e-4362-93d3-df429eb3ad10")
    val AUDIO_CHAR: UUID = UUID.fromString("00000001-dc2e-4362-93d3-df429eb3ad10")
    val TRANSFER_STATUS_CHAR: UUID = UUID.fromString("00000005-dc2e-4362-93d3-df429eb3ad10")
    val OPERATION_CHAR: UUID = UUID.fromString("00000007-dc2e-4362-93d3-df429eb3ad10")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised local-name prefix of the ring. */
    const val RING_NAME_PREFIX = "WIZPR RING"

    /** Sample rate of the decoded audio stream. */
    const val SAMPLE_RATE_HZ = 16_000
}

/** Single-byte values the ring sends on the transfer-status characteristic. */
object TransferStatus {
    const val START: Byte = '1'.code.toByte()
    const val STOP: Byte = '0'.code.toByte()
}

/**
 * The allowlisted commands the host writes to the operation characteristic.
 * Mirrors the SDK, which deliberately exposes no raw firmware command transport.
 */
enum class OperationCommand(val wire: String) {
    SAMPLE_RATE_16("sample_rate 16"),
    BATTERY_STATUS("BATTERY"),
    LOCK("LOCK");

    fun bytes(): ByteArray = wire.toByteArray(Charsets.US_ASCII)
}

/** Same candidate rule as the SDK scanner: service UUID advertised, or name contains the prefix. */
fun isRingCandidate(name: String?, advertisedServices: Collection<UUID>): Boolean =
    WizprBle.SERVICE in advertisedServices ||
        (name?.uppercase()?.contains(WizprBle.RING_NAME_PREFIX) ?: false)
