package io.github.hagbard235.ringnotes.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamingWavWriterTest {
    private fun tmp() = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
    private fun le(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun writesCanonicalHeaderAndPatchesSizes() {
        val f = tmp()
        StreamingWavWriter(f).use { it.append(ShortArray(60)); it.append(ShortArray(40)) }
        val b = f.readBytes()
        assertEquals(44 + 200, b.size)
        assertContentEquals("RIFF".toByteArray(), b.copyOfRange(0, 4))
        assertContentEquals("WAVE".toByteArray(), b.copyOfRange(8, 12))
        assertContentEquals("data".toByteArray(), b.copyOfRange(36, 40))
        assertEquals(36 + 200, le(b, 4))
        assertEquals(16_000, le(b, 24))
        assertEquals(200, le(b, 40))
    }

    @Test
    fun samplesAreLittleEndianAndGainClips() {
        val f = tmp()
        val w = StreamingWavWriter(f, gain = 3f)
        w.use { it.append(shortArrayOf(0x100, -1, 20_000)) }
        val b = ByteBuffer.wrap(f.readBytes(), 44, 6).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x300.toShort(), b.short)
        assertEquals((-3).toShort(), b.short)
        assertEquals(Short.MAX_VALUE, b.short)
        assertEquals(1, w.clippedCount)
        assertEquals(3, w.sampleCount)
    }
}
