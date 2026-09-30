package io.github.hagbard235.ringnotes.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Prepares recorded PCM for speech recognition:
 *  - raises quiet recordings to a good level (the phone microphone source used
 *    for recognition has no automatic gain), never lowers loud ones, and caps
 *    the gain so noise is not blown up;
 *  - pads silence before and after, because recognizers tend to swallow the
 *    first word when speech starts at sample 0 and the last one when it stops abruptly.
 */
object RecognitionAudio {
    const val LEAD_SILENCE_MS = 1000
    const val TAIL_SILENCE_MS = 800
    private const val TARGET_PEAK = 0.8f * Short.MAX_VALUE
    private const val MAX_GAIN = 8f

    /** Gain that brings [samples] to the target peak, between 1 and [MAX_GAIN]. */
    fun gainFor(samples: ShortArray): Float {
        var peak = 0
        for (s in samples) peak = maxOf(peak, abs(s.toInt()))
        if (peak == 0) return 1f
        return (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN)
    }

    fun prepare(samples: ShortArray, sampleRate: Int = WizprBle.SAMPLE_RATE_HZ): ShortArray {
        val gain = gainFor(samples)
        val lead = sampleRate * LEAD_SILENCE_MS / 1000
        val tail = sampleRate * TAIL_SILENCE_MS / 1000
        val out = ShortArray(lead + samples.size + tail)
        for (i in samples.indices) {
            val v = (samples[i] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[lead + i] = v.toShort()
        }
        return out
    }
}
