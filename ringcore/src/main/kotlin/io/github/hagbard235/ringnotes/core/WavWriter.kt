// WAV writing adapted from wizpr-ring-core (audio.rs) of https://github.com/vtouchio/wizpr-ring-sdk
// Copyright VTouch Inc., licensed under Apache-2.0. Modified: translated to Kotlin, made streaming.
package io.github.hagbard235.ringnotes.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM into a canonical 44-byte-header WAV file.
 *
 * The header is written with zero sizes up front and patched in [close], so
 * long recordings never have to be held in memory. An optional [gain] is
 * applied while writing (the SDK's desktop example defaults to 3.0) and
 * samples are clipped to the 16-bit range.
 */
class StreamingWavWriter(
    private val file: File,
    private val sampleRate: Int = WizprBle.SAMPLE_RATE_HZ,
    private val gain: Float = 1f,
) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var closed = false

    var sampleCount: Long = 0
        private set
    var clippedCount: Long = 0
        private set

    init {
        out.write(wavHeader(0, sampleRate))
    }

    fun append(samples: ShortArray) {
        check(!closed) { "writer is closed" }
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val scaled = Math.round(s * gain)
            val clipped = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (clipped != scaled) clippedCount++
            buf.putShort(clipped.toShort())
        }
        out.write(buf.array())
        sampleCount += samples.size
    }

    val durationMs: Long get() = sampleCount * 1000 / sampleRate

    override fun close() {
        if (closed) return
        closed = true
        out.close()
        require(sampleCount * 2 <= MAX_DATA_BYTES) { "too many samples for a canonical RIFF/WAV file" }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(wavHeader(sampleCount * 2, sampleRate))
        }
    }

    companion object {
        private const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36

        /** Build the 44-byte header for a 16-bit mono PCM WAV with [dataBytes] of audio. */
        fun wavHeader(dataBytes: Long, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val blockAlign = channels * bitsPerSample / 8
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + dataBytes).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * blockAlign)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.toInt())
            }.array()
        }
    }
}
