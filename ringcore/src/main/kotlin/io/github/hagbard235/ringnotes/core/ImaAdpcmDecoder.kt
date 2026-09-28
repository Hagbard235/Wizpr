// Ported from wizpr-ring-core (codec.rs) of https://github.com/vtouchio/wizpr-ring-sdk
// Copyright VTouch Inc., licensed under Apache-2.0. Modified: translated to Kotlin.
package io.github.hagbard235.ringnotes.core

/**
 * Streaming IMA ADPCM decoder for the ring's audio characteristic.
 *
 * Each byte holds two 4-bit nibbles (high nibble first) and yields two
 * 16-bit PCM samples. State carries across BLE packets so a recording
 * decodes continuously; call [reset] when a new transfer starts.
 */
class ImaAdpcmDecoder {
    var predictedSample: Int = 0
        private set
    var stepIndex: Int = 0
        private set

    fun reset() {
        predictedSample = 0
        stepIndex = 0
    }

    fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        var i = 0
        for (b in data) {
            val byte = b.toInt() and 0xff
            out[i++] = decodeSample((byte shr 4) and 0x0f)
            out[i++] = decodeSample(byte and 0x0f)
        }
        return out
    }

    private fun decodeSample(nibble: Int): Short {
        val step = STEP_TABLE[stepIndex]
        var diff = step shr 3
        if (nibble and 0x01 != 0) diff += step shr 2
        if (nibble and 0x02 != 0) diff += step shr 1
        if (nibble and 0x04 != 0) diff += step
        if (nibble and 0x08 != 0) diff = -diff

        val sample = (predictedSample + diff).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        predictedSample = sample
        stepIndex = (stepIndex + STEP_ADJUST[nibble and 0x07]).coerceIn(0, 88)
        return sample.toShort()
    }

    private companion object {
        val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
            73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
            494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
            2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
            10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
        val STEP_ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    }
}
