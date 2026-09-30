package io.github.hagbard235.ringnotes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecognitionAudioTest {
    @Test
    fun padsSilenceBeforeAndAfter() {
        val out = RecognitionAudio.prepare(shortArrayOf(1000, -1000), sampleRate = 16_000)
        val lead = 16_000 * RecognitionAudio.LEAD_SILENCE_MS / 1000
        val tail = 16_000 * RecognitionAudio.TAIL_SILENCE_MS / 1000
        assertEquals(lead + 2 + tail, out.size)
        assertTrue(out.take(lead).all { it == 0.toShort() })
        assertTrue(out.takeLast(tail).all { it == 0.toShort() })
    }

    @Test
    fun raisesQuietAudioWithCappedGain() {
        // Very quiet: gain is capped at 8.
        assertEquals(8f, RecognitionAudio.gainFor(shortArrayOf(100, -50)))
        // Moderately quiet: brought to 80 % of full scale.
        val gain = RecognitionAudio.gainFor(shortArrayOf(8000))
        assertEquals(0.8f * Short.MAX_VALUE / 8000, gain, 1e-3f)
    }

    @Test
    fun neverLowersLoudAudio() {
        assertEquals(1f, RecognitionAudio.gainFor(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)))
        assertEquals(1f, RecognitionAudio.gainFor(ShortArray(10)))
    }

    @Test
    fun chunkedGainMatchesWholeFile() {
        val samples = ShortArray(1000) { ((it % 50) * 60 - 1500).toShort() }
        val whole = RecognitionAudio.prepare(samples, sampleRate = 1000)
        val gain = RecognitionAudio.gainForPeak(samples.maxOf { kotlin.math.abs(it.toInt()) })
        val chunk = samples.copyOf()
        RecognitionAudio.applyGain(chunk, chunk.size, gain)
        val lead = RecognitionAudio.leadSamples(1000)
        kotlin.test.assertContentEquals(whole.copyOfRange(lead, lead + samples.size), chunk)
    }

    @Test
    fun appliedGainStaysInRange() {
        val out = RecognitionAudio.prepare(shortArrayOf(4000, -4000), sampleRate = 1000)
        val lead = 1000 * RecognitionAudio.LEAD_SILENCE_MS / 1000
        assertTrue(out[lead] in 26000..26300)
        assertTrue(out[lead + 1] in -26300..-26000)
    }
}
