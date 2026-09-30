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
        return gainForPeak(peak)
    }

    /** Same as [gainFor] for a peak measured elsewhere (e.g. while streaming a long file). */
    fun gainForPeak(peak: Int): Float {
        if (peak <= 0) return 1f
        return (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN)
    }

    fun leadSamples(sampleRate: Int = WizprBle.SAMPLE_RATE_HZ) = sampleRate * LEAD_SILENCE_MS / 1000

    fun tailSamples(sampleRate: Int = WizprBle.SAMPLE_RATE_HZ) = sampleRate * TAIL_SILENCE_MS / 1000

    /** Apply [gain] in place with clipping; for chunk-wise processing of long recordings. */
    fun applyGain(chunk: ShortArray, count: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until count) {
            chunk[i] = (chunk[i] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun prepare(samples: ShortArray, sampleRate: Int = WizprBle.SAMPLE_RATE_HZ): ShortArray {
        val gain = gainFor(samples)
        val lead = leadSamples(sampleRate)
        val out = ShortArray(lead + samples.size + tailSamples(sampleRate))
        samples.copyInto(out, destinationOffset = lead)
        val body = samples.copyOf()
        applyGain(body, body.size, gain)
        body.copyInto(out, destinationOffset = lead)
        return out
    }
}
