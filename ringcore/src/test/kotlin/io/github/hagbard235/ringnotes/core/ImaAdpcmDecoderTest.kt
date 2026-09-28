package io.github.hagbard235.ringnotes.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ImaAdpcmDecoderTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun eachByteYieldsTwoSamples() {
        assertEquals(6, ImaAdpcmDecoder().decode(bytes(0x12, 0x34, 0x56)).size)
    }

    @Test
    fun decodesKnownVectorHighNibbleFirst() {
        val d = ImaAdpcmDecoder()
        val out = d.decode(bytes(0x12, 0x34, 0x56, 0x78))
        assertContentEquals(shortArrayOf(1, 4, 8, 15, 27, 47, 88, 82), out)
        assertEquals(82, d.predictedSample)
        assertEquals(19, d.stepIndex)
    }

    @Test
    fun stateAdvancesAcrossCallsAndResets() {
        val d = ImaAdpcmDecoder()
        d.decode(bytes(0x12, 0x34))
        val first = d.stepIndex
        assertNotEquals(0, first)
        d.decode(bytes(0x56, 0x78))
        assertNotEquals(first, d.stepIndex)
        d.reset()
        assertEquals(0, d.stepIndex)
        assertEquals(0, d.predictedSample)
    }

    @Test
    fun stepIndexStaysInRange() {
        val d = ImaAdpcmDecoder()
        repeat(1000) {
            d.decode(ByteArray(32) { 0x77 })
            assertTrue(d.stepIndex in 0..88)
        }
    }

    @Test
    fun handlesSignedBytes() {
        // 0xFF must be treated as two nibbles of 0xF, not as a negative int.
        val out = ImaAdpcmDecoder().decode(bytes(0xff))
        assertEquals(2, out.size)
        assertTrue(out[0] < 0)
    }
}
